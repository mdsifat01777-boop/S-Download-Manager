package com.flowfetch.app.data.repository

import android.content.Context
import com.flowfetch.app.data.download.DownloadForegroundService
import com.flowfetch.app.data.download.DownloadHistoryStore
import com.flowfetch.app.data.download.DownloadManagerHelper
import com.flowfetch.app.data.extractor.BeegExtractor
import com.flowfetch.app.data.extractor.Extractor
import com.flowfetch.app.data.extractor.GenericWebVideoExtractor
import com.flowfetch.app.data.extractor.NativeYtDlpExtractor
import com.flowfetch.app.data.extractor.SmartVideoExtractor
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VideoRepository(
    private val context: Context,
    private val beegExtractor: Extractor = BeegExtractor(),
    private val nativeExtractor: Extractor = NativeYtDlpExtractor(context),
    private val smartExtractor: Extractor = SmartVideoExtractor(context),
    private val genericWebExtractor: Extractor = GenericWebVideoExtractor(),
    private val downloadHelper: DownloadManagerHelper = DownloadManagerHelper(context)
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val historyStore = DownloadHistoryStore(context.applicationContext)

    private val _downloads = MutableStateFlow(historyStore.load())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    @Volatile
    private var lastNotifUpdateMs = 0L
    @Volatile
    private var lastPersistMs = 0L

    /**
     * High-speed platform-aware URL extraction.
     * Runs compatible extractors concurrently and prefers true HD streams over SD fallbacks.
     */
    suspend fun analyzeUrl(url: String): Result<VideoMetadata> {
        val cleanUrl = url.trim()
        val platform = Platform.detect(cleanUrl)

        // Priority 1: Dedicated Beeg.com Engine
        if (beegExtractor.canHandle(cleanUrl)) {
            val beegResult = try {
                withTimeoutOrNull(10_000L) { beegExtractor.extract(cleanUrl) }
            } catch (_: Throwable) { null }
            val meta = beegResult?.getOrNull()
            if (beegResult != null && beegResult.isSuccess && meta != null && meta.formats.any { !it.isAudioOnly }) {
                return beegResult
            }
        }

        // Priority 2: Parallel race tailored per platform
        val raceWinner = when (platform) {
            Platform.FACEBOOK -> {
                // Race SmartVideoExtractor (SnapSave / Direct) vs NativeYtDlpExtractor.
                // If SmartVideoExtractor only finds 360p SD, give NativeYtDlp a few extra seconds to provide true 720p/1080p HD!
                raceExtractors(
                    timeoutMs = 16_000L,
                    preferHd = true,
                    tasks = listOf(
                        { smartExtractor.extract(cleanUrl) },
                        { nativeExtractor.extract(cleanUrl) }
                    )
                )
            }
            Platform.TIKTOK, Platform.INSTAGRAM -> {
                raceExtractors(
                    timeoutMs = 14_000L,
                    preferHd = false,
                    tasks = listOf(
                        { smartExtractor.extract(cleanUrl) },
                        {
                            delay(300L)
                            nativeExtractor.extract(cleanUrl)
                        }
                    )
                )
            }
            Platform.YOUTUBE, Platform.TWITTER -> {
                val primary = try {
                    nativeExtractor.extract(cleanUrl)
                } catch (t: Throwable) { Result.failure(t) }
                val meta = primary.getOrNull()
                if (primary.isSuccess && meta != null && meta.formats.any { !it.isAudioOnly }) {
                    return primary
                }
                try {
                    withTimeoutOrNull(10_000L) { smartExtractor.extract(cleanUrl) }
                } catch (_: Throwable) { null }
            }
            Platform.GENERIC -> {
                raceExtractors(
                    timeoutMs = 15_000L,
                    preferHd = false,
                    tasks = listOf(
                        { nativeExtractor.extract(cleanUrl) },
                        {
                            delay(2_000L)
                            genericWebExtractor.extract(cleanUrl)
                        }
                    )
                )
            }
        }

        if (raceWinner != null && raceWinner.isSuccess) {
            return raceWinner
        }

        // Final fallback: Generic HTML5/OG scraper
        if (platform != Platform.GENERIC) {
            val genericResult = try {
                withTimeoutOrNull(8_000L) { genericWebExtractor.extract(cleanUrl) }
            } catch (_: Throwable) { null }
            val gMeta = genericResult?.getOrNull()
            if (genericResult != null && genericResult.isSuccess && gMeta != null && gMeta.formats.isNotEmpty()) {
                return genericResult
            }
        }

        return Result.failure(Exception("Could not extract video from this URL. Try using the Web Sniffer tab."))
    }

    private fun hasHdVideoFormat(meta: VideoMetadata): Boolean {
        return meta.formats.any { fmt ->
            if (fmt.isAudioOnly) return@any false
            val labelAndRes = "${fmt.resolution.orEmpty()} ${fmt.qualityLabel}".lowercase()
            labelAndRes.contains("2160") || labelAndRes.contains("1440") ||
                labelAndRes.contains("1080") || labelAndRes.contains("720") ||
                labelAndRes.contains("hd") && !labelAndRes.contains("sd")
        }
    }

    private suspend fun raceExtractors(
        timeoutMs: Long,
        preferHd: Boolean,
        tasks: List<suspend () -> Result<VideoMetadata>>
    ): Result<VideoMetadata>? {
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val resultsChannel = Channel<Result<VideoMetadata>>(capacity = tasks.size)
                val jobs = tasks.map { task ->
                    launch(Dispatchers.IO) {
                        val res = try {
                            task()
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                        resultsChannel.trySend(res)
                    }
                }

                var bestVideoFallback: Result<VideoMetadata>? = null
                var bestAudioFallback: Result<VideoMetadata>? = null

                for (i in tasks.indices) {
                    // If we already have an SD video fallback and preferHd is true, wait up to 5s for an HD winner
                    val res = if (preferHd && bestVideoFallback != null) {
                        withTimeoutOrNull(5_000L) { resultsChannel.receive() } ?: break
                    } else {
                        resultsChannel.receive()
                    }

                    val meta = res.getOrNull()
                    if (res.isSuccess && meta != null && meta.formats.isNotEmpty()) {
                        val hasVideo = meta.formats.any { !it.isAudioOnly }
                        if (hasVideo) {
                            if (!preferHd || hasHdVideoFormat(meta)) {
                                jobs.forEach { it.cancel() }
                                return@coroutineScope res
                            } else if (bestVideoFallback == null) {
                                bestVideoFallback = res
                            }
                        } else if (bestAudioFallback == null) {
                            bestAudioFallback = res
                        }
                    }
                }
                jobs.forEach { it.cancel() }
                bestVideoFallback ?: bestAudioFallback
            }
        }
    }

    fun startDownload(metadata: VideoMetadata, format: VideoFormat, replaceItemId: Long? = null): Long {
        val downloadId = System.currentTimeMillis()

        val newItem = DownloadItem(
            id = downloadId,
            title = metadata.title,
            originalUrl = metadata.originalUrl,
            downloadUrl = format.downloadUrl,
            thumbnailUrl = metadata.thumbnailUrl,
            platform = metadata.platform,
            formatLabel = format.qualityLabel,
            fileExtension = format.fileExtension,
            mimeType = format.mimeType,
            totalBytes = format.estimatedBytes,
            downloadedBytes = 0L,
            progress = 0f,
            status = DownloadStatus.DOWNLOADING,
            formatId = format.id,
            audioUrl = format.audioUrl,
            resolution = format.resolution
        )

        _downloads.update { current ->
            val cleaned = if (replaceItemId != null) current.filterNot { it.id == replaceItemId } else current
            listOf(newItem) + cleaned
        }
        persistNow()
        notifyForegroundService(completedTitle = null, completedSuccess = false)

        scope.launch {
            val result = downloadHelper.downloadWithYtDlp(metadata, format) { bytesDownloaded, totalBytes, progress ->
                _downloads.update { list ->
                    list.map { item ->
                        if (item.id == downloadId) {
                            item.copy(
                                downloadedBytes = bytesDownloaded,
                                totalBytes = totalBytes,
                                progress = progress,
                                status = if (progress >= 100f) DownloadStatus.COMPLETED else DownloadStatus.DOWNLOADING
                            )
                        } else item
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastNotifUpdateMs > 500L) {
                    lastNotifUpdateMs = now
                    notifyForegroundService(completedTitle = null, completedSuccess = false)
                }
                if (now - lastPersistMs > 2_000L) {
                    lastPersistMs = now
                    persistNow()
                }
            }

            if (result.isSuccess) {
                val file = result.getOrNull()
                _downloads.update { list ->
                    list.map { item ->
                        if (item.id == downloadId) {
                            item.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 100f,
                                filePath = file?.absolutePath,
                                downloadedBytes = file?.length() ?: item.totalBytes
                            )
                        } else item
                    }
                }
                persistNow()
                notifyForegroundService(completedTitle = metadata.title, completedSuccess = true)
            } else {
                _downloads.update { list ->
                    list.map { item ->
                        if (item.id == downloadId) {
                            item.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = result.exceptionOrNull()?.message ?: "Download failed"
                            )
                        } else item
                    }
                }
                persistNow()
                notifyForegroundService(completedTitle = null, completedSuccess = false)
            }
        }

        return downloadId
    }

    private fun persistNow() {
        historyStore.save(_downloads.value)
    }

    private fun notifyForegroundService(completedTitle: String?, completedSuccess: Boolean) {
        val active = _downloads.value.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.ENQUEUED
        }
        if (active.isNotEmpty()) {
            val avgProgress = active.map { it.progress }.average().toInt().coerceIn(0, 100)
            DownloadForegroundService.updateProgress(
                context = context,
                title = active.first().title,
                activeCount = active.size,
                progress = avgProgress
            )
        } else {
            DownloadForegroundService.stop(
                context = context,
                completedTitle = completedTitle,
                success = completedSuccess
            )
        }
    }

    fun updateThumbnail(downloadId: Long, thumbnailUrl: String) {
        if (thumbnailUrl.isBlank()) return
        _downloads.update { list ->
            list.map { item ->
                if (item.id == downloadId) item.copy(thumbnailUrl = thumbnailUrl) else item
            }
        }
        persistNow()
    }

    fun openDownloadedFile(item: DownloadItem) {
        downloadHelper.openDownloadedFile(item)
    }

    fun deleteDownload(item: DownloadItem): Boolean {
        val deleted = downloadHelper.deleteDownloadedFile(item)
        _downloads.update { list -> list.filterNot { it.id == item.id } }
        persistNow()
        return deleted
    }

    fun cancelDownload(downloadId: Long) {
        _downloads.update { list -> list.filterNot { it.id == downloadId } }
        persistNow()
        notifyForegroundService(completedTitle = null, completedSuccess = false)
    }

    fun retryDownload(item: DownloadItem) {
        val format = VideoFormat(
            id = item.formatId.ifBlank { "retry_fmt" },
            qualityLabel = item.formatLabel,
            fileExtension = item.fileExtension,
            mimeType = item.mimeType,
            estimatedBytes = item.totalBytes,
            downloadUrl = item.downloadUrl,
            isAudioOnly = item.fileExtension.equals("mp3", ignoreCase = true),
            resolution = item.resolution,
            audioUrl = item.audioUrl
        )
        val meta = VideoMetadata(
            id = item.id.toString(),
            originalUrl = item.originalUrl,
            title = item.title,
            author = "Media",
            durationSeconds = 0,
            thumbnailUrl = item.thumbnailUrl,
            platform = item.platform,
            formats = listOf(format)
        )
        startDownload(meta, format, replaceItemId = item.id)
    }
}

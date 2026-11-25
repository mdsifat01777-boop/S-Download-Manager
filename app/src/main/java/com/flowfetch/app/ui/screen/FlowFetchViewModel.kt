package com.flowfetch.app.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowfetch.app.FlowFetchApplication
import com.flowfetch.app.data.browser.DetectedStream
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import com.flowfetch.app.data.repository.VideoRepository
import com.flowfetch.app.ui.components.DownloadFilter
import com.flowfetch.app.ui.utils.ClipboardHelper
import com.flowfetch.app.ui.utils.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class AppTab {
    DOWNLOADER,
    BROWSER
}

data class FlowFetchUiState(
    val selectedAppTab: AppTab = AppTab.DOWNLOADER,
    val urlInput: String = "",
    val detectedClipboardUrl: String? = null,
    val showClipboardCard: Boolean = false,
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val extractedVideo: VideoMetadata? = null,
    val selectedFormat: VideoFormat? = null,
    val downloads: List<DownloadItem> = emptyList(),
    val selectedFilter: DownloadFilter = DownloadFilter.ALL,
    val userMessage: String? = null,
    val showInfoDialog: Boolean = false,
    val isDarkTheme: Boolean = true,
    val itemPendingDeletion: DownloadItem? = null
)

class FlowFetchViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: VideoRepository =
        (application as? FlowFetchApplication)?.videoRepository ?: VideoRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(FlowFetchUiState())
    val uiState: StateFlow<FlowFetchUiState> = _uiState.asStateFlow()

    private var lastAnalyzedClipboard: String? = null
    private var analyzeJob: Job? = null
    private val analysisSeq = AtomicLong(0L)

    init {
        viewModelScope.launch {
            repository.downloads.collect { list ->
                _uiState.update { it.copy(downloads = list) }
            }
        }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDarkTheme = !it.isDarkTheme) }
    }

    fun onUrlChanged(newUrl: String) {
        if (newUrl.isBlank()) {
            cancelActiveAnalysis()
        }
        _uiState.update {
            if (newUrl.isBlank()) {
                it.copy(
                    urlInput = newUrl,
                    extractedVideo = null,
                    selectedFormat = null,
                    isLoading = false,
                    isDownloading = false,
                    showClipboardCard = false,
                    userMessage = null
                )
            } else {
                it.copy(urlInput = newUrl)
            }
        }
    }

    fun onClearInput() {
        cancelActiveAnalysis()
        lastAnalyzedClipboard = null
        _uiState.update {
            it.copy(
                urlInput = "",
                extractedVideo = null,
                selectedFormat = null,
                isLoading = false,
                isDownloading = false,
                showClipboardCard = false,
                userMessage = null
            )
        }
    }

    private fun cancelActiveAnalysis() {
        analysisSeq.incrementAndGet()
        analyzeJob?.cancel()
        analyzeJob = null
    }

    fun onPasteFromClipboard(context: Context) {
        val clip = ClipboardHelper.getClipboardUrl(context)
        if (!clip.isNullOrBlank()) {
            _uiState.update { it.copy(urlInput = clip) }
            analyzeUrl(clip)
        } else {
            _uiState.update { it.copy(userMessage = "No valid URL found on clipboard") }
        }
    }

    fun checkClipboardOnResume(context: Context) {
        val clip = ClipboardHelper.getClipboardUrl(context)
        if (!clip.isNullOrBlank() && clip != lastAnalyzedClipboard && clip != _uiState.value.urlInput) {
            _uiState.update {
                it.copy(
                    detectedClipboardUrl = clip,
                    showClipboardCard = true
                )
            }
        }
    }

    fun dismissClipboardCard() {
        lastAnalyzedClipboard = _uiState.value.detectedClipboardUrl
        _uiState.update { it.copy(showClipboardCard = false) }
    }

    fun analyzeUrl(customUrl: String? = null) {
        val target = (customUrl ?: _uiState.value.urlInput).trim()
        if (target.isBlank() || !Formatters.isValidUrl(target)) {
            _uiState.update { it.copy(userMessage = "Please enter a valid HTTP or HTTPS video URL") }
            return
        }

        // Cancel any previous in-flight analysis so stale results can never overwrite newer input
        analyzeJob?.cancel()
        val currentSeq = analysisSeq.incrementAndGet()
        lastAnalyzedClipboard = target

        _uiState.update {
            it.copy(
                urlInput = target,
                isLoading = true,
                extractedVideo = null,
                selectedFormat = null,
                showClipboardCard = false,
                userMessage = null
            )
        }

        analyzeJob = viewModelScope.launch {
            val result = repository.analyzeUrl(target)
            // Ignore result if cancelled or superseded by a clear / new paste
            if (!isActive || currentSeq != analysisSeq.get()) return@launch

            if (result.isSuccess) {
                val metadata = result.getOrNull()
                val defaultFormat = metadata?.formats?.firstOrNull { !it.isAudioOnly } ?: metadata?.formats?.firstOrNull()
                _uiState.update {
                    if (currentSeq == analysisSeq.get()) {
                        it.copy(
                            isLoading = false,
                            extractedVideo = metadata,
                            selectedFormat = defaultFormat
                        )
                    } else it
                }
            } else {
                _uiState.update {
                    if (currentSeq == analysisSeq.get()) {
                        it.copy(
                            isLoading = false,
                            userMessage = result.exceptionOrNull()?.message ?: "Failed to extract video details"
                        )
                    } else it
                }
            }
        }
    }

    fun selectFormat(format: VideoFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun startDownload() {
        val video = _uiState.value.extractedVideo
        val format = _uiState.value.selectedFormat

        if (video == null || format == null) {
            _uiState.update { it.copy(userMessage = "No video or format selected") }
            return
        }

        _uiState.update { it.copy(isDownloading = true) }

        viewModelScope.launch {
            try {
                repository.startDownload(video, format)
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        userMessage = "Download started! Saving to Gallery."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        userMessage = "Could not start download: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun openDownloadedFile(item: DownloadItem) {
        repository.openDownloadedFile(item)
    }

    fun requestDelete(item: DownloadItem) {
        _uiState.update { it.copy(itemPendingDeletion = item) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(itemPendingDeletion = null) }
    }

    fun confirmDelete() {
        val item = _uiState.value.itemPendingDeletion ?: return
        repository.deleteDownload(item)
        _uiState.update {
            it.copy(
                itemPendingDeletion = null,
                userMessage = "Deleted from device storage"
            )
        }
    }

    fun cancelDownload(downloadId: Long) {
        repository.cancelDownload(downloadId)
        _uiState.update { it.copy(userMessage = "Download removed") }
    }

    fun retryDownload(item: DownloadItem) {
        repository.retryDownload(item)
    }

    fun setFilter(filter: DownloadFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun setInfoDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showInfoDialog = visible) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun selectAppTab(tab: AppTab) {
        _uiState.update { it.copy(selectedAppTab = tab) }
    }

    /**
     * Starts a download captured from Web Sniffer directly in the Downloads queue
     * WITHOUT overwriting the main Downloader URL input or preview card.
     */
    fun downloadDetectedFromBrowser(stream: DetectedStream, pageTitle: String, pageUrl: String) {
        val isAudio = stream.fileExtension.equals("mp3", ignoreCase = true) || stream.fileExtension.equals("m4a", ignoreCase = true)
        val formatIdPrefix = if (stream.isPageLevel) "sniffed_page_" else "sniffed_"
        val format = VideoFormat(
            id = formatIdPrefix + stream.id,
            qualityLabel = stream.label,
            fileExtension = if (stream.fileExtension == "m3u8") "mp4" else stream.fileExtension,
            mimeType = if (stream.fileExtension == "m3u8") "video/mp4" else stream.mimeType,
            estimatedBytes = stream.estimatedBytes,
            downloadUrl = stream.url,
            resolution = stream.resolution,
            isAudioOnly = isAudio,
            audioUrl = stream.audioUrl
        )

        val pagePlatform = Platform.detect(pageUrl)
        val initialThumb = stream.thumbnailUrl.takeIf { it.startsWith("http") }
            ?: "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"

        val metadata = VideoMetadata(
            id = UUID.randomUUID().toString().take(8),
            originalUrl = pageUrl,
            title = pageTitle.ifBlank { "Web Video" },
            author = "Web Sniffer Source",
            durationSeconds = stream.durationSeconds,
            thumbnailUrl = initialThumb,
            platform = pagePlatform,
            formats = listOf(format)
        )

        val downloadId = repository.startDownload(metadata, format)
        _uiState.update {
            it.copy(
                selectedAppTab = AppTab.DOWNLOADER,
                userMessage = "Download started in queue! Saving to Gallery."
            )
        }

        // Fetch og:image asynchronously if DOM did not provide a thumbnail
        if (!stream.thumbnailUrl.startsWith("http") && pageUrl.startsWith("http")) {
            viewModelScope.launch(Dispatchers.IO) {
                val fetchedThumb = extractPageThumbnail(pageUrl)
                if (fetchedThumb.startsWith("http") && !fetchedThumb.contains("unsplash.com")) {
                    repository.updateThumbnail(downloadId, fetchedThumb)
                }
            }
        }
    }

    private fun extractPageThumbnail(pageUrl: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = okhttp3.Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "TelegramBot (like TwitterBot)")
                .header("Accept", "text/html")
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string()?.take(50_000) ?: ""
            val ogMatch = Regex("""<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image["']""", RegexOption.IGNORE_CASE).find(html)
            val thumb = ogMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
            if (thumb.startsWith("http")) thumb else "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
        } catch (_: Exception) {
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
        }
    }
}

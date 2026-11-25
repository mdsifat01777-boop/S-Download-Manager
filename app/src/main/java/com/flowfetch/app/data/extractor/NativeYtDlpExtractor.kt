package com.flowfetch.app.data.extractor

import android.content.Context
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device Native yt-dlp & FFmpeg Extractor Engine.
 * Extracts video metadata and available quality streams directly on-device
 * across 1,800+ websites without relying on fragile third-party scrapers.
 */
class NativeYtDlpExtractor(
    private val context: Context? = null
) : Extractor {

    override val name: String = "NativeYtDlpExtractor"
    private val twitterExtractor = TwitterFxExtractor()

    override fun canHandle(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        val platform = Platform.detect(cleanUrl)

        // If Twitter / X, try direct Fx engine if desired or via yt-dlp
        if (platform == Platform.TWITTER) {
            val fxResult = twitterExtractor.extract(cleanUrl)
            if (fxResult.isSuccess) return@withContext fxResult
        }

        try {
            val targetUrl = if (platform == Platform.TWITTER && cleanUrl.contains("x.com")) {
                cleanUrl.replace("x.com", "twitter.com")
            } else cleanUrl

            val request = YoutubeDLRequest(targetUrl).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--skip-download")
                addOption("--socket-timeout", "10")
                addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
                addOption("--referer", cleanUrl)

                // Inject logged-in browser cookies (for private Facebook/Instagram/X videos)
                try {
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(cleanUrl)
                    if (!cookies.isNullOrBlank()) {
                        addOption("--add-header", "Cookie:$cookies")
                    }
                } catch (_: Throwable) {}
            }

            // Hard non-blocking timeout so yt-dlp never stalls the UI loading spinner
            val timeoutMs = if (platform == Platform.YOUTUBE) 22_000L else 14_000L
            val videoInfo = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<com.yausername.youtubedl_android.mapper.VideoInfo> { cont ->
                    val worker = Thread {
                        try {
                            val info = YoutubeDL.getInstance().getInfo(request)
                            if (cont.isActive) cont.resume(info)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }.apply { isDaemon = true }
                    cont.invokeOnCancellation {
                        try { worker.interrupt() } catch (_: Throwable) {}
                    }
                    worker.start()
                }
            } ?: return@withContext Result.failure(Exception("Extraction timed out"))
            val title = videoInfo.title?.ifBlank { "Media Video" } ?: "Media Video"
            val author = videoInfo.uploader?.ifBlank { "Content Creator" } ?: "Content Creator"
            val duration = videoInfo.duration
            val thumb = videoInfo.thumbnail?.ifBlank {
                "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
            } ?: "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"

            val formats = mutableListOf<VideoFormat>()

            val nativeFormats = videoInfo.formats
            if (!nativeFormats.isNullOrEmpty()) {
                val videoByHeight = mutableMapOf<Int, com.yausername.youtubedl_android.mapper.VideoFormat>()
                var bestAudio: com.yausername.youtubedl_android.mapper.VideoFormat? = null

                for (f in nativeFormats) {
                    val height = f.height
                    val vcodec = f.vcodec ?: "none"
                    val acodec = f.acodec ?: "none"
                    val fmtIdLower = f.formatId.orEmpty().lowercase()
                    val extLower = f.ext.orEmpty().lowercase()

                    val isAudioExt = extLower in listOf("m4a", "mp3", "aac", "opus", "ogg", "wav", "flac")
                    val isAudioOnly = (vcodec == "none" && acodec != "none") &&
                        (isAudioExt || (!fmtIdLower.contains("hd") && !fmtIdLower.contains("sd") && extLower != "mp4"))

                    val isVideo = !isAudioOnly && (vcodec != "none" || height > 0 || fmtIdLower.contains("hd") || fmtIdLower.contains("sd") || extLower == "mp4")

                    val currentSize = if (f.fileSize > 0L) f.fileSize else f.fileSizeApproximate

                    if (isAudioOnly) {
                        val bestSize = if (bestAudio != null) {
                            if (bestAudio.fileSize > 0L) bestAudio.fileSize else bestAudio.fileSizeApproximate
                        } else 0L

                        if (bestAudio == null || currentSize > bestSize) {
                            bestAudio = f
                        }
                    } else if (isVideo) {
                        // Resolve height even when yt-dlp omits height metadata (common on Facebook hd/sd)
                        val resolvedHeight = when {
                            height > 0 -> height
                            fmtIdLower.contains("1080") -> 1080
                            fmtIdLower.contains("hd") || f.formatNote.orEmpty().contains("hd", true) -> 720
                            fmtIdLower.contains("720") -> 720
                            fmtIdLower.contains("480") -> 480
                            fmtIdLower.contains("sd") -> 360
                            else -> 480
                        }
                        val existing = videoByHeight[resolvedHeight]
                        val existingSize = if (existing != null) {
                            if (existing.fileSize > 0L) existing.fileSize else existing.fileSizeApproximate
                        } else 0L

                        if (existing == null || currentSize > existingSize) {
                            videoByHeight[resolvedHeight] = f
                        }
                    }
                }

                val sortedHeights = videoByHeight.keys.sortedDescending()
                for (h in sortedHeights) {
                    val f = videoByHeight[h]!!
                    val label = when {
                        h >= 2160 -> "4K Ultra HD (${h}p MP4)"
                        h >= 1440 -> "1440p Quad HD (2K MP4)"
                        h >= 1080 -> "1080p Full HD (MP4)"
                        h >= 720 -> "720p HD Quality (MP4)"
                        h >= 480 -> "480p SD Quality (MP4)"
                        else -> "${h}p Standard Quality (MP4)"
                    }
                    val rawSize = if (f.fileSize > 0L) f.fileSize else f.fileSizeApproximate
                    val estSize = if (rawSize > 0L) rawSize else estimateBytesForHeight(h, duration)

                    formats.add(
                        VideoFormat(
                            id = "ytdlp_${f.formatId ?: "${h}p"}",
                            qualityLabel = label,
                            fileExtension = "mp4",
                            mimeType = "video/mp4",
                            estimatedBytes = estSize,
                            downloadUrl = f.url ?: cleanUrl,
                            resolution = "${h}p",
                            isAudioOnly = false
                        )
                    )
                }

                // Add distinct MP3 Audio Option
                val rawAudioSize = if (bestAudio != null) {
                    if (bestAudio.fileSize > 0L) bestAudio.fileSize else bestAudio.fileSizeApproximate
                } else 0L
                val audioEstSize = if (rawAudioSize > 0L) rawAudioSize else {
                    if (duration > 0) ((320_000L * duration) / 8L).coerceAtLeast(3_000_000L) else 5_000_000L
                }

                formats.add(
                    VideoFormat(
                        id = "ytdlp_audio_mp3",
                        qualityLabel = "MP3 Audio (320 kbps)",
                        fileExtension = "mp3",
                        mimeType = "audio/mpeg",
                        estimatedBytes = audioEstSize,
                        downloadUrl = bestAudio?.url ?: cleanUrl,
                        resolution = "320 kbps",
                        isAudioOnly = true
                    )
                )
            }

            if (formats.isEmpty()) {
                formats.add(
                    VideoFormat(
                        id = "ytdlp_best",
                        qualityLabel = "Best Quality (MP4)",
                        fileExtension = "mp4",
                        mimeType = "video/mp4",
                        estimatedBytes = 25_000_000L,
                        downloadUrl = cleanUrl,
                        resolution = "Best",
                        isAudioOnly = false
                    )
                )
                formats.add(
                    VideoFormat(
                        id = "ytdlp_audio",
                        qualityLabel = "MP3 Audio (320 kbps)",
                        fileExtension = "mp3",
                        mimeType = "audio/mpeg",
                        estimatedBytes = 5_000_000L,
                        downloadUrl = cleanUrl,
                        resolution = "320 kbps",
                        isAudioOnly = true
                    )
                )
            }

            Result.success(
                VideoMetadata(
                    id = videoInfo.id ?: UUID.randomUUID().toString().take(8),
                    originalUrl = cleanUrl,
                    title = title,
                    author = author,
                    durationSeconds = duration,
                    thumbnailUrl = thumb,
                    platform = platform,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            if (platform == Platform.TWITTER) {
                val fx = twitterExtractor.extract(cleanUrl)
                if (fx.isSuccess) return@withContext fx
            }
            Result.failure(e)
        }
    }

    private fun estimateBytesForHeight(height: Int, durationSeconds: Int): Long {
        val bps = when {
            height >= 2160 -> 15_000_000L
            height >= 1440 -> 8_000_000L
            height >= 1080 -> 3_500_000L
            height >= 720 -> 1_800_000L
            height >= 480 -> 900_000L
            else -> 450_000L
        }
        return if (durationSeconds > 0) {
            ((bps * durationSeconds) / 8L).coerceAtLeast(3_000_000L)
        } else {
            when {
                height >= 2160 -> 250_000_000L
                height >= 1440 -> 120_000_000L
                height >= 1080 -> 45_000_000L
                height >= 720 -> 22_000_000L
                height >= 480 -> 12_000_000L
                else -> 6_000_000L
            }
        }
    }
}

package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Handles direct media stream URLs (e.g. .mp4, .mp3, .webm, .mkv, .mov)
 * Uses HTTP HEAD to inspect Content-Length and MIME type dynamically.
 */
class DirectStreamExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Extractor {

    override val name: String = "DirectStreamExtractor"

    override fun canHandle(url: String): Boolean {
        val clean = url.lowercase().split("?").first()
        return clean.endsWith(".mp4") ||
                clean.endsWith(".m4v") ||
                clean.endsWith(".webm") ||
                clean.endsWith(".mp3") ||
                clean.endsWith(".mkv") ||
                clean.endsWith(".mov")
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            var contentLength = -1L
            var contentType = "video/mp4"

            try {
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use { response ->
                    contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    contentType = response.header("Content-Type") ?: "video/mp4"
                }
            } catch (_: Exception) {
                // If HEAD fails, fallback gracefully
            }

            val fileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "Media Stream" }
            val isAudio = contentType.startsWith("audio/") || fileName.endsWith(".mp3", ignoreCase = true)
            val ext = if (isAudio) "mp3" else "mp4"

            val format = VideoFormat(
                id = "direct_original",
                qualityLabel = if (isAudio) "Original Audio MP3" else "Direct Video Stream (${ext.uppercase()})",
                fileExtension = ext,
                mimeType = if (isAudio) "audio/mpeg" else "video/mp4",
                estimatedBytes = if (contentLength > 0) contentLength else 15_000_000L,
                downloadUrl = url,
                isAudioOnly = isAudio
            )

            val metadata = VideoMetadata(
                id = UUID.randomUUID().toString().take(8),
                originalUrl = url,
                title = fileName.replace(".", " ").capitalizeWords(),
                author = "Direct Web Source",
                durationSeconds = 0,
                thumbnailUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80",
                platform = Platform.GENERIC,
                formats = listOf(format)
            )

            Result.success(metadata)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}


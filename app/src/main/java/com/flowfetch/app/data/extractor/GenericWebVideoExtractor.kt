package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Universal HTML5 / OpenGraph Video Extractor for any website.
 * Scrapes og:video, twitter:player, HTML5 <video> / <source>, JSON-LD,
 * and embedded .m3u8/.mp4 stream URLs when pasted directly in the main Downloader tab.
 */
class GenericWebVideoExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Extractor {

    override val name: String = "GenericWebVideoExtractor"

    override fun canHandle(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", url)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${resp.code} fetching webpage"))
            }

            val html = resp.body?.string() ?: ""
            if (html.isBlank()) {
                return@withContext Result.failure(Exception("Empty webpage response"))
            }

            // 1. Extract Page Title
            val titleRegex = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val titleMatcher = titleRegex.matcher(html)
            var title = if (titleMatcher.find()) titleMatcher.group(1)?.trim().orEmpty() else ""

            if (title.isBlank()) {
                val ogTitleRegex = Pattern.compile("<meta[^>]+(?:property|name)=[\"'](?:og:title|twitter:title)[\"'][^>]+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
                val ogMatcher = ogTitleRegex.matcher(html)
                if (ogMatcher.find()) {
                    title = ogMatcher.group(1)?.trim().orEmpty()
                }
            }
            if (title.isBlank()) {
                title = "Web Video"
            }
            // Decode common HTML entities
            title = title.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

            // 2. Extract Thumbnail
            var thumb = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
            val ogImgRegex = Pattern.compile("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
            val imgMatcher = ogImgRegex.matcher(html)
            if (imgMatcher.find()) {
                val foundImg = imgMatcher.group(1)?.trim().orEmpty()
                if (foundImg.startsWith("http")) thumb = foundImg
            }

            // 3. Scan for direct video streams (OpenGraph, HTML5 video/source, and embedded media URLs)
            val detectedUrls = mutableSetOf<String>()

            // A. OpenGraph & Twitter player meta tags
            val ogVideoRegex = Pattern.compile("<meta[^>]+(?:property|name)=[\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\"'][^>]+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
            val vMatcher = ogVideoRegex.matcher(html)
            while (vMatcher.find()) {
                val src = vMatcher.group(1)?.trim().orEmpty()
                if (isValidVideoStreamUrl(src)) {
                    detectedUrls.add(resolveUrl(url, src))
                }
            }

            // B. HTML5 <video> and <source> tags
            val html5Regex = Pattern.compile("<(?:video|source)[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val hMatcher = html5Regex.matcher(html)
            while (hMatcher.find()) {
                val src = hMatcher.group(1)?.trim().orEmpty()
                if (isValidVideoStreamUrl(src)) {
                    detectedUrls.add(resolveUrl(url, src))
                }
            }

            // C. Embedded JSON-LD / JS stream urls (.mp4 or .m3u8)
            val streamRegex = Pattern.compile("https?://[^\"'<>\t\r\n\\s]+\\.(?:mp4|m3u8)(?:\\?[^\"'<>\t\r\n\\s]*)?", Pattern.CASE_INSENSITIVE)
            val sMatcher = streamRegex.matcher(html)
            while (sMatcher.find()) {
                val src = sMatcher.group(0)?.trim().orEmpty()
                if (isValidVideoStreamUrl(src)) {
                    detectedUrls.add(src)
                }
            }

            if (detectedUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No public video streams found on this webpage. Use the Web Sniffer tab if login is required."))
            }

            val formats = mutableListOf<VideoFormat>()
            for (streamUrl in detectedUrls) {
                val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
                val ext = if (isHls) "m3u8" else "mp4"
                val mime = if (isHls) "application/x-mpegURL" else "video/mp4"
                val label = if (isHls) "HLS Adaptive Stream (Full HD)" else "High Definition Video (MP4)"

                formats.add(
                    VideoFormat(
                        id = "generic_" + UUID.randomUUID().toString().take(6),
                        qualityLabel = label,
                        fileExtension = ext,
                        mimeType = mime,
                        estimatedBytes = 35_000_000L,
                        downloadUrl = streamUrl,
                        resolution = "HD",
                        isAudioOnly = false
                    )
                )
            }

            // Add MP3 Audio Option
            formats.add(
                VideoFormat(
                    id = "generic_audio_mp3",
                    qualityLabel = "MP3 Audio (320 kbps)",
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg",
                    estimatedBytes = 6_000_000L,
                    downloadUrl = formats.first().downloadUrl,
                    resolution = "320 kbps",
                    isAudioOnly = true
                )
            )

            Result.success(
                VideoMetadata(
                    id = UUID.randomUUID().toString().take(8),
                    originalUrl = url,
                    title = title,
                    author = URI(url).host?.replace("www.", "") ?: "Web Source",
                    durationSeconds = 0,
                    thumbnailUrl = thumb,
                    platform = Platform.GENERIC,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isValidVideoStreamUrl(src: String): Boolean {
        val lower = src.lowercase()
        // Ignore previews, teasers, thumbs, banners, tracking ads
        if (lower.contains("preview") || lower.contains("teaser") || lower.contains("trailer") ||
            lower.contains("thumb") || lower.contains("hover") || lower.contains("video-previews") ||
            lower.contains("adsystem") || lower.contains("doubleclick") || lower.contains(".ts")
        ) {
            return false
        }
        return lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".webm") ||
                lower.contains(".mp4?") || lower.contains(".m3u8?")
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }
}

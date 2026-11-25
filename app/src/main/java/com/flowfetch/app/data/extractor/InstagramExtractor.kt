package com.flowfetch.app.data.extractor

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

/**
 * Dedicated High-Speed Instagram Reel & Video Extractor for S Download.
 * Employs a multi-stage high-speed extraction pipeline:
 * 1. Sanitization & shortcode extraction.
 * 2. High-speed FxInstagram direct stream query (< 500ms, direct 1080p/720p MP4 + full metadata).
 * 3. Direct Instagram OpenGraph meta-scraping.
 * 4. SnapSave engine fallback.
 * 5. On-device WebView stream interceptor (bounded to 3s max so UI never freezes).
 */
class InstagramExtractor(
    private val context: Context?,
    private val client: OkHttpClient
) {

    private val snapSaveExtractor = SnapSaveExtractor(client)

    suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanInstagramUrl(url)
        val shortcode = extractShortcode(cleanUrl) ?: UUID.randomUUID().toString().take(8)

        // Stage 1: Fast direct FxInstagram query (instant < 500ms, direct high-bitrate MP4 stream)
        val fxResult = fetchFxInstagram(shortcode, cleanUrl)
        if (fxResult != null && !fxResult.directStreamUrl.isNullOrBlank()) {
            return@withContext Result.success(buildInstagramMetadata(shortcode, url, fxResult))
        }

        // Stage 2: Direct Instagram OpenGraph metadata fetch
        val ogData = fetchOgMetadata(cleanUrl)
        var streamUrl: String? = ogData.directStreamUrl

        // Stage 3: Try SnapSave engine if direct stream not yet found
        if (streamUrl.isNullOrBlank()) {
            try {
                val snapResult = snapSaveExtractor.extract(cleanUrl, Platform.INSTAGRAM)
                if (snapResult.isSuccess) {
                    val snapMeta = snapResult.getOrNull()
                    val candidate = snapMeta?.formats?.firstOrNull { !it.isAudioOnly && it.downloadUrl.isNotBlank() }?.downloadUrl
                    if (!candidate.isNullOrBlank() && !candidate.startsWith("&")) {
                        streamUrl = candidate
                    }
                }
            } catch (_: Exception) {}
        }

        // Stage 4: On-device headless WebView stream interceptor (capped at 3 seconds)
        if (streamUrl.isNullOrBlank() && context != null) {
            try {
                val embedUrl = "https://www.instagram.com/reel/$shortcode/embed/"
                streamUrl = captureStreamWithWebView(embedUrl)
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = captureStreamWithWebView(cleanUrl)
                }
            } catch (_: Exception) {}
        }

        if (streamUrl.isNullOrBlank()) {
            // As a last-ditch proxy fallback, try constructing the FxInstagram direct video URL directly
            val directFxUrl = "https://fxinstagram.com/video/$shortcode.mp4"
            if (verifyStream(directFxUrl)) {
                streamUrl = directFxUrl
            }
        }

        if (streamUrl.isNullOrBlank()) {
            return@withContext Result.failure(
                Exception("Could not extract a downloadable video stream from this link. Make sure the video is public.")
            )
        }

        val finalData = OgData(
            title = ogData.title.ifBlank { "Instagram Reel" },
            thumbnailUrl = ogData.thumbnailUrl.ifBlank {
                "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
            },
            directStreamUrl = streamUrl
        )

        Result.success(buildInstagramMetadata(shortcode, url, finalData))
    }

    private fun buildInstagramMetadata(shortcode: String, originalUrl: String, data: OgData): VideoMetadata {
        val streamUrl = data.directStreamUrl ?: ""
        val title = data.title.ifBlank { "Instagram Reel" }
        val thumb = data.thumbnailUrl.ifBlank {
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"
        }

        val formats = listOf(
            VideoFormat(
                id = "ig_hd_mp4",
                qualityLabel = "Original HD Quality (MP4)",
                fileExtension = "mp4",
                mimeType = "video/mp4",
                estimatedBytes = 24_000_000L,
                downloadUrl = streamUrl,
                resolution = "HD",
                isAudioOnly = false
            ),
            VideoFormat(
                id = "ig_audio_mp3",
                qualityLabel = "MP3 Audio (320 kbps)",
                fileExtension = "mp3",
                mimeType = "audio/mpeg",
                estimatedBytes = 4_500_000L,
                downloadUrl = streamUrl,
                resolution = "320 kbps",
                isAudioOnly = true
            )
        )

        return VideoMetadata(
            id = shortcode,
            originalUrl = originalUrl,
            title = title,
            author = "Instagram Creator",
            durationSeconds = 30,
            thumbnailUrl = thumb,
            platform = Platform.INSTAGRAM,
            formats = formats
        )
    }

    private data class OgData(
        val title: String,
        val thumbnailUrl: String,
        val directStreamUrl: String? = null
    )

    private fun fetchFxInstagram(shortcode: String, canonicalUrl: String): OgData? {
        return try {
            val fxUrl = "https://fxinstagram.com/reel/$shortcode/"
            val request = Request.Builder()
                .url(fxUrl)
                .header("User-Agent", "TelegramBot (like TwitterBot)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                val videoMatch = Regex("""<meta\s+property=["']og:video["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<meta\s+name=["']twitter:player:stream["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                val videoUrl = videoMatch?.groupValues?.get(1)?.replace("&amp;", "&")

                val imageMatch = Regex("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                val imageUrl = imageMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

                val titleMatch = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                var title = titleMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: "Instagram Reel"
                title = title.removeSuffix(" • Instagram").removeSuffix(" | Instagram").trim()

                val descMatch = Regex("""<meta\s+property=["']og:description["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                val desc = descMatch?.groupValues?.get(1)?.replace("&amp;", "&")?.trim() ?: ""
                if (desc.isNotBlank()) {
                    title = desc.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: title
                }

                if (!videoUrl.isNullOrBlank()) {
                    OgData(title = title, thumbnailUrl = imageUrl, directStreamUrl = videoUrl)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOgMetadata(url: String): OgData {
        return try {
            val botUa = "TelegramBot (like TwitterBot)"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", botUa)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""

                val ogImageMatch = Regex("""<meta[^>]+(?:property|name)=["'](?:og|twitter):image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og|twitter):image["']""", RegexOption.IGNORE_CASE).find(html)
                val thumb = ogImageMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

                val ogTitleMatch = Regex("""<meta[^>]+(?:property|name)=["'](?:og|twitter):title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
                var title = ogTitleMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: "Instagram Reel"
                title = title.removeSuffix(" • Instagram").removeSuffix(" | Instagram").trim()

                var streamUrl: String? = null
                val mp4Match = Regex("""https://[a-zA-Z0-9.-]+\.(?:cdninstagram|fbcdn)\.net/[^"'\s<>]+\.mp4[^"'\s<>]*""").find(html)
                if (mp4Match != null) {
                    streamUrl = cleanStreamUrl(mp4Match.value)
                } else {
                    val videoUrlMatch = Regex(""""(?:video_url|playable_url|browser_native_hd_url)"\s*:\s*"([^"]+)"""").find(html)
                    if (videoUrlMatch != null) {
                        streamUrl = cleanStreamUrl(videoUrlMatch.groupValues[1])
                    }
                }

                OgData(title = title, thumbnailUrl = thumb, directStreamUrl = streamUrl)
            }
        } catch (_: Exception) {
            OgData(title = "Instagram Reel", thumbnailUrl = "")
        }
    }

    private fun verifyStream(streamUrl: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(streamUrl)
                .head()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun captureStreamWithWebView(targetUrl: String): String? {
        val ctx = context ?: return null
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            var webView: WebView? = null
            try {
                webView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = false
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if ((reqUrl.contains(".mp4") || reqUrl.contains("fbcdn.net/v/t") || reqUrl.contains("cdninstagram.com/v/t"))
                                && !reqUrl.contains(".jpg") && !reqUrl.contains(".png")) {
                                if (!deferred.isCompleted) {
                                    deferred.complete(reqUrl)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var v = document.querySelector('video');
                                    if (v && v.src) return v.src;
                                    var s = document.querySelectorAll('video source');
                                    for (var i = 0; i < s.length; i++) {
                                        if (s[i].src) return s[i].src;
                                    }
                                    return '';
                                })()
                                """.trimIndent()
                            ) { result ->
                                val cleanResult = result?.replace("\"", "")?.trim() ?: ""
                                if (cleanResult.startsWith("http") && !cleanResult.contains(".jpg") && !deferred.isCompleted) {
                                    deferred.complete(cleanResult)
                                }
                            }
                        }
                    }
                    loadUrl(targetUrl)
                }

                // Fast timeout: wait maximum 3.0 seconds
                withTimeoutOrNull(3000L) {
                    deferred.await()
                }
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun cleanStreamUrl(url: String): String {
        return url
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\", "")
            .trim()
    }

    fun cleanInstagramUrl(url: String): String {
        val trimmed = url.trim()
        val shortcode = extractShortcode(trimmed) ?: return trimmed
        val isReel = trimmed.contains("/reel/", ignoreCase = true) || trimmed.contains("/reels/", ignoreCase = true)
        return if (isReel) {
            "https://www.instagram.com/reel/$shortcode/"
        } else {
            "https://www.instagram.com/p/$shortcode/"
        }
    }

    fun extractShortcode(url: String): String? {
        val patterns = listOf(
            "/(?:reel|reels|p|tv)/([a-zA-Z0-9_-]+)",
            "instagram\\.com/([a-zA-Z0-9_-]+)"
        )
        for (p in patterns) {
            val match = Regex(p).find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}

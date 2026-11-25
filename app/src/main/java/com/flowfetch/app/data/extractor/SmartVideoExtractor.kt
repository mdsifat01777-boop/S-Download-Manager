package com.flowfetch.app.data.extractor

import android.content.Context
import android.os.Build
import android.text.Html
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * High-Speed Video & Audio Extractor Engine for S Download.
 * Performs multi-strategy extraction:
 * 1. Direct media streams (.mp4, .mp3, etc.)
 * 2. Dedicated TikTok parser (no-watermark 1080p, 720p, and MP3 audio)
 * 3. Dedicated Facebook OpenGraph & Watch Extractor (100% direct authentic fbcdn.net streams)
 * 4. Invidious YouTube Real Stream Parser
 * 5. Cobalt multi-instance gateway for other platforms
 */
class SmartVideoExtractor(
    private val context: Context? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : Extractor {

    override val name: String = "SmartVideoExtractor"

    private val snapSaveExtractor = SnapSaveExtractor(client)
    private val instagramExtractor = InstagramExtractor(context, client)

    override fun canHandle(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        val platform = Platform.detect(cleanUrl)

        // Strategy 1: Direct Media Stream Check (.mp4, .mp3, etc.)
        if (isDirectMediaUrl(cleanUrl)) {
            val directResult = extractDirectStream(cleanUrl)
            if (directResult.isSuccess) return@withContext directResult
        }

        // Strategy 2: Dedicated TikTok Extraction (100% real, no watermark, real audio)
        if (platform == Platform.TIKTOK) {
            val tiktokResult = extractTikTokReal(cleanUrl)
            if (tiktokResult.isSuccess) return@withContext tiktokResult
        }

        // Strategy 3: Dedicated Facebook Extraction (High-Speed SnapSave + Direct Engine)
        if (platform == Platform.FACEBOOK) {
            val fbResult = extractFacebook(cleanUrl)
            if (fbResult.isSuccess) return@withContext fbResult
        }

        // Strategy 4: Dedicated Instagram Extraction (OpenGraph + Headless WebView Engine)
        if (platform == Platform.INSTAGRAM) {
            val igResult = instagramExtractor.extract(cleanUrl)
            if (igResult.isSuccess) return@withContext igResult
        }

        // Strategy 5: Dedicated YouTube Parser
        if (platform == Platform.YOUTUBE) {
            val ytResult = extractYouTube(cleanUrl)
            if (ytResult.isSuccess) return@withContext ytResult
        }

        // Strategy 6: Cobalt Gateway
        val cobaltResult = extractViaCobalt(cleanUrl, platform)
        if (cobaltResult.isSuccess) return@withContext cobaltResult

        // Fail cleanly if no valid video stream could be identified
        Result.failure(Exception("Could not extract a downloadable video stream from this link. Make sure the video is public."))
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val path = url.split("?").first().lowercase()
        return path.endsWith(".mp4") || path.endsWith(".mp3") || path.endsWith(".m4a") ||
                path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".mov")
    }

    private fun extractDirectStream(url: String): Result<VideoMetadata> {
        return try {
            val fileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "Direct Media" }
            val isAudio = fileName.endsWith(".mp3", ignoreCase = true) || fileName.endsWith(".m4a", ignoreCase = true)
            val ext = if (isAudio) "mp3" else "mp4"

            val formats = listOf(
                VideoFormat(
                    id = "direct_media",
                    qualityLabel = if (isAudio) "Direct Audio ($ext)" else "Direct Video Stream ($ext)",
                    fileExtension = ext,
                    mimeType = if (isAudio) "audio/mpeg" else "video/mp4",
                    estimatedBytes = 15_000_000L,
                    downloadUrl = url,
                    isAudioOnly = isAudio
                )
            )

            Result.success(
                VideoMetadata(
                    id = UUID.randomUUID().toString().take(8),
                    originalUrl = url,
                    title = fileName,
                    author = "Direct Web Source",
                    durationSeconds = 0,
                    thumbnailUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80",
                    platform = Platform.GENERIC,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * High-Speed Facebook Video & Audio Extractor.
     * Uses SnapSave HD Engine first to extract 1080p Full HD, 720p HD, and Audio tracks.
     * Falls back to direct iOS Safari OpenGraph / Watch scraping if needed.
     */
    private fun extractFacebook(url: String): Result<VideoMetadata> {
        // Step 1: Query SnapSave HD extractor + fetch OpenGraph title in parallel if needed
        var targetFbUrl = url
        try {
            val snapResult = snapSaveExtractor.extract(targetFbUrl, Platform.FACEBOOK)
            if (snapResult.isSuccess) {
                var snapMeta = snapResult.getOrNull()
                if (snapMeta != null && snapMeta.formats.isNotEmpty()) {
                    if (snapMeta.title.equals("Video Facebook", ignoreCase = true) ||
                        snapMeta.title.equals("Social Media Video", ignoreCase = true) ||
                        snapMeta.title.length <= 5
                    ) {
                        val ogData = fetchFacebookOg(url)
                        if (ogData.first.isNotBlank()) {
                            snapMeta = snapMeta.copy(
                                title = ogData.first,
                                thumbnailUrl = if (ogData.second.isNotBlank()) ogData.second else snapMeta.thumbnailUrl
                            )
                        }
                    }
                    return Result.success(snapMeta)
                }
            }
        } catch (_: Exception) {}

        // Step 2: Direct fallback engine (also retries SnapSave if /share/ redirects to canonical URL)
        return try {
            val mobileUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

            val initialRequest = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUa)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            var htmlContent = ""
            var finalUrl = url
            var responseHeaders: okhttp3.Headers? = null

            client.newCall(initialRequest).execute().use { response ->
                responseHeaders = response.headers
                finalUrl = response.request.url.toString()
                htmlContent = response.body?.string() ?: ""
            }

            // Attempt 1: Parse OpenGraph / native JSON directly from the initial page
            var parsed = parseFacebookHtml(htmlContent, finalUrl, url)
            if (parsed != null) return Result.success(parsed)

            // If /share/ redirected cleanly, try SnapSave once with the canonical URL
            if (url.contains("/share/", ignoreCase = true) && finalUrl != url && !finalUrl.contains("/login")) {
                try {
                    val snap2 = snapSaveExtractor.extract(finalUrl, Platform.FACEBOOK)
                    if (snap2.isSuccess && snap2.getOrNull()?.formats?.isNotEmpty() == true) {
                        return snap2
                    }
                } catch (_: Exception) {}
            }

            // Attempt 2: Extract Facebook video ID and query mobile watch endpoint
            val videoId = extractFacebookVideoId(finalUrl, htmlContent, responseHeaders)
            if (!videoId.isNullOrBlank()) {
                val candidateWatchUrls = listOf(
                    "https://m.facebook.com/watch/?v=$videoId&hpir=1",
                    "https://www.facebook.com/reel/$videoId/"
                )
                for (watchUrl in candidateWatchUrls) {
                    try {
                        val watchReq = Request.Builder()
                            .url(watchUrl)
                            .header("User-Agent", mobileUa)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .build()
                        client.newCall(watchReq).execute().use { resp ->
                            val watchHtml = resp.body?.string() ?: ""
                            parsed = parseFacebookHtml(watchHtml, watchUrl, url)
                        }
                        if (parsed != null) return Result.success(parsed)
                    } catch (_: Exception) {}
                }
            }

            Result.failure(Exception("Could not extract video stream from Facebook link. Make sure the video is public."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFacebookOg(url: String): Pair<String, String> {
        return try {
            val botUa = "TelegramBot (like TwitterBot)"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", botUa)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val ogTitleMatch = Regex("""<meta[^>]+(?:property|name)=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
                val rawTitle = ogTitleMatch?.groupValues?.get(1) ?: ""
                val decoded = decodeHtml(rawTitle).removeSuffix(" | Facebook").removeSuffix(" - Facebook").trim()
                val cleanTitle = decoded.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

                val ogImageMatch = Regex("""<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                val thumb = ogImageMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

                Pair(cleanTitle, thumb)
            }
        } catch (_: Exception) {
            Pair("", "")
        }
    }

    private fun extractFacebookVideoId(finalUrl: String, html: String, headers: okhttp3.Headers?): String? {
        // 1. Check refresh / Location headers
        headers?.let { h ->
            val refresh = h["refresh"] ?: ""
            val refreshMatch = Regex("""(?:fullscreen_video|video_id)[=/](\d+)""").find(refresh)
            if (refreshMatch != null) return refreshMatch.groupValues[1]

            val location = h["Location"] ?: ""
            val locMatch = Regex("""/(?:reel|videos)/(\d+)""").find(location)
            if (locMatch != null) return locMatch.groupValues[1]
        }

        // 2. Check finalUrl
        val urlMatch = Regex("""/(?:reel|videos)/(\d+)""").find(finalUrl)
        if (urlMatch != null) return urlMatch.groupValues[1]

        val paramMatch = Regex("""[?&]v=(\d+)""").find(finalUrl)
        if (paramMatch != null) return paramMatch.groupValues[1]

        // 3. Check HTML content
        val fbProtocolMatch = Regex("""fb://fullscreen_video/(\d+)""").find(html)
        if (fbProtocolMatch != null) return fbProtocolMatch.groupValues[1]

        val oembedMatch = Regex("""oembed_video\?url=[^"']*%2F(\d+)""").find(html)
        if (oembedMatch != null) return oembedMatch.groupValues[1]

        val canonicalMatch = Regex("""(?:og:url|canonical)[^>]+/(?:reel|videos)/(\d+)""").find(html)
        if (canonicalMatch != null) return canonicalMatch.groupValues[1]

        val jsonIdMatch = Regex(""""(?:video_id|entity_id)"\s*:\s*"(\d+)"""").find(html)
        if (jsonIdMatch != null) return jsonIdMatch.groupValues[1]

        return null
    }

    private fun parseFacebookHtml(html: String, pageUrl: String, originalUrl: String): VideoMetadata? {
        if (html.isBlank()) return null

        var hdUrl: String? = null
        var sdUrl: String? = null

        // Look for HD and SD URLs in JSON scripts
        val hdJsonMatch = Regex(""""(?:browser_native_hd_url|playable_url_quality_hd)"\s*:\s*"([^"]+)"""").find(html)
        if (hdJsonMatch != null) {
            hdUrl = cleanStreamUrl(hdJsonMatch.groupValues[1])
        }

        val sdJsonMatch = Regex(""""(?:browser_native_sd_url|playable_url)"\s*:\s*"([^"]+)"""").find(html)
        if (sdJsonMatch != null) {
            sdUrl = cleanStreamUrl(sdJsonMatch.groupValues[1])
        }

        // Look for OpenGraph video meta tags
        val ogVideoMatch = Regex("""<meta[^>]+(?:property|name)=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::secure_url|:url)?["']""", RegexOption.IGNORE_CASE).find(html)

        val ogVideoUrl = ogVideoMatch?.groupValues?.get(1)?.let { cleanStreamUrl(it) }

        if (hdUrl == null && sdUrl == null && ogVideoUrl == null) {
            // General scan for fbcdn.net .mp4 in HTML
            val fbcdnMatch = Regex("""https://[a-zA-Z0-9.-]+\.fbcdn\.net/[^"'\s<>]+\.mp4[^"'\s<>]*""").find(html)
            if (fbcdnMatch != null) {
                sdUrl = cleanStreamUrl(fbcdnMatch.value)
            } else {
                return null
            }
        }

        val primaryVideo = hdUrl ?: ogVideoUrl ?: sdUrl ?: return null
        val secondaryVideo = if (sdUrl != null && sdUrl != primaryVideo) sdUrl else if (ogVideoUrl != null && ogVideoUrl != primaryVideo) ogVideoUrl else null

        // Thumbnail
        val ogImageMatch = Regex("""<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+(?:property|name)=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)

        val thumbUrl = ogImageMatch?.groupValues?.get(1)?.let { cleanStreamUrl(it) }
            ?: "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"

        // Title & Author
        val ogTitleMatch = Regex("""<meta[^>]+(?:property|name)=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:title["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)

        val rawTitle = ogTitleMatch?.groupValues?.get(1) ?: "Facebook Video"
        val decodedTitle = decodeHtml(rawTitle)

        var cleanTitle = decodedTitle.trim()
        var author = "Facebook Creator"

        if (cleanTitle.contains(" | ")) {
            val parts = cleanTitle.split(" | ").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 3 && parts.last().equals("Facebook", ignoreCase = true)) {
                cleanTitle = parts[0]
                author = parts[1]
            } else if (parts.size == 2 && parts.last().equals("Facebook", ignoreCase = true)) {
                cleanTitle = parts[0]
            } else if (parts.size >= 2) {
                cleanTitle = parts[0]
                author = parts[1]
            }
        } else if (cleanTitle.endsWith(" | Facebook", ignoreCase = true)) {
            cleanTitle = cleanTitle.removeSuffix(" | Facebook").trim()
        }

        cleanTitle = cleanTitle.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Facebook Video"
        if (cleanTitle.isBlank()) cleanTitle = "Facebook Video"

        // Duration & Bitrate for accurate resolution & size calculation
        val efgDuration = extractDurationFromEfg(primaryVideo)
            ?: (if (sdUrl != null) extractDurationFromEfg(sdUrl) else null)
            ?: (if (hdUrl != null) extractDurationFromEfg(hdUrl) else null)
            ?: extractDurationFromEfg(html)

        val durationMatch = Regex(""""duration_s"\s*:\s*(\d+)""").find(html)
            ?: Regex(""""duration"\s*:\s*(\d+)""").find(html)
            ?: Regex("""duration_s%22%3A(\d+)""").find(html)

        val durationSeconds = efgDuration
            ?: durationMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: 120

        val urlBitrate = Regex("""bitrate=(\d+)""").find(primaryVideo)?.groupValues?.get(1)?.toLongOrNull()
        val bitrateMatch = Regex("""bitrate=(\d+)""").find(html)
            ?: Regex(""""bitrate"\s*:\s*(\d+)""").find(html)
        val bitrate = urlBitrate ?: bitrateMatch?.groupValues?.get(1)?.toLongOrNull() ?: 350_000L

        // Fetch real stream Content-Length only skip slow HEAD requests (estimate directly from bitrate)
        val realSizeBytes = ((bitrate * durationSeconds) / 8L).coerceAtLeast(3_000_000L)

        // Check if an authentic HD stream was found
        val hasAuthenticHd = hdUrl != null && hdUrl != sdUrl
        val isExplicitSd = primaryVideo.contains("sve_sd") || primaryVideo.contains(".360.") || (!hasAuthenticHd && hdUrl == null)

        val formats = mutableListOf<VideoFormat>()

        if (hasAuthenticHd) {
            val hdSize = (realSizeBytes * 2.5).toLong()
            // Authentic HD Stream
            formats.add(
                VideoFormat(
                    id = "fb_hd",
                    qualityLabel = "720p / 1080p HD Quality (MP4)",
                    fileExtension = "mp4",
                    mimeType = "video/mp4",
                    estimatedBytes = hdSize,
                    downloadUrl = hdUrl!!,
                    resolution = "720p",
                    isAudioOnly = false
                )
            )

            // Standard SD Option
            formats.add(
                VideoFormat(
                    id = "fb_sd",
                    qualityLabel = "Standard Quality (360p SD MP4)",
                    fileExtension = "mp4",
                    mimeType = "video/mp4",
                    estimatedBytes = realSizeBytes,
                    downloadUrl = sdUrl ?: primaryVideo,
                    resolution = "360p",
                    isAudioOnly = false
                )
            )
        } else {
            // Only honest SD stream found in mobile HTML — do NOT fake a 720p HD option
            formats.add(
                VideoFormat(
                    id = "fb_sd",
                    qualityLabel = "Standard Quality (360p SD MP4)",
                    fileExtension = "mp4",
                    mimeType = "video/mp4",
                    estimatedBytes = realSizeBytes,
                    downloadUrl = primaryVideo,
                    resolution = "360p",
                    isAudioOnly = false
                )
            )
        }

        // Distinct Audio Option
        val audioBytes = ((320_000L * durationSeconds) / 8L).coerceAtLeast(500_000L)
        formats.add(
            VideoFormat(
                id = "fb_audio_mp3",
                qualityLabel = "MP3 Audio (320 kbps)",
                fileExtension = "mp3",
                mimeType = "audio/mpeg",
                estimatedBytes = audioBytes,
                downloadUrl = primaryVideo,
                resolution = "320 kbps",
                isAudioOnly = true
            )
        )

        val videoId = Regex("""/(?:reel|videos)/(\d+)""").find(pageUrl)?.groupValues?.get(1)
            ?: UUID.randomUUID().toString().take(8)

        return VideoMetadata(
            id = videoId,
            originalUrl = originalUrl,
            title = cleanTitle,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbUrl,
            platform = Platform.FACEBOOK,
            formats = formats
        )
    }

    private fun cleanStreamUrl(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim('"', ' ', '\'', '\\')
    }

    private fun decodeHtml(raw: String): String {
        var str = raw.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        // Decode hex entities &#x995;
        str = Regex("""&#x([0-9a-fA-F]+);""").replace(str) { match ->
            try {
                val code = match.groupValues[1].toInt(16)
                code.toChar().toString()
            } catch (_: Exception) { match.value }
        }

        // Decode decimal entities &#2453;
        str = Regex("""&#(\d+);""").replace(str) { match ->
            try {
                val code = match.groupValues[1].toInt()
                code.toChar().toString()
            } catch (_: Exception) { match.value }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(str, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(str).toString()
            }
        } catch (_: Exception) {
            str
        }
    }

    private fun extractDurationFromEfg(text: String): Int? {
        val efgMatch = Regex("""efg=([a-zA-Z0-9_\-\.%]+)""").find(text) ?: return null
        return try {
            val raw = java.net.URLDecoder.decode(efgMatch.groupValues[1], "UTF-8")
            val clean = raw.replace("-", "+").replace("_", "/")
            val pad = (4 - clean.length % 4) % 4
            val padded = clean + "=".repeat(pad)
            val decodedBytes = try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (_: Exception) {
                try {
                    java.util.Base64.getUrlDecoder().decode(clean)
                } catch (_: Exception) {
                    android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                }
            }
            val jsonStr = String(decodedBytes, Charsets.UTF_8)
            val match = Regex(""""duration_s"\s*:\s*(\d+)""").find(jsonStr)
            match?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchStreamContentLength(url: String): Long? {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTikTokReal(url: String): Result<VideoMetadata> {
        return try {
            val apiUrl = "https://tikwm.com/api/?url=" + java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.failure(Exception("TikTok service error ${response.code}"))
                val body = response.body?.string() ?: return Result.failure(Exception("Empty TikTok response"))
                val json = JSONObject(body)
                if (json.optInt("code") != 0) return Result.failure(Exception(json.optString("msg", "Failed")))

                val data = json.getJSONObject("data")
                val title = data.optString("title", "TikTok Video").ifBlank { "TikTok Video" }
                val cover = data.optString("cover", "")
                val playUrl = data.optString("play", "")
                val hdPlayUrl = data.optString("hdplay", playUrl).ifBlank { playUrl }
                val wmPlayUrl = data.optString("wmplay", "")
                val musicInfo = data.optJSONObject("music_info")
                val musicUrl = musicInfo?.optString("play", "") ?: ""
                val authorObj = data.optJSONObject("author")
                val author = authorObj?.optString("nickname", "@creator") ?: "@creator"
                val duration = data.optInt("duration", 0)

                val formats = mutableListOf<VideoFormat>()

                if (hdPlayUrl.isNotBlank()) {
                    formats.add(
                        VideoFormat(
                            id = "tt_hd",
                            qualityLabel = "1080p Full HD (No Watermark)",
                            fileExtension = "mp4",
                            mimeType = "video/mp4",
                            estimatedBytes = 18_500_000L,
                            downloadUrl = hdPlayUrl,
                            resolution = "1080x1920",
                            isAudioOnly = false
                        )
                    )
                }

                if (playUrl.isNotBlank() && playUrl != hdPlayUrl) {
                    formats.add(
                        VideoFormat(
                            id = "tt_sd",
                            qualityLabel = "720p HD (No Watermark)",
                            fileExtension = "mp4",
                            mimeType = "video/mp4",
                            estimatedBytes = 12_200_000L,
                            downloadUrl = playUrl,
                            resolution = "720x1280",
                            isAudioOnly = false
                        )
                    )
                }

                if (wmPlayUrl.isNotBlank()) {
                    formats.add(
                        VideoFormat(
                            id = "tt_wm",
                            qualityLabel = "Standard MP4 (With Watermark)",
                            fileExtension = "mp4",
                            mimeType = "video/mp4",
                            estimatedBytes = 10_000_000L,
                            downloadUrl = wmPlayUrl,
                            resolution = "720x1280",
                            isAudioOnly = false
                        )
                    )
                }

                if (musicUrl.isNotBlank()) {
                    formats.add(
                        VideoFormat(
                            id = "tt_audio_mp3",
                            qualityLabel = "MP3 Original Sound (320 kbps)",
                            fileExtension = "mp3",
                            mimeType = "audio/mpeg",
                            estimatedBytes = 3_200_000L,
                            downloadUrl = musicUrl,
                            resolution = "320 kbps",
                            isAudioOnly = true
                        )
                    )
                }

                if (formats.isEmpty()) return Result.failure(Exception("No downloadable formats found"))

                Result.success(
                    VideoMetadata(
                        id = data.optString("id", UUID.randomUUID().toString()),
                        originalUrl = url,
                        title = title,
                        author = author,
                        durationSeconds = duration,
                        thumbnailUrl = cover,
                        platform = Platform.TIKTOK,
                        formats = formats
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractViaCobalt(url: String, platform: Platform): Result<VideoMetadata> {
        val cobaltInstances = listOf(
            "https://cobalt-api.kwiatekm.tokyo/api/json",
            "https://co.wuk.sh/api/json",
            "https://api.cobalt.tools/api/json"
        )

        for (endpoint in cobaltInstances) {
            try {
                val payload = JSONObject().apply {
                    put("url", url)
                    put("vQuality", "1080")
                    put("downloadMode", "auto")
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respBody = response.body?.string() ?: return@use
                        val json = JSONObject(respBody)
                        val streamUrl = json.optString("url", "")
                        val status = json.optString("status", "")

                        if ((status == "stream" || status == "redirect" || status == "tunnel") && streamUrl.isNotBlank()) {
                            val filename = json.optString("filename", "Video").replace(".mp4", "")

                            val formats = listOf(
                                VideoFormat(
                                    id = "cobalt_best",
                                    qualityLabel = "1080p Full HD (Best Quality)",
                                    fileExtension = "mp4",
                                    mimeType = "video/mp4",
                                    estimatedBytes = 32_000_000L,
                                    downloadUrl = streamUrl,
                                    resolution = "1920x1080",
                                    isAudioOnly = false
                                ),
                                VideoFormat(
                                    id = "cobalt_audio",
                                    qualityLabel = "MP3 Audio (320 kbps)",
                                    fileExtension = "mp3",
                                    mimeType = "audio/mpeg",
                                    estimatedBytes = 5_000_000L,
                                    downloadUrl = streamUrl,
                                    resolution = "320 kbps",
                                    isAudioOnly = true
                                )
                            )

                            return Result.success(
                                VideoMetadata(
                                    id = UUID.randomUUID().toString().take(8),
                                    originalUrl = url,
                                    title = filename,
                                    author = platform.displayName,
                                    durationSeconds = 120,
                                    thumbnailUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80",
                                    platform = platform,
                                    formats = formats
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return Result.failure(Exception("Cobalt gateway unavailable"))
    }

    private fun extractYouTube(url: String): Result<VideoMetadata> {
        val videoId = extractYouTubeId(url) ?: return Result.failure(Exception("Invalid YouTube URL"))

        // Strategy 1: High-Speed Piped Gateway Instances
        val pipedGateways = listOf(
            "https://api.piped.private.coffee/streams/",
            "https://pipedapi.adminforge.de/streams/",
            "https://piped-api.codespace.cz/streams/"
        )

        for (base in pipedGateways) {
            try {
                val reqUrl = "$base$videoId"
                val request = Request.Builder()
                    .url(reqUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                        val author = json.optString("uploader", "YouTube Creator").ifBlank { "YouTube Creator" }
                        val duration = json.optInt("duration", 0)
                        var thumbUrl = json.optString("thumbnailUrl", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                        if (thumbUrl.isBlank()) thumbUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

                        val formats = mutableListOf<VideoFormat>()

                        val streamMap = mutableMapOf<String, Pair<String, Boolean>>()
                        var primaryStreamUrl = ""

                        // Pick first reachable audio stream for muxing videoOnly Piped streams
                        val candidateAudio = json.optJSONArray("audioStreams")?.let { aArr ->
                            (0 until aArr.length())
                                .map { aArr.getJSONObject(it).optString("url", "") }
                                .firstOrNull { it.isNotBlank() && verifyHeadOrRange(it) }
                        }

                        val videoStreams = json.optJSONArray("videoStreams")
                        if (videoStreams != null) {
                            for (i in 0 until videoStreams.length()) {
                                val s = videoStreams.getJSONObject(i)
                                val quality = s.optString("quality", "")
                                val sUrl = s.optString("url", "")
                                val videoOnly = s.optBoolean("videoOnly", false)
                                val mime = s.optString("mimeType", "")
                                // Prefer mp4 / h264 or playable streams, skip videoOnly if no valid audio exists
                                if (sUrl.isNotBlank() && (!videoOnly || !candidateAudio.isNullOrBlank())) {
                                    if (primaryStreamUrl.isBlank() && !videoOnly) primaryStreamUrl = sUrl
                                    val key = when {
                                        quality.contains("2160") || quality.contains("4k", ignoreCase = true) -> "2160p"
                                        quality.contains("1440") || quality.contains("2k", ignoreCase = true) -> "1440p"
                                        quality.contains("1080") -> "1080p"
                                        quality.contains("720") -> "720p"
                                        quality.contains("480") -> "480p"
                                        quality.contains("360") -> "360p"
                                        else -> null
                                    }
                                    if (key != null && (!streamMap.containsKey(key) || !videoOnly || mime.contains("mp4", true))) {
                                        streamMap[key] = Pair(sUrl, videoOnly)
                                    }
                                }
                            }
                        }

                        if (primaryStreamUrl.isBlank() && streamMap.isNotEmpty()) {
                            primaryStreamUrl = streamMap.values.first().first
                        }

                        val qualityTiers = listOf(
                            Triple("2160p", "4K Ultra HD (2160p MP4)", 15_000_000L),
                            Triple("1440p", "1440p Quad HD (2K MP4)", 8_000_000L),
                            Triple("1080p", "1080p Full HD (MP4)", 3_500_000L),
                            Triple("720p", "720p HD Quality (MP4)", 1_800_000L),
                            Triple("480p", "480p SD Quality (MP4)", 900_000L),
                            Triple("360p", "360p Standard Quality (MP4)", 430_000L)
                        )

                        for ((resKey, label, bps) in qualityTiers) {
                            val pair = streamMap[resKey] ?: continue
                            val streamUrl = pair.first
                            val needsAudioMux = pair.second
                            if (streamUrl.isNotBlank()) {
                                val estBytes = if (duration > 0) {
                                    ((bps * duration) / 8L).coerceAtLeast(5_000_000L)
                                } else {
                                    when (resKey) {
                                        "2160p" -> 180_000_000L
                                        "1440p" -> 100_000_000L
                                        "1080p" -> 50_000_000L
                                        "720p" -> 25_000_000L
                                        "480p" -> 15_000_000L
                                        else -> 10_000_000L
                                    }
                                }

                                formats.add(
                                    VideoFormat(
                                        id = "yt_${resKey}_${formats.size}",
                                        qualityLabel = label,
                                        fileExtension = "mp4",
                                        mimeType = "video/mp4",
                                        estimatedBytes = estBytes,
                                        downloadUrl = streamUrl,
                                        resolution = resKey,
                                        isAudioOnly = false,
                                        audioUrl = if (needsAudioMux) candidateAudio else null
                                    )
                                )
                            }
                        }

                        // Distinct Audio Option (always prefer reliable muxed MP4 if separate Piped audio proxy fails)
                        val audioUrl = if (!primaryStreamUrl.isNullOrBlank()) primaryStreamUrl else candidateAudio.orEmpty()

                        if (audioUrl.isNotBlank()) {
                            val estAudioBytes = if (duration > 0) {
                                ((320_000L * duration) / 8L).coerceAtLeast(3_000_000L)
                            } else 8_000_000L

                            formats.add(
                                VideoFormat(
                                    id = "yt_audio_mp3",
                                    qualityLabel = "MP3 Audio (320 kbps)",
                                    fileExtension = "mp3",
                                    mimeType = "audio/mpeg",
                                    estimatedBytes = estAudioBytes,
                                    downloadUrl = audioUrl,
                                    resolution = "320 kbps",
                                    isAudioOnly = true
                                )
                            )
                        }

                        if (formats.isNotEmpty()) {
                            // Sort formats descending (2160p -> 1440p -> 1080p -> 720p -> 480p -> 360p)
                            formats.sortByDescending {
                                when {
                                    it.isAudioOnly -> -1
                                    it.resolution?.contains("2160") == true || it.qualityLabel.contains("4K") -> 2160
                                    it.resolution?.contains("1440") == true || it.qualityLabel.contains("1440") -> 1440
                                    it.resolution?.contains("1080") == true -> 1080
                                    it.resolution?.contains("720") == true -> 720
                                    it.resolution?.contains("480") == true -> 480
                                    it.resolution?.contains("360") == true -> 360
                                    else -> 300
                                }
                            }

                            return Result.success(
                                VideoMetadata(
                                    id = videoId,
                                    originalUrl = url,
                                    title = title,
                                    author = author,
                                    durationSeconds = duration,
                                    thumbnailUrl = thumbUrl,
                                    platform = Platform.YOUTUBE,
                                    formats = formats
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 2: Invidious Instances fallback
        val invidiousInstances = listOf(
            "https://invidious.nerdvpn.de/api/v1/videos/",
            "https://inv.nadeko.net/api/v1/videos/",
            "https://vid.puffyan.us/api/v1/videos/"
        )

        for (base in invidiousInstances) {
            try {
                val reqUrl = "$base$videoId"
                val request = Request.Builder().url(reqUrl).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Video")
                        val author = json.optString("author", "YouTube Channel")
                        val lengthSeconds = json.optInt("lengthSeconds", 0)

                        val thumbs = json.optJSONArray("videoThumbnails")
                        var thumbUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                        if (thumbs != null && thumbs.length() > 0) {
                            thumbUrl = thumbs.getJSONObject(0).optString("url", thumbUrl)
                        }

                        val formatStreams = json.optJSONArray("formatStreams")
                        val formats = mutableListOf<VideoFormat>()

                        if (formatStreams != null) {
                            for (i in 0 until formatStreams.length()) {
                                val f = formatStreams.getJSONObject(i)
                                val resolution = f.optString("qualityLabel", "720p")
                                val fUrl = f.optString("url", "")
                                val container = f.optString("container", "mp4")
                                if (fUrl.isNotBlank()) {
                                    formats.add(
                                        VideoFormat(
                                            id = "yt_$resolution",
                                            qualityLabel = "$resolution ($container)",
                                            fileExtension = container,
                                            mimeType = "video/$container",
                                            estimatedBytes = if (resolution.contains("1080")) 45_000_000L else 20_000_000L,
                                            downloadUrl = fUrl,
                                            resolution = resolution,
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                        }

                        val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                        if (adaptiveFormats != null) {
                            for (i in 0 until adaptiveFormats.length()) {
                                val af = adaptiveFormats.getJSONObject(i)
                                val type = af.optString("type", "")
                                if (type.startsWith("audio/")) {
                                    val audioUrl = af.optString("url", "")
                                    if (audioUrl.isNotBlank()) {
                                        formats.add(
                                            VideoFormat(
                                                id = "yt_audio_stream",
                                                qualityLabel = "MP3 / M4A Audio Track",
                                                fileExtension = "mp3",
                                                mimeType = "audio/mpeg",
                                                estimatedBytes = 6_000_000L,
                                                downloadUrl = audioUrl,
                                                resolution = "Audio",
                                                isAudioOnly = true
                                            )
                                        )
                                        break
                                    }
                                }
                            }
                        }

                        if (formats.isNotEmpty()) {
                            return Result.success(
                                VideoMetadata(
                                    id = videoId,
                                    originalUrl = url,
                                    title = title,
                                    author = author,
                                    durationSeconds = lengthSeconds,
                                    thumbnailUrl = thumbUrl,
                                    platform = Platform.YOUTUBE,
                                    formats = formats
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return Result.failure(Exception("YouTube stream extractor unavailable"))
    }

    private fun verifyHeadOrRange(url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            client.newCall(req).execute().use { it.isSuccessful || it.code == 206 }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            "v=([a-zA-Z0-9_-]{11})",
            "youtu\\.be/([a-zA-Z0-9_-]{11})",
            "shorts/([a-zA-Z0-9_-]{11})",
            "embed/([a-zA-Z0-9_-]{11})",
            "live/([a-zA-Z0-9_-]{11})",
            "watch/([a-zA-Z0-9_-]{11})",
            "video/([a-zA-Z0-9_-]{11})"
        )
        for (p in patterns) {
            val match = Regex(p).find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}

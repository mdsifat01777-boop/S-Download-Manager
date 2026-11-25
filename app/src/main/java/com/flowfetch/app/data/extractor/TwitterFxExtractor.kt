package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * Dedicated high-speed Twitter/X Video Extractor.
 * Bypasses login and guest-token walls using FxTwitter syndication API.
 */
class TwitterFxExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val statusId = extractStatusId(url)
                ?: return@withContext Result.failure(Exception("Invalid Twitter/X status URL"))
            val screenName = extractScreenName(url) ?: "i"

            val apiUrl = "https://api.fxtwitter.com/$screenName/status/$statusId"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "SDownload/2.0 (Android; +https://github.com)")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext tryVxTwitter(url, screenName, statusId)
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty Twitter API response"))
            val json = JSONObject(body)
            if (json.optInt("code") != 200) {
                return@withContext tryVxTwitter(url, screenName, statusId)
            }

            val tweet = json.getJSONObject("tweet")
            val rawText = tweet.optString("text", "X / Twitter Video").trim()
            val title = rawText.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "X / Twitter Video"
            val authorObj = tweet.optJSONObject("author")
            val author = authorObj?.optString("name", authorObj.optString("screen_name", "X Creator")) ?: "X Creator"

            val mediaObj = tweet.optJSONObject("media")
            val videosArray = mediaObj?.optJSONArray("videos")
            if (videosArray == null || videosArray.length() == 0) {
                return@withContext tryVxTwitter(url, screenName, statusId)
            }

            val firstVideo = videosArray.getJSONObject(0)
            val thumbUrl = firstVideo.optString("thumbnail_url", "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80")
            val durationDouble = firstVideo.optDouble("duration", 0.0)
            val durationSeconds = durationDouble.toInt()

            val formats = mutableListOf<VideoFormat>()
            val variants = firstVideo.optJSONArray("variants")

            if (variants != null && variants.length() > 0) {
                // Sort variants by bitrate descending
                val variantList = mutableListOf<JSONObject>()
                for (i in 0 until variants.length()) {
                    val v = variants.getJSONObject(i)
                    val cType = v.optString("content_type", "")
                    if (cType.contains("mp4", ignoreCase = true) || v.optString("url").contains(".mp4")) {
                        variantList.add(v)
                    }
                }
                variantList.sortByDescending { it.optInt("bitrate", 0) }

                for (v in variantList) {
                    val streamUrl = v.optString("url", "")
                    val bitrate = v.optInt("bitrate", 0)
                    if (streamUrl.isNotBlank()) {
                        val (label, res) = when {
                            bitrate >= 2_000_000 -> Pair("1080p Full HD (MP4)", "1080p")
                            bitrate >= 800_000 -> Pair("720p HD Quality (MP4)", "720p")
                            bitrate >= 400_000 -> Pair("480p SD Quality (MP4)", "480p")
                            else -> Pair("360p Standard Quality (MP4)", "360p")
                        }
                        val estBytes = if (durationSeconds > 0 && bitrate > 0) {
                            ((bitrate.toLong() * durationSeconds) / 8L).coerceAtLeast(1_000_000L)
                        } else {
                            when (res) {
                                "1080p" -> 25_000_000L
                                "720p" -> 14_000_000L
                                "480p" -> 7_000_000L
                                else -> 3_500_000L
                            }
                        }

                        // Avoid duplicate labels
                        if (formats.none { it.resolution == res }) {
                            formats.add(
                                VideoFormat(
                                    id = "x_${res}_${formats.size}",
                                    qualityLabel = label,
                                    fileExtension = "mp4",
                                    mimeType = "video/mp4",
                                    estimatedBytes = estBytes,
                                    downloadUrl = streamUrl,
                                    resolution = res,
                                    isAudioOnly = false
                                )
                            )
                        }
                    }
                }
            }

            // Fallback to primary url if no variants parsed
            if (formats.isEmpty()) {
                val primaryUrl = firstVideo.optString("url", "https://d.fxtwitter.com/$screenName/status/$statusId.mp4")
                formats.add(
                    VideoFormat(
                        id = "x_best",
                        qualityLabel = "High Quality Video (MP4)",
                        fileExtension = "mp4",
                        mimeType = "video/mp4",
                        estimatedBytes = 15_000_000L,
                        downloadUrl = primaryUrl,
                        resolution = "HD",
                        isAudioOnly = false
                    )
                )
            }

            // Add MP3 Audio Option
            val primaryStream = formats.first().downloadUrl
            val audioBytes = if (durationSeconds > 0) ((320_000L * durationSeconds) / 8L).coerceAtLeast(1_500_000L) else 4_000_000L
            formats.add(
                VideoFormat(
                    id = "x_audio_mp3",
                    qualityLabel = "MP3 Audio (320 kbps)",
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg",
                    estimatedBytes = audioBytes,
                    downloadUrl = primaryStream,
                    resolution = "320 kbps",
                    isAudioOnly = true
                )
            )

            Result.success(
                VideoMetadata(
                    id = statusId,
                    originalUrl = url,
                    title = title,
                    author = author,
                    durationSeconds = durationSeconds,
                    thumbnailUrl = thumbUrl,
                    platform = Platform.TWITTER,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun tryVxTwitter(originalUrl: String, screenName: String, statusId: String): Result<VideoMetadata> {
        try {
            val vxUrl = "https://api.vxtwitter.com/$screenName/status/$statusId"
            val req = Request.Builder()
                .url(vxUrl)
                .header("User-Agent", "SDownload/2.0 (Android; +https://github.com)")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val mediaExtended = json.optJSONArray("media_extended")
                    val videoEntry = (0 until (mediaExtended?.length() ?: 0))
                        .map { mediaExtended!!.getJSONObject(it) }
                        .firstOrNull { it.optString("type").contains("video", true) || it.optString("type").contains("gif", true) }
                    if (videoEntry != null) {
                        val videoUrl = videoEntry.optString("url", "")
                        if (videoUrl.startsWith("http")) {
                            val thumb = videoEntry.optString("thumbnail_url", "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80")
                            val title = json.optString("text", "X / Twitter Video").lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "X / Twitter Video"
                            val author = json.optString("user_name", "X Creator")
                            val dur = (videoEntry.optDouble("duration_millis", 0.0) / 1000.0).toInt()
                            return Result.success(
                                VideoMetadata(
                                    id = statusId,
                                    originalUrl = originalUrl,
                                    title = title,
                                    author = author,
                                    durationSeconds = dur,
                                    thumbnailUrl = thumb,
                                    platform = Platform.TWITTER,
                                    formats = listOf(
                                        VideoFormat(
                                            id = "vx_hd",
                                            qualityLabel = "High Quality Video (MP4)",
                                            fileExtension = "mp4",
                                            mimeType = "video/mp4",
                                            estimatedBytes = 15_000_000L,
                                            downloadUrl = videoUrl,
                                            resolution = "HD",
                                            isAudioOnly = false
                                        ),
                                        VideoFormat(
                                            id = "vx_audio",
                                            qualityLabel = "MP3 Audio (320 kbps)",
                                            fileExtension = "mp3",
                                            mimeType = "audio/mpeg",
                                            estimatedBytes = 3_500_000L,
                                            downloadUrl = videoUrl,
                                            resolution = "320 kbps",
                                            isAudioOnly = true
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: Twitter public Syndication API
        return try {
            val synToken = ((statusId.toLongOrNull() ?: 1L) / 1e15 * Math.PI).toString().replace(".", "").take(8)
            val synUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=$synToken"
            val req = Request.Builder()
                .url(synUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("Twitter/X API returned ${resp.code}"))
                val json = JSONObject(resp.body?.string() ?: "{}")
                val videoObj = json.optJSONObject("video")
                    ?: return Result.failure(Exception("No video found in this X / Twitter post"))
                val variants = videoObj.optJSONArray("variants")
                    ?: return Result.failure(Exception("No video variants in X / Twitter post"))
                val mp4Variants = (0 until variants.length())
                    .map { variants.getJSONObject(it) }
                    .filter { it.optString("type").contains("mp4", true) && it.optString("src").startsWith("http") }
                val bestSrc = mp4Variants.lastOrNull()?.optString("src")
                    ?: return Result.failure(Exception("No MP4 stream in X / Twitter post"))

                val title = json.optString("text", "X / Twitter Video").lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "X / Twitter Video"
                val author = json.optJSONObject("user")?.optString("name", "X Creator") ?: "X Creator"
                val thumb = videoObj.optString("poster", "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80")
                val dur = (videoObj.optDouble("durationMs", 0.0) / 1000.0).toInt()

                Result.success(
                    VideoMetadata(
                        id = statusId,
                        originalUrl = originalUrl,
                        title = title,
                        author = author,
                        durationSeconds = dur,
                        thumbnailUrl = thumb,
                        platform = Platform.TWITTER,
                        formats = listOf(
                            VideoFormat(
                                id = "syn_hd",
                                qualityLabel = "High Quality Video (MP4)",
                                fileExtension = "mp4",
                                mimeType = "video/mp4",
                                estimatedBytes = 15_000_000L,
                                downloadUrl = bestSrc,
                                resolution = "HD",
                                isAudioOnly = false
                            ),
                            VideoFormat(
                                id = "syn_audio",
                                qualityLabel = "MP3 Audio (320 kbps)",
                                fileExtension = "mp3",
                                mimeType = "audio/mpeg",
                                estimatedBytes = 3_500_000L,
                                downloadUrl = bestSrc,
                                resolution = "320 kbps",
                                isAudioOnly = true
                            )
                        )
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildDirectTwitterMetadata(
        id: String,
        originalUrl: String,
        streamUrl: String,
        title: String = "X / Twitter Video",
        author: String = "X Creator"
    ): VideoMetadata {
        val formats = listOf(
            VideoFormat(
                id = "x_direct_hd",
                qualityLabel = "1080p Full HD (Original MP4)",
                fileExtension = "mp4",
                mimeType = "video/mp4",
                estimatedBytes = 18_000_000L,
                downloadUrl = streamUrl,
                resolution = "1080p",
                isAudioOnly = false
            ),
            VideoFormat(
                id = "x_direct_audio",
                qualityLabel = "MP3 Audio (320 kbps)",
                fileExtension = "mp3",
                mimeType = "audio/mpeg",
                estimatedBytes = 3_500_000L,
                downloadUrl = streamUrl,
                resolution = "320 kbps",
                isAudioOnly = true
            )
        )
        return VideoMetadata(
            id = id,
            originalUrl = originalUrl,
            title = title,
            author = author,
            durationSeconds = 30,
            thumbnailUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80",
            platform = Platform.TWITTER,
            formats = formats
        )
    }

    fun extractStatusId(url: String): String? {
        val patterns = listOf(
            "/(?:status|statuses)/(\\d+)",
            "/i/status/(\\d+)"
        )
        for (p in patterns) {
            val m = Regex(p).find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    fun extractScreenName(url: String): String? {
        val m = Regex("""(?:twitter\.com|x\.com)/([a-zA-Z0-9_]+)/status""").find(url)
        val name = m?.groupValues?.get(1)
        return if (name.isNullOrBlank() || name.equals("i", ignoreCase = true)) "i" else name
    }
}


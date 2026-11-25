package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dedicated Native Beeg.com Video Extractor.
 * Resolves full-length videos (1080p Full HD, 720p, 480p, 360p, and MP3 audio)
 * directly from the Beeg / Externulls API and HLS CDN without downloading 3-second teasers.
 */
class BeegExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Extractor {

    override val name: String = "BeegExtractor"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("beeg.com") || lower.contains("externulls.com")
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            // Extract clean video ID (digits only, stripping leading minus and leading zeros)
            val regex = Regex("""beeg\.com/(?:video/)?-?0*(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(url) ?: Regex("""(\d{6,})""").find(url)
            val rawId = match?.groupValues?.get(1) ?: return@withContext Result.failure(
                IllegalArgumentException("Could not detect valid Beeg video ID from URL")
            )
            val videoId = rawId.trimStart('0').ifEmpty { rawId }

            // 1. Fetch metadata from store.externulls.com/facts/file/$videoId
            val factsReq = Request.Builder()
                .url("https://store.externulls.com/facts/file/$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Referer", "https://beeg.com/")
                .header("Origin", "https://beeg.com")
                .build()

            val factsResp = client.newCall(factsReq).execute()
            if (!factsResp.isSuccessful) {
                return@withContext Result.failure(Exception("Beeg API error HTTP ${factsResp.code}"))
            }

            val factsJson = JSONObject(factsResp.body?.string() ?: "{}")
            val fileObj = factsJson.optJSONObject("file") ?: JSONObject()

            // Video Title
            var title = "Beeg Video #$videoId"
            val dataArray = fileObj.optJSONArray("data")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i)
                    if (item?.optString("cd_column") == "sf_name") {
                        val v = item.optString("cd_value")
                        if (!v.isNullOrBlank()) {
                            title = v.trim()
                            break
                        }
                    }
                }
            }

            // Duration in seconds (full video duration, e.g. 629s)
            val durationSeconds = fileObj.optInt("fl_duration", 0)

            // Author / Creator
            var author = "Beeg Creator"
            val tagsArray = factsJson.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    val tag = tagsArray.optJSONObject(i)
                    if (tag?.optBoolean("is_person") == true) {
                        val name = tag.optString("tg_name")
                        if (!name.isNullOrBlank()) {
                            author = name.trim()
                            break
                        }
                    }
                }
            }

            val thumbUrl = "https://thumbs.externulls.com/videos/$videoId/0.webp"

            // 2. Fetch play_url from store.externulls.com/video/play_url/$videoId
            val playReq = Request.Builder()
                .url("https://store.externulls.com/video/play_url/$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Referer", "https://beeg.com/")
                .header("Origin", "https://beeg.com")
                .build()

            val playResp = client.newCall(playReq).execute()
            if (!playResp.isSuccessful) {
                return@withContext Result.failure(Exception("Beeg play URL error HTTP ${playResp.code}"))
            }

            val playPath = playResp.body?.string()?.trim() ?: ""
            if (playPath.isBlank()) {
                return@withContext Result.failure(Exception("Empty play path returned from Beeg"))
            }

            // 3. Fetch master HLS playlist from https://video.beeg.com/$playPath
            val masterUrl = "https://video.beeg.com/$playPath"
            val masterReq = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Referer", "https://beeg.com/")
                .header("Origin", "https://beeg.com")
                .build()

            val masterResp = client.newCall(masterReq).execute()
            val formats = mutableListOf<VideoFormat>()

            if (masterResp.isSuccessful) {
                val masterBody = masterResp.body?.string() ?: ""
                val lines = masterBody.lines()

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val nextLine = lines.getOrNull(i + 1)?.trim() ?: continue
                        if (nextLine.startsWith("/")) {
                            // Extract stream URL
                            val streamUrl = "https://video.beeg.com$nextLine"

                            // Prioritize H.264 (avc1) for 100% Android hardware decoding compatibility
                            val isAvc = line.contains("avc1", ignoreCase = true) || (!line.contains("av01") && !line.contains("hvc1"))
                            if (isAvc) {
                                val height = when {
                                    line.contains("1920x1080") -> 1080
                                    line.contains("1280x720") -> 720
                                    line.contains("854x480") -> 480
                                    line.contains("640x360") -> 360
                                    line.contains("426x240") -> 240
                                    else -> 720
                                }

                                val label = when (height) {
                                    1080 -> "1080p Full HD (MP4)"
                                    720 -> "720p HD Quality (MP4)"
                                    480 -> "480p SD Quality (MP4)"
                                    360 -> "360p Quality (MP4)"
                                    else -> "${height}p Quality (MP4)"
                                }

                                val bps = when (height) {
                                    1080 -> 7_400_000L
                                    720 -> 3_400_000L
                                    480 -> 1_700_000L
                                    360 -> 1_000_000L
                                    else -> 500_000L
                                }
                                val estBytes = if (durationSeconds > 0) {
                                    ((bps * durationSeconds) / 8L).coerceAtLeast(10_000_000L)
                                } else {
                                    when (height) {
                                        1080 -> 180_000_000L
                                        720 -> 90_000_000L
                                        480 -> 45_000_000L
                                        else -> 25_000_000L
                                    }
                                }

                                if (formats.none { it.resolution == "${height}p" }) {
                                    formats.add(
                                        VideoFormat(
                                            id = "beeg_${height}p",
                                            qualityLabel = label,
                                            fileExtension = "mp4",
                                            mimeType = "video/mp4",
                                            estimatedBytes = estBytes,
                                            downloadUrl = streamUrl,
                                            resolution = "${height}p",
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Fallback to master URL if variant parsing was empty
            if (formats.isEmpty()) {
                val estBytes = if (durationSeconds > 0) ((4_000_000L * durationSeconds) / 8L) else 80_000_000L
                formats.add(
                    VideoFormat(
                        id = "beeg_master_best",
                        qualityLabel = "Full HD Quality (MP4)",
                        fileExtension = "mp4",
                        mimeType = "video/mp4",
                        estimatedBytes = estBytes,
                        downloadUrl = masterUrl,
                        resolution = "1080p",
                        isAudioOnly = false
                    )
                )
            }

            // Sort descending by resolution height
            formats.sortByDescending { it.resolution?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }

            // Add High Quality MP3 Audio Track Option
            val audioEst = if (durationSeconds > 0) {
                ((320_000L * durationSeconds) / 8L).coerceAtLeast(3_000_000L)
            } else 8_000_000L

            formats.add(
                VideoFormat(
                    id = "beeg_audio_mp3",
                    qualityLabel = "MP3 Audio (320 kbps)",
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg",
                    estimatedBytes = audioEst,
                    downloadUrl = formats.first().downloadUrl,
                    resolution = "320 kbps",
                    isAudioOnly = true
                )
            )

            Result.success(
                VideoMetadata(
                    id = videoId,
                    originalUrl = url,
                    title = title,
                    author = author,
                    durationSeconds = durationSeconds,
                    thumbnailUrl = thumbUrl,
                    platform = Platform.GENERIC,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


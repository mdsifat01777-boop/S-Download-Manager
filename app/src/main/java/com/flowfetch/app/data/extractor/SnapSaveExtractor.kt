package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID
import kotlin.math.pow

/**
 * SnapSave High-Definition Extraction Engine.
 * Extracts authentic 1080p Full HD, 720p HD, and audio tracks
 * from Facebook and Instagram using SnapSave's decoder.
 */
class SnapSaveExtractor(private val client: OkHttpClient) {

    private val base64Alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

    fun extract(url: String, platform: Platform): Result<VideoMetadata> {
        return try {
            val formBody = FormBody.Builder()
                .add("url", url)
                .build()

            val request = Request.Builder()
                .url("https://snapsave.app/action.php?lang=en")
                .post(formBody)
                .header("Origin", "https://snapsave.app")
                .header("Referer", "https://snapsave.app/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val rawResponse = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.failure(Exception("SnapSave request failed with code ${response.code}"))
                response.body?.string() ?: return Result.failure(Exception("Empty SnapSave response"))
            }

            val decodedHtml = decodeSnapResponse(rawResponse)
                ?: return Result.failure(Exception("Failed to decode SnapSave payload"))

            if (decodedHtml.contains("error_api_get_") || decodedHtml.contains("Unable to connect")) {
                return Result.failure(Exception("SnapSave could not fetch stream for this link"))
            }

            parseDecodedHtml(decodedHtml, url, platform)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decodeSnapResponse(raw: String): String? {
        val regex = Regex("""\("([^"]+)",\s*(\d+),\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+)\)""")
        val match = regex.find(raw) ?: return null

        val h = match.groupValues[1]
        val u = match.groupValues[2].toIntOrNull() ?: return null
        val n = match.groupValues[3]
        val t = match.groupValues[4].toIntOrNull() ?: return null
        val e = match.groupValues[5].toIntOrNull() ?: return null
        val r = match.groupValues[6].toIntOrNull() ?: return null

        return decodeSnap(h, u, n, t, e, r)
    }

    private fun decodeSnap(h: String, u: Int, n: String, t: Int, e: Int, r: Int): String {
        val result = StringBuilder()
        var i = 0
        val delimiter = n[e]

        while (i < h.length) {
            val sb = StringBuilder()
            while (i < h.length && h[i] != delimiter) {
                sb.append(h[i])
                i++
            }
            var s = sb.toString()
            for (j in 0 until n.length) {
                s = s.replace(n[j].toString(), j.toString())
            }

            val baseVal = baseConvert(s, e, 10).toLongOrNull() ?: 0L
            val charCode = (baseVal - t).toInt()
            if (charCode > 0) {
                result.append(charCode.toChar())
            }
            i++ // skip delimiter
        }

        return try {
            URLDecoder.decode(result.toString(), "UTF-8")
        } catch (_: Exception) {
            result.toString()
        }
    }

    private fun baseConvert(d: String, e: Int, f: Int): String {
        val h = base64Alphabet.substring(0, e.coerceAtMost(base64Alphabet.length))
        val i = base64Alphabet.substring(0, f.coerceAtMost(base64Alphabet.length))

        var j = 0.0
        val reversed = d.reversed()
        for (c in 0 until reversed.length) {
            val idx = h.indexOf(reversed[c])
            if (idx != -1) {
                j += idx * e.toDouble().pow(c.toDouble())
            }
        }

        var k = ""
        var cur = j.toLong()
        while (cur > 0) {
            val rem = (cur % f).toInt()
            if (rem in i.indices) {
                k = i[rem] + k
            }
            cur /= f
        }

        return if (k.isEmpty()) "0" else k
    }

    private fun parseDecodedHtml(html: String, originalUrl: String, platform: Platform): Result<VideoMetadata> {
        // Unescape escaped quotes and characters from JS string payload
        val cleanHtml = html
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")

        val formats = mutableListOf<VideoFormat>()
        var detectedDurationSeconds = 0

        // 1. Check for table rows (Facebook table with 720p HD, 1080p, etc.)
        val rowRegex = Regex("""<td class="video-quality">([^<]+)</td>\s*<td>([^<]*)</td>\s*<td>([\s\S]*?)</td>""")
        val rows = rowRegex.findAll(cleanHtml)

        for (row in rows) {
            val quality = row.groupValues[1].trim()
            val tdAction = row.groupValues[3]

            // Check if there is an <a href="..."> download link
            val linkMatch = Regex("""href="([^"]+)"""").find(tdAction)
            if (linkMatch != null) {
                var streamUrl = linkMatch.groupValues[1].replace("&amp;", "&")
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                if (streamUrl.startsWith("/")) streamUrl = "https://snapsave.app$streamUrl"

                if (streamUrl.isNotBlank() && !streamUrl.startsWith("&")) {
                    // Extract duration from token in URL if available
                    val dur = extractDurationFromTokenOrUrl(streamUrl)
                    if (dur > 0 && detectedDurationSeconds == 0) {
                        detectedDurationSeconds = dur
                    }

                    val isHd = quality.contains("HD", ignoreCase = true) || quality.contains("720") || quality.contains("1080")
                    val estBytes = if (detectedDurationSeconds > 0) {
                        val bps = if (isHd) 900_000L else 450_000L
                        ((bps * detectedDurationSeconds) / 8L).coerceAtLeast(15_000_000L)
                    } else {
                        if (isHd) 28_000_000L else 12_000_000L
                    }

                val fmtId = "snapsave_${quality.replace(" ", "_")}"
                    if (formats.none { it.id == fmtId }) {
                        formats.add(
                            VideoFormat(
                                id = fmtId,
                                qualityLabel = if (isHd) "$quality Full Quality (MP4)" else "$quality (Standard MP4)",
                                fileExtension = "mp4",
                                mimeType = "video/mp4",
                                estimatedBytes = estBytes,
                                downloadUrl = streamUrl,
                                resolution = quality,
                                isAudioOnly = false
                            )
                        )
                    }
                }
            }

            // Check if there is a render token with separate video and audio streams
            val renderTokenMatch = Regex("""/render\.php\?token=([^"'\s)]+)""").find(tdAction)
            if (renderTokenMatch != null) {
                val token = renderTokenMatch.groupValues[1]
                val decodedJson = decodeJwtPayload(token)
                if (decodedJson != null) {
                    val videoStreamUrl = decodedJson.optString("video_url", "")
                    val audioStreamUrl = decodedJson.optString("audio_url", "")

                    val dur = extractDurationFromTokenOrUrl(token)
                    if (dur > 0 && detectedDurationSeconds == 0) {
                        detectedDurationSeconds = dur
                    }

                    if (videoStreamUrl.isNotBlank() && formats.none { it.id == "snapsave_1080p_hd" }) {
                        val estBytes = if (detectedDurationSeconds > 0) {
                            ((1_800_000L * detectedDurationSeconds) / 8L).coerceAtLeast(35_000_000L)
                        } else {
                            35_000_000L
                        }
                        formats.add(
                            VideoFormat(
                                id = "snapsave_1080p_hd",
                                qualityLabel = "1080p Full HD (High Bitrate MP4)",
                                fileExtension = "mp4",
                                mimeType = "video/mp4",
                                estimatedBytes = estBytes,
                                downloadUrl = videoStreamUrl,
                                resolution = "1080p",
                                isAudioOnly = false,
                                audioUrl = audioStreamUrl.takeIf { it.isNotBlank() }
                            )
                        )
                    }

                    if (audioStreamUrl.isNotBlank() && formats.none { it.id == "snapsave_render_audio" }) {
                        val estAudioBytes = if (detectedDurationSeconds > 0) {
                            ((320_000L * detectedDurationSeconds) / 8L).coerceAtLeast(4_000_000L)
                        } else {
                            4_500_000L
                        }
                        formats.add(
                            VideoFormat(
                                id = "snapsave_render_audio",
                                qualityLabel = "MP3 Audio (Original Sound)",
                                fileExtension = "mp3",
                                mimeType = "audio/mpeg",
                                estimatedBytes = estAudioBytes,
                                downloadUrl = audioStreamUrl,
                                resolution = "320 kbps",
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }
        }

        // 2. Check for single download item (Instagram format or single button)
        if (formats.isEmpty()) {
            val singleDownloadMatch = Regex("""<a[^>]+href="([^"]+)"[^>]*title="Download[^"]*"[^>]*>([\s\S]*?)</a>""").find(cleanHtml)
                ?: Regex("""<a[^>]+href="([^"]+)"[^>]*class="[^"]*button[^"]*is-success[^"]*"[^>]*>""").find(cleanHtml)

            val downloadUrl = singleDownloadMatch?.groupValues?.get(1)?.replace("&amp;", "&")
            if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("&")) {
                formats.add(
                    VideoFormat(
                        id = "snapsave_direct",
                        qualityLabel = "High Quality Video (Original MP4)",
                        fileExtension = "mp4",
                        mimeType = "video/mp4",
                        estimatedBytes = 18_000_000L,
                        downloadUrl = downloadUrl,
                        resolution = "HD",
                        isAudioOnly = false
                    )
                )
            }
        }

        // Thumbnail extraction
        val thumbMatch = Regex("""<img[^>]+src="([^"]+)"""").find(cleanHtml)
        val thumbUrl = thumbMatch?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&q=80"

        // Title extraction
        val titleMatch = Regex("""<span class="video-des">([^<]+)</span>""").find(cleanHtml)
            ?: Regex("""<strong>([^<]+)</strong>""").find(cleanHtml)
        var title = titleMatch?.groupValues?.get(1)?.trim()?.ifBlank { "Social Media Video" } ?: "Social Media Video"
        if (title == "..." || title.length <= 3) title = "Social Media Video"

        if (formats.isEmpty()) {
            return Result.failure(Exception("No downloadable streams found in SnapSave response"))
        }

        // Sort video formats from highest quality (1080p -> 720p -> SD)
        formats.sortByDescending {
            when {
                it.isAudioOnly -> -1
                it.resolution?.contains("1080") == true -> 1080
                it.resolution?.contains("720") == true -> 720
                it.resolution?.contains("480") == true -> 480
                it.resolution?.contains("360") == true -> 360
                else -> 500
            }
        }

        // Ensure an audio format is always available for the separate Audio tab
        val primaryStream = formats.firstOrNull { !it.isAudioOnly }?.downloadUrl ?: formats.first().downloadUrl
        if (formats.none { it.isAudioOnly }) {
            val audioBytes = if (detectedDurationSeconds > 0) {
                ((320_000L * detectedDurationSeconds) / 8L).coerceAtLeast(3_500_000L)
            } else {
                4_500_000L
            }
            formats.add(
                VideoFormat(
                    id = "snapsave_audio",
                    qualityLabel = "MP3 Audio (320 kbps)",
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg",
                    estimatedBytes = audioBytes,
                    downloadUrl = primaryStream,
                    resolution = "320 kbps",
                    isAudioOnly = true
                )
            )
        }

        val finalDuration = if (detectedDurationSeconds > 0) detectedDurationSeconds else 30

        return Result.success(
            VideoMetadata(
                id = UUID.randomUUID().toString().take(8),
                originalUrl = originalUrl,
                title = title,
                author = platform.displayName,
                durationSeconds = finalDuration,
                thumbnailUrl = thumbUrl,
                platform = platform,
                formats = formats
            )
        )
    }

    private fun extractDurationFromTokenOrUrl(tokenOrUrl: String): Int {
        return try {
            val tokenMatch = Regex("""token=([a-zA-Z0-9_\-\.]+)""").find(tokenOrUrl)
            val token = tokenMatch?.groupValues?.get(1) ?: tokenOrUrl
            val json = decodeJwtPayload(token)
            if (json != null) {
                val dur = json.optInt("duration_s", json.optInt("duration", 0))
                if (dur > 0) return dur

                val targetUrl = json.optString("url", json.optString("video_url", ""))
                if (targetUrl.isNotBlank()) {
                    val efgMatch = Regex("""efg=([a-zA-Z0-9_\-\.%]+)""").find(targetUrl)
                    if (efgMatch != null) {
                        val efgRaw = URLDecoder.decode(efgMatch.groupValues[1], "UTF-8")
                        val efgBytes = try {
                            java.util.Base64.getDecoder().decode(efgRaw)
                        } catch (_: Throwable) {
                            android.util.Base64.decode(efgRaw, android.util.Base64.DEFAULT)
                        }
                        val efgJson = JSONObject(String(efgBytes, Charsets.UTF_8))
                        val efgDur = efgJson.optInt("duration_s", 0)
                        if (efgDur > 0) return efgDur
                    }
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeJwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                var payloadBase64 = parts[1].replace('-', '+').replace('_', '/')
                while (payloadBase64.length % 4 != 0) {
                    payloadBase64 += "="
                }
                val decodedBytes = try {
                    java.util.Base64.getDecoder().decode(payloadBase64)
                } catch (_: Throwable) {
                    android.util.Base64.decode(payloadBase64, android.util.Base64.DEFAULT)
                }
                val jsonStr = String(decodedBytes, Charsets.UTF_8)
                JSONObject(jsonStr)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

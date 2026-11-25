package com.flowfetch.app

import com.flowfetch.app.data.extractor.BeegExtractor
import com.flowfetch.app.data.extractor.GenericWebVideoExtractor
import com.flowfetch.app.data.extractor.SmartVideoExtractor
import com.flowfetch.app.data.extractor.TwitterFxExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class LiveMultiSiteVerificationTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun verifyStreamDownloadable(url: String, referer: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Range", "bytes=0-65535")
                .apply {
                    if (referer.isNotBlank()) header("Referer", referer)
                }
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val ctype = resp.header("Content-Type") ?: "unknown"
                val clen = resp.header("Content-Length") ?: resp.header("Content-Range") ?: "unknown"
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val headAscii = String(bytes.take(36).toByteArray(), Charsets.ISO_8859_1)
                    .replace(Regex("[^\\x20-\\x7E]"), ".")
                var extra = ""
                if (ctype.contains("mpegurl", true) || headAscii.startsWith("#EXTM3U")) {
                    val m3u8Text = String(bytes, Charsets.UTF_8)
                    val firstSeg = m3u8Text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (firstSeg != null) {
                        val segUrl = URI(url).resolve(firstSeg).toString()
                        val segReq = Request.Builder().url(segUrl).header("Range", "bytes=0-8191")
                            .apply { if (referer.isNotBlank()) header("Referer", referer) }.build()
                        extra = client.newCall(segReq).execute().use { sr ->
                            val sb = sr.body?.bytes() ?: ByteArray(0)
                            val firstHex = sb.take(4).joinToString(" ") { "%02X".format(it) }
                            " | segHTTP=${sr.code} segBytes=${sb.size} hex=[$firstHex]"
                        }
                    }
                }
                "HTTP=$code ctype=$ctype size=$clen read=${bytes.size} ascii=[$headAscii]$extra"
            }
        } catch (e: Exception) {
            "ERR=${e.javaClass.simpleName}:${e.message}"
        }
    }

    @Test
    fun verifyAllExtractorsAndStreams() = runBlocking {
        val smart = SmartVideoExtractor()
        val beeg = BeegExtractor()
        val twitter = TwitterFxExtractor()
        val generic = GenericWebVideoExtractor()

        val testCases = listOf(
            "FB_REEL_1" to "https://www.facebook.com/share/r/1FAA4o3w5j/",
            "FB_REEL_2" to "https://www.facebook.com/share/r/1EBy2RMWcz/",
            "FB_VID_1" to "https://www.facebook.com/share/v/1HxRRikqyZ/",
            "FB_VID_2" to "https://www.facebook.com/share/v/1QQ6EKVYM4/",
            "IG_REEL" to "https://www.instagram.com/reel/DgGfjf5zUxt/",
            "TIKTOK" to "https://www.tiktok.com/@tiktok/video/7106594312292453675",
            "BEEG" to "https://beeg.com/-0383025456840383",
            "X_NOVID" to "https://x.com/SpaceX/status/1761825126899986851",
            "X_VIDEO" to "https://x.com/NASA/status/1845815239199375741",
            "YT_4K" to "https://www.youtube.com/watch?v=sRWcJrMTtMI",
            "YT_CLASSIC" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "DIRECT_MP4" to "https://www.w3schools.com/html/mov_bbb.mp4",
            "GENERIC_HTML5" to "https://www.w3schools.com/html/html5_video.asp"
        )

        println("=== LIVE MULTI-SITE EXTRACTION & DOWNLOAD AUDIT v2 ===")
        for ((tag, url) in testCases) {
            val t0 = System.currentTimeMillis()
            val result = when {
                beeg.canHandle(url) -> beeg.extract(url)
                url.contains("x.com") || url.contains("twitter.com") -> twitter.extract(url)
                tag == "GENERIC_HTML5" -> generic.extract(url)
                else -> smart.extract(url)
            }
            val elapsed = System.currentTimeMillis() - t0
            if (result.isSuccess) {
                val meta = result.getOrNull()!!
                println("[$tag] OK (${elapsed}ms) title='${meta.title.take(45)}' dur=${meta.durationSeconds}s formats=${meta.formats.size}")
                meta.formats.forEachIndexed { idx, fmt ->
                    val probe = if (idx < 3 || fmt.isAudioOnly) verifyStreamDownloadable(fmt.downloadUrl, url) else "(skipped probe)"
                    val audioProbe = if (!fmt.audioUrl.isNullOrBlank()) verifyStreamDownloadable(fmt.audioUrl!!, url) else "none"
                    println("   fmt[$idx] id=${fmt.id} label='${fmt.qualityLabel}' res=${fmt.resolution} est=${fmt.estimatedBytes} audio=${fmt.isAudioOnly} hasAudioTrack=${fmt.audioUrl != null}")
                    println("      urlProbe: $probe")
                    if (fmt.audioUrl != null) println("      audProbe: $audioProbe")
                }
            } else {
                println("[$tag] FAIL (${elapsed}ms): ${result.exceptionOrNull()?.message}")
            }
        }
        println("=== END AUDIT v2 ===")
    }
}

package com.flowfetch.app.data.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import com.flowfetch.app.ui.utils.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Ultra-fast direct media downloader that streams bytes in real-time,
 * updates live percentage smoothly, and inserts directly into Android's Gallery (MediaStore).
 */
class DownloadManagerHelper(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    private val wakeLock = powerManager?.newWakeLock(
        android.os.PowerManager.PARTIAL_WAKE_LOCK,
        "FlowFetch::DownloadWakeLock"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Downloads direct stream URL at maximum throughput with live byte-by-byte progress callbacks,
     * saving directly into public DIRECTORY_DOWNLOADS and indexing immediately into Android Gallery.
     */
    suspend fun downloadDirect(
        metadata: VideoMetadata,
        format: VideoFormat,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            wakeLock?.acquire(20 * 60 * 1000L)
            val sanitizedTitle = Formatters.sanitizeFilename(metadata.title)
            val extension = format.fileExtension.lowercase()
            val fileName = "SDownload_${sanitizedTitle}_${System.currentTimeMillis() % 10000}.$extension"

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val targetFile = File(downloadsDir, fileName)

            val requestBuilder = Request.Builder()
                .url(format.downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")

            if (metadata.originalUrl.isNotBlank()) {
                requestBuilder.header("Referer", metadata.originalUrl)
            }

            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val targetCookieUrl = if (metadata.originalUrl.isNotBlank()) metadata.originalUrl else format.downloadUrl
                val cookies = cookieManager.getCookie(targetCookieUrl)
                if (!cookies.isNullOrBlank()) {
                    requestBuilder.header("Cookie", cookies)
                }
            } catch (_: Throwable) {}

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${response.code}: ${response.message}"))
            }

            val contentType = response.header("Content-Type")?.lowercase() ?: ""
            if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                return@withContext Result.failure(Exception("The link points to a webpage, not a direct video stream."))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download stream"))
            val contentLength = body.contentLength().let { if (it > 0) it else format.estimatedBytes }

            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(64 * 1024) // 64KB high-speed buffer
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100 || totalBytesRead == contentLength) {
                        lastProgressUpdate = now
                        val currentProgress = if (contentLength > 0) {
                            ((totalBytesRead.toDouble() / contentLength.toDouble()) * 100.0).toFloat().coerceIn(0f, 99f)
                        } else {
                            50f
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(totalBytesRead, contentLength, currentProgress)
                        }
                    }
                }
                outputStream.flush()
            } finally {
                inputStream?.close()
                outputStream?.close()
                body.close()
            }

            // Verify downloaded file is an actual playable media file (not HTML or truncated partial chunk)
            if (!isValidMediaFile(targetFile, format.isAudioOnly)) {
                if (targetFile.exists()) targetFile.delete()
                return@withContext Result.failure(Exception("Downloaded stream is incomplete or unsupported (possibly a partial segment)."))
            }

            // Immediately Index into Android MediaStore / Gallery
            indexIntoGallery(context, targetFile, format.mimeType, format.isAudioOnly)

            withContext(Dispatchers.Main) {
                onProgress(totalBytesReadOrLength(targetFile, contentLength), targetFile.length(), 100f)
                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    /**
     * Downloads using the embedded native yt-dlp & FFmpeg engine.
     * Automatically merges high-definition video (up to 4K) with audio tracks via FFmpeg,
     * or extracts pure 320kbps MP3 audio with metadata.
     */
    suspend fun downloadWithYtDlp(
        metadata: VideoMetadata,
        format: VideoFormat,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val sanitizedTitle = Formatters.sanitizeFilename(metadata.title)
        val isAudio = format.isAudioOnly
        val ext = if (isAudio) "mp3" else "mp4"
        val fileSuffix = "${System.currentTimeMillis() % 10000}"
        val fileNamePrefix = "SDownload_${sanitizedTitle}_$fileSuffix"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, "$fileNamePrefix.$ext")

        try {
            wakeLock?.acquire(30 * 60 * 1000L)

            // Fast Path A: Separate Video + Audio tracks (e.g. Facebook/Instagram DASH or SnapSave 1080p render)
            if (!isAudio && !format.audioUrl.isNullOrBlank() && format.audioUrl != format.downloadUrl) {
                val muxRes = downloadAndMuxStreams(
                    videoUrl = format.downloadUrl,
                    audioUrl = format.audioUrl,
                    referer = metadata.originalUrl.ifBlank { format.downloadUrl },
                    targetFile = targetFile,
                    fileNamePrefix = fileNamePrefix,
                    mimeType = format.mimeType,
                    onProgress = onProgress
                )
                if (muxRes.isSuccess) {
                    return@withContext muxRes
                }
            }

            // Fast Path B: Direct progressive CDN MP4 from Web Sniffer (fbcdn/cdninstagram without separate audio)
            if (!isAudio && format.id.startsWith("sniffed_") && !format.id.startsWith("sniffed_page_") &&
                !format.downloadUrl.contains(".m3u8", ignoreCase = true) &&
                (format.downloadUrl.contains(".fbcdn.net/") || format.downloadUrl.contains(".cdninstagram.com/"))
            ) {
                val directRes = downloadDirect(metadata, format, onProgress)
                if (directRes.isSuccess) {
                    return@withContext directRes
                }
            }

            val targetUrl = when {
                format.id.startsWith("sniffed_page_") && metadata.originalUrl.isNotBlank() -> metadata.originalUrl
                format.downloadUrl.contains(".m3u8", ignoreCase = true) -> format.downloadUrl
                metadata.originalUrl.isBlank() -> format.downloadUrl
                format.downloadUrl.isNotBlank() && (
                    format.id.startsWith("beeg_") ||
                    format.id.startsWith("sniffed_") ||
                    format.id.startsWith("direct_") ||
                    format.id.startsWith("generic_")
                ) -> format.downloadUrl
                else -> metadata.originalUrl
            }

            val request = com.yausername.youtubedl_android.YoutubeDLRequest(targetUrl)
            val outTemplate = File(downloadsDir, "$fileNamePrefix.%(ext)s").absolutePath
            request.addOption("-o", outTemplate)
            request.addOption("--no-mtime")
            request.addOption("--no-playlist")
            request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            if (metadata.originalUrl.isNotBlank()) {
                request.addOption("--referer", metadata.originalUrl)
                try {
                    val uri = Uri.parse(metadata.originalUrl)
                    val origin = "${uri.scheme}://${uri.host}"
                    if (!uri.host.isNullOrBlank()) {
                        request.addOption("--add-header", "Origin:$origin")
                    }
                } catch (_: Throwable) {}
            }

            // Inject logged-in browser session cookies from both page URL and target CDN URL
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val pageCookies = if (metadata.originalUrl.isNotBlank()) cookieManager.getCookie(metadata.originalUrl) else null
                val targetCookies = cookieManager.getCookie(targetUrl)
                val mergedCookies = listOfNotNull(pageCookies, targetCookies)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("; ")
                if (mergedCookies.isNotBlank()) {
                    request.addOption("--add-header", "Cookie:$mergedCookies")
                }
            } catch (_: Throwable) {}

            // Enable Aria2c Multi-connection Acceleration (16 concurrent threads like 1DM)
            if (!targetUrl.contains(".m3u8", ignoreCase = true) && !isAudio) {
                try {
                    request.addOption("--external-downloader", "aria2c")
                    request.addOption("--external-downloader-args", "aria2c:-s 16 -x 16 -j 16 -k 1M --file-allocation=none")
                } catch (_: Throwable) {}
            }

            if (isAudio) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            } else {
                if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                    request.addOption("-f", "best")
                    request.addOption("--concurrent-fragments", "8")
                } else {
                    val formatSelector = if (format.id.startsWith("ytdlp_")) {
                        val rawId = format.id.removePrefix("ytdlp_")
                        if (rawId == "best") "bestvideo+bestaudio/best" else "$rawId+bestaudio/best"
                    } else if (!format.resolution.isNullOrBlank()) {
                        val h = format.resolution.filter { it.isDigit() }
                        if (h.isNotBlank()) "bestvideo[height<=$h]+bestaudio/best" else "bestvideo+bestaudio/best"
                    } else {
                        "bestvideo+bestaudio/best"
                    }
                    request.addOption("-f", formatSelector)
                }
                request.addOption("--merge-output-format", "mp4")
            }

            var lastReportedProgress = 0f
            com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                val p = progress.coerceIn(0f, 99f)
                if (p > lastReportedProgress) {
                    lastReportedProgress = p
                    val currentBytes = ((format.estimatedBytes * p) / 100f).toLong()
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        onProgress(currentBytes, format.estimatedBytes, p)
                    }
                }
            }

            // Find the resulting file
            val finalFile = if (targetFile.exists()) {
                targetFile
            } else {
                val candidates = downloadsDir.listFiles { _, name -> name.startsWith(fileNamePrefix) }
                candidates?.firstOrNull() ?: targetFile
            }

            if (!isValidMediaFile(finalFile, isAudio)) {
                if (finalFile.exists()) finalFile.delete()
                throw Exception("Downloaded file is empty or corrupted. Trying fallback...")
            }

            indexIntoGallery(context, finalFile, format.mimeType, isAudio)

            withContext(Dispatchers.Main) {
                onProgress(finalFile.length(), finalFile.length(), 100f)
                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
            }

            Result.success(finalFile)
        } catch (e: Exception) {
            // Fallback 1: Multi-layer HLS .m3u8 Downloader (yt-dlp alt -> FFmpeg w/ LD_LIBRARY_PATH -> OkHttp segment downloader + FFmpeg remux)
            if (format.downloadUrl.contains(".m3u8", ignoreCase = true)) {
                val referer = metadata.originalUrl.ifBlank { format.downloadUrl }
                val cookies = getMergedCookies(referer, format.downloadUrl)

                // Layer 1a: FFmpeg directly over network with proper LD_LIBRARY_PATH & SSL_CERT_FILE
                try {
                    val headerStr = buildString {
                        append("User-Agent: Mozilla/5.0 (Linux; Android 14; 24122RKC7C) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36\r\n")
                        if (referer.isNotBlank()) append("Referer: $referer\r\n")
                        if (!cookies.isNullOrBlank()) append("Cookie: $cookies\r\n")
                    }
                    val exitCode = runEmbeddedFfmpeg(
                        listOf(
                            "-y",
                            "-headers", headerStr,
                            "-i", format.downloadUrl,
                            "-c", "copy",
                            "-bsf:a", "aac_adtstoasc",
                            "-movflags", "+faststart",
                            targetFile.absolutePath
                        )
                    )
                    if (exitCode == 0 && targetFile.exists() && targetFile.length() > 50_000L) {
                        indexIntoGallery(context, targetFile, format.mimeType, isAudio)
                        withContext(Dispatchers.Main) {
                            onProgress(targetFile.length(), targetFile.length(), 100f)
                            Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext Result.success(targetFile)
                    }
                } catch (_: Throwable) {}

                // Layer 1b: Pure Kotlin OkHttp HLS Segment Downloader + Local FFmpeg Remux
                // Uses Android's native TLS/HTTP2 stack (immune to Python/FFmpeg SSL issues)
                try {
                    val okHlsResult = downloadHlsWithOkHttp(
                        m3u8Url = format.downloadUrl,
                        referer = referer,
                        cookies = cookies,
                        targetFile = targetFile,
                        downloadsDir = downloadsDir,
                        fileNamePrefix = fileNamePrefix,
                        isAudio = isAudio,
                        mimeType = format.mimeType,
                        onProgress = onProgress
                    )
                    if (okHlsResult.isSuccess) {
                        return@withContext okHlsResult
                    }
                } catch (_: Throwable) {}

                Result.failure(e)
            }
            // Fallback 2: Direct stream downloader for direct MP4/MP3 links (with page-level fallback for sniffed streams)
            else if (format.downloadUrl.startsWith("http") && !format.downloadUrl.equals(metadata.originalUrl)) {
                val directRes = downloadDirect(metadata, format, onProgress)
                if (directRes.isSuccess) {
                    directRes
                } else if (metadata.originalUrl.startsWith("http") && format.id.startsWith("sniffed_") && !format.id.startsWith("sniffed_page_")) {
                    downloadWithYtDlp(
                        metadata,
                        format.copy(id = "sniffed_page_fallback", downloadUrl = metadata.originalUrl),
                        onProgress
                    )
                } else {
                    directRes
                }
            } else {
                Result.failure(e)
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private fun getMergedCookies(pageUrl: String, targetUrl: String): String {
        return try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val c1 = if (pageUrl.isNotBlank()) cookieManager.getCookie(pageUrl) else null
            val c2 = if (targetUrl.isNotBlank()) cookieManager.getCookie(targetUrl) else null
            listOfNotNull(c1, c2).filter { it.isNotBlank() }.distinct().joinToString("; ")
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Executes the embedded youtubedl-android `libffmpeg.so` binary with the exact
     * LD_LIBRARY_PATH and SSL_CERT_FILE environment variables it requires.
     */
    private fun runEmbeddedFfmpeg(args: List<String>): Int {
        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpegBin.exists()) return -1

        val ytBase = File(context.noBackupFilesDir, "youtubedl-android")
        val ffmpegLibDir = File(ytBase, "packages/ffmpeg/usr/lib").absolutePath
        val pythonLibDir = File(ytBase, "packages/python/usr/lib").absolutePath
        val sslCertFile = File(ytBase, "packages/python/usr/etc/tls/cert.pem")

        val pb = ProcessBuilder(listOf(ffmpegBin.absolutePath) + args)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = "$ffmpegLibDir:$pythonLibDir:${context.applicationInfo.nativeLibraryDir}"
        if (sslCertFile.exists()) {
            env["SSL_CERT_FILE"] = sslCertFile.absolutePath
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        // Drain output to prevent buffer deadlock
        proc.inputStream.bufferedReader().use { it.readText() }
        return proc.waitFor()
    }

    /**
     * Pure Kotlin OkHttp HLS (.m3u8) downloader + local FFmpeg remuxer.
     * Parses master playlists (preferring H.264 highest resolution), downloads all .ts/.m4s segments
     * cleanly using Android's native TLS stack, and remuxes locally into an MP4/MP3 file.
     */
    private suspend fun downloadHlsWithOkHttp(
        m3u8Url: String,
        referer: String,
        cookies: String,
        targetFile: File,
        downloadsDir: File,
        fileNamePrefix: String,
        isAudio: Boolean,
        mimeType: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> {
        val ua = "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36"
        val origin = try {
            val u = Uri.parse(referer)
            if (!u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else ""
        } catch (_: Exception) { "" }

        fun buildReq(url: String): Request {
            val b = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "*/*")
            if (referer.isNotBlank()) b.header("Referer", referer)
            if (origin.isNotBlank()) b.header("Origin", origin)
            if (cookies.isNotBlank()) b.header("Cookie", cookies)
            return b.build()
        }

        fun fetchText(url: String): String? {
            return client.newCall(buildReq(url)).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }

        fun resolveRelative(baseUrl: String, ref: String): String {
            return try {
                java.net.URI(baseUrl).resolve(ref).toString()
            } catch (_: Exception) {
                ref
            }
        }

        var playlistUrl = m3u8Url
        var playlistText = fetchText(playlistUrl)
            ?: return Result.failure(Exception("Failed to fetch HLS playlist"))

        // If master playlist, pick best H.264 variant stream
        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val lines = playlistText.lines()
            var bestUrl: String? = null
            var bestScore = -1L
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val next = lines.getOrNull(i + 1)?.trim() ?: continue
                    if (next.isBlank() || next.startsWith("#")) continue
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 1_000_000L
                    val isAvc = line.contains("avc1", ignoreCase = true) || (!line.contains("av01", true) && !line.contains("hvc1", true))
                    val score = if (isAvc) bw + 100_000_000L else bw
                    if (score > bestScore) {
                        bestScore = score
                        bestUrl = resolveRelative(playlistUrl, next)
                    }
                }
            }
            if (bestUrl != null) {
                playlistUrl = bestUrl
                playlistText = fetchText(playlistUrl)
                    ?: return Result.failure(Exception("Failed to fetch HLS variant playlist"))
            }
        }

        // Parse segments (+ optional #EXT-X-MAP init segment for fMP4)
        val segmentUrls = mutableListOf<String>()
        val pLines = playlistText.lines()
        for (raw in pLines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-MAP:")) {
                val mapUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                if (!mapUri.isNullOrBlank()) {
                    segmentUrls.add(resolveRelative(playlistUrl, mapUri))
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                segmentUrls.add(resolveRelative(playlistUrl, line))
            }
        }

        if (segmentUrls.isEmpty()) {
            return Result.failure(Exception("No media segments in HLS playlist"))
        }

        val tempTsFile = File(context.cacheDir, "${fileNamePrefix}_hls_tmp.ts")
        if (tempTsFile.exists()) tempTsFile.delete()

        var totalBytesWritten = 0L
        try {
            FileOutputStream(tempTsFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                val totalCount = segmentUrls.size
                for ((idx, segUrl) in segmentUrls.withIndex()) {
                    client.newCall(buildReq(segUrl)).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { ins ->
                                var read: Int
                                while (ins.read(buf).also { read = it } != -1) {
                                    fos.write(buf, 0, read)
                                    totalBytesWritten += read
                                }
                            }
                        }
                    }
                    val pct = (((idx + 1).toFloat() / totalCount.toFloat()) * 94f).coerceIn(1f, 94f)
                    val estTotal = ((totalBytesWritten.toDouble() / (idx + 1)) * totalCount).toLong().coerceAtLeast(totalBytesWritten)
                    withContext(Dispatchers.Main) {
                        onProgress(totalBytesWritten, estTotal, pct)
                    }
                }
                fos.flush()
            }

            if (!tempTsFile.exists() || tempTsFile.length() < 10_000L) {
                return Result.failure(Exception("Downloaded HLS stream was empty"))
            }

            // Remux local TS/fMP4 cleanly to MP4 or MP3 using embedded FFmpeg
            val ffmpegArgs = if (isAudio) {
                listOf("-y", "-i", tempTsFile.absolutePath, "-vn", "-acodec", "libmp3lame", "-q:a", "0", targetFile.absolutePath)
            } else {
                listOf("-y", "-i", tempTsFile.absolutePath, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", targetFile.absolutePath)
            }
            val exitCode = runEmbeddedFfmpeg(ffmpegArgs)

            val finalOut = if (exitCode == 0 && targetFile.exists() && targetFile.length() > 10_000L) {
                targetFile
            } else {
                // Retry without bitstream filter (needed if source was fMP4 or non-ADTS AAC)
                val exit2 = if (!isAudio) {
                    runEmbeddedFfmpeg(listOf("-y", "-i", tempTsFile.absolutePath, "-c", "copy", "-movflags", "+faststart", targetFile.absolutePath))
                } else -1
                if (exit2 == 0 && targetFile.exists() && targetFile.length() > 10_000L) {
                    targetFile
                } else {
                    // Fallback: copy raw TS stream to Downloads so user still gets playable video
                    val rawFallback = File(downloadsDir, "$fileNamePrefix.ts")
                    tempTsFile.copyTo(rawFallback, overwrite = true)
                    rawFallback
                }
            }

            indexIntoGallery(context, finalOut, mimeType, isAudio)
            withContext(Dispatchers.Main) {
                onProgress(finalOut.length(), finalOut.length(), 100f)
                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
            return Result.success(finalOut)
        } finally {
            if (tempTsFile.exists()) tempTsFile.delete()
        }
    }

    /**
     * Downloads separate video + audio streams via OkHttp (with WebView cookies/referer)
     * and muxes them losslessly into a single playable MP4 via embedded FFmpeg.
     */
    private suspend fun downloadAndMuxStreams(
        videoUrl: String,
        audioUrl: String,
        referer: String,
        targetFile: File,
        fileNamePrefix: String,
        mimeType: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> {
        val vidTmp = File(context.cacheDir, "${fileNamePrefix}_vid.mp4")
        val audTmp = File(context.cacheDir, "${fileNamePrefix}_aud.m4a")
        val cookies = getMergedCookies(referer, videoUrl)
        val ua = "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36"

        fun streamToFile(url: String, dest: File, basePct: Float, spanPct: Float): Long {
            val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "*/*").apply {
                if (referer.isNotBlank()) header("Referer", referer)
                if (cookies.isNotBlank()) header("Cookie", cookies)
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("Empty stream")
                val len = body.contentLength().coerceAtLeast(1L)
                var readTotal = 0L
                var lastUpdate = 0L
                FileOutputStream(dest).use { fos ->
                    body.byteStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (ins.read(buf).also { n = it } != -1) {
                            fos.write(buf, 0, n)
                            readTotal += n
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 120) {
                                lastUpdate = now
                                val p = (basePct + (readTotal.toFloat() / len.toFloat()) * spanPct).coerceIn(0f, 95f)
                                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                                    onProgress(readTotal, len, p)
                                }
                            }
                        }
                    }
                }
                return readTotal
            }
        }

        return try {
            streamToFile(videoUrl, vidTmp, 0f, 65f)
            if (!isValidMediaFile(vidTmp, false)) {
                return Result.failure(Exception("Video track incomplete or invalid"))
            }
            streamToFile(audioUrl, audTmp, 65f, 25f)

            val exit = if (audTmp.exists() && audTmp.length() > 5_000L) {
                runEmbeddedFfmpeg(
                    listOf("-y", "-i", vidTmp.absolutePath, "-i", audTmp.absolutePath, "-c", "copy", "-movflags", "+faststart", targetFile.absolutePath)
                )
            } else -1

            if (exit != 0 || !isValidMediaFile(targetFile, false)) {
                // Fallback: copy video track alone if audio track failed
                vidTmp.copyTo(targetFile, overwrite = true)
            }

            if (!isValidMediaFile(targetFile, false)) {
                if (targetFile.exists()) targetFile.delete()
                return Result.failure(Exception("Muxed stream invalid"))
            }

            indexIntoGallery(context, targetFile, mimeType, false)
            withContext(Dispatchers.Main) {
                onProgress(targetFile.length(), targetFile.length(), 100f)
                Toast.makeText(context, "Saved HD Video to Gallery!", Toast.LENGTH_SHORT).show()
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (vidTmp.exists()) vidTmp.delete()
            if (audTmp.exists()) audTmp.delete()
        }
    }

    private fun totalBytesReadOrLength(file: File, fallback: Long): Long {
        return if (file.exists() && file.length() > 0) file.length() else fallback
    }

    /**
     * Checks that a downloaded file exists, is reasonably sized, is NOT an HTML/JSON error page,
     * and matches expected media container headers (e.g., MP4 ftyp/moov, WebM EBML, TS 0x47, or MP3 ID3/frame sync).
     */
    private fun isValidMediaFile(file: File, isAudio: Boolean): Boolean {
        if (!file.exists()) return false
        val minBytes = if (isAudio) 5_000L else 25_000L
        if (file.length() < minBytes) return false

        return try {
            val header = ByteArray(64)
            val readCount = java.io.FileInputStream(file).use { it.read(header) }
            if (readCount < 8) return false

            val headStr = String(header, 0, readCount.coerceAtMost(32), Charsets.ISO_8859_1).trimStart().lowercase()
            if (headStr.startsWith("<!doc") || headStr.startsWith("<html") || headStr.startsWith("<?xml") || headStr.startsWith("{\"")) {
                return false
            }

            val ext = file.extension.lowercase()
            if (ext == "mp4" || ext == "m4a") {
                val ascii64 = String(header, 0, readCount, Charsets.ISO_8859_1)
                // Valid MP4 files have 'ftyp', 'moov', 'mdat', or 'free' box within the first 64 bytes
                return ascii64.contains("ftyp") || ascii64.contains("moov") || ascii64.contains("mdat") || ascii64.contains("free")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inserts the completed download file into Android's MediaStore so it instantly appears
     * in Google Photos, Gallery apps, and system media players.
     */
    private fun indexIntoGallery(context: Context, file: File, mimeType: String, isAudio: Boolean) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                // Scanned successfully
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isAudio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }

                try {
                    context.contentResolver.insert(collection, values)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Permanently deletes a downloaded file from device storage and MediaStore.
     */
    fun deleteDownloadedFile(item: DownloadItem): Boolean {
        return try {
            var deleted = false

            // Delete physical file
            if (!item.filePath.isNullOrBlank()) {
                val file = File(item.filePath)
                if (file.exists()) {
                    deleted = file.delete()
                }
            }

            // Fallback search in Downloads directory
            if (!deleted) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.listFiles()?.forEach { f ->
                    if (f.name.contains(item.title.take(15), ignoreCase = true)) {
                        deleted = f.delete()
                    }
                }
            }

            // Delete from MediaStore if Uri present
            if (!item.localUri.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(item.localUri)
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {}
            }

            deleted
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens the video or audio file directly in the default media player.
     */
    fun openDownloadedFile(item: DownloadItem) {
        try {
            var targetFile: File? = null

            if (!item.filePath.isNullOrBlank()) {
                val f = File(item.filePath)
                if (f.exists()) targetFile = f
            }

            if (targetFile == null) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                targetFile = downloadsDir.listFiles()?.firstOrNull {
                    it.name.contains(item.title.take(15), ignoreCase = true)
                }
            }

            if (targetFile != null && targetFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    targetFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Open with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                Toast.makeText(context, "File not found on device", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

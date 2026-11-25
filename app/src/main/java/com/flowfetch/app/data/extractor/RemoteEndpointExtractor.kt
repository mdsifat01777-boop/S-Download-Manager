package com.flowfetch.app.data.extractor

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
import java.util.concurrent.TimeUnit

/**
 * Extractor that forwards the URL to a configurable REST parsing endpoint
 * (such as a Cobalt instance or custom backend extractor service).
 */
class RemoteEndpointExtractor(
    private val endpointUrl: String = "https://api.flowfetch.app/api/extract",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) : Extractor {

    override val name: String = "RemoteEndpointExtractor"

    override fun canHandle(url: String): Boolean {
        // Can handle any supported video streaming URL
        return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }

    override suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val jsonPayload = JSONObject().apply {
                put("url", url)
            }
            val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpointUrl)
                .post(body)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Remote extraction server returned: ${response.code}"))
                }
                val rawJson = response.body?.string() ?: throw Exception("Empty server response")
                val obj = JSONObject(rawJson)

                val title = obj.optString("title", "Online Video")
                val author = obj.optString("author", "Creator")
                val duration = obj.optInt("duration", 0)
                val thumbnail = obj.optString("thumbnail", "")
                val formatsJson = obj.optJSONArray("formats")

                val formatsList = mutableListOf<VideoFormat>()
                if (formatsJson != null) {
                    for (i in 0 until formatsJson.length()) {
                        val f = formatsJson.getJSONObject(i)
                        formatsList.add(
                            VideoFormat(
                                id = f.optString("id", "fmt_$i"),
                                qualityLabel = f.optString("quality", "Standard Quality"),
                                fileExtension = f.optString("ext", "mp4"),
                                mimeType = f.optString("mime", "video/mp4"),
                                estimatedBytes = f.optLong("size", 10_000_000L),
                                downloadUrl = f.getString("url"),
                                isAudioOnly = f.optBoolean("isAudio", false)
                            )
                        )
                    }
                }

                val metadata = VideoMetadata(
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    originalUrl = url,
                    title = title,
                    author = author,
                    durationSeconds = duration,
                    thumbnailUrl = thumbnail,
                    platform = Platform.detect(url),
                    formats = formatsList
                )
                Result.success(metadata)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


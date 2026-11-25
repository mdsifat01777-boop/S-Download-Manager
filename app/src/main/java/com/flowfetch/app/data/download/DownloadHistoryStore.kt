package com.flowfetch.app.data.download

import android.content.Context
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.data.model.Platform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists download history (completed, failed, and active downloads) to SharedPreferences
 * using JSON so closing or swiping the app away never loses the user's download list.
 */
class DownloadHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(items: List<DownloadItem>) {
        try {
            val json = serialize(items.take(MAX_HISTORY_ITEMS))
            prefs.edit().putString(KEY_ITEMS, json).apply()
        } catch (_: Throwable) {}
    }

    fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return deserialize(raw, markInFlightAsInterrupted = true)
    }

    companion object {
        private const val PREFS_NAME = "sdownload_history_prefs"
        private const val KEY_ITEMS = "download_items_json"
        private const val MAX_HISTORY_ITEMS = 150

        fun serialize(items: List<DownloadItem>): String {
            val arr = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("originalUrl", item.originalUrl)
                    put("downloadUrl", item.downloadUrl)
                    put("thumbnailUrl", item.thumbnailUrl)
                    put("platform", item.platform.name)
                    put("formatLabel", item.formatLabel)
                    put("fileExtension", item.fileExtension)
                    put("mimeType", item.mimeType)
                    put("totalBytes", item.totalBytes)
                    put("downloadedBytes", item.downloadedBytes)
                    put("progress", item.progress.toDouble())
                    put("status", item.status.name)
                    if (item.localUri != null) put("localUri", item.localUri)
                    if (item.filePath != null) put("filePath", item.filePath)
                    put("timestamp", item.timestamp)
                    if (item.errorMessage != null) put("errorMessage", item.errorMessage)
                    put("formatId", item.formatId)
                    if (item.audioUrl != null) put("audioUrl", item.audioUrl)
                    if (item.resolution != null) put("resolution", item.resolution)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserialize(raw: String, markInFlightAsInterrupted: Boolean = false): List<DownloadItem> {
            return try {
                val arr = JSONArray(raw)
                val list = mutableListOf<DownloadItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawStatus = try {
                        DownloadStatus.valueOf(obj.optString("status", DownloadStatus.FAILED.name))
                    } catch (_: Exception) {
                        DownloadStatus.FAILED
                    }

                    val isInterrupted = markInFlightAsInterrupted &&
                        (rawStatus == DownloadStatus.DOWNLOADING || rawStatus == DownloadStatus.ENQUEUED)

                    val finalStatus = if (isInterrupted) DownloadStatus.FAILED else rawStatus
                    val finalError = if (isInterrupted) {
                        "Interrupted when app closed — Tap Retry"
                    } else {
                        obj.optString("errorMessage", "").ifBlank { null }
                    }

                    val platform = try {
                        Platform.valueOf(obj.optString("platform", Platform.GENERIC.name))
                    } catch (_: Exception) {
                        Platform.GENERIC
                    }

                    list.add(
                        DownloadItem(
                            id = obj.getLong("id"),
                            title = obj.optString("title", "Media"),
                            originalUrl = obj.optString("originalUrl", ""),
                            downloadUrl = obj.optString("downloadUrl", ""),
                            thumbnailUrl = obj.optString("thumbnailUrl", ""),
                            platform = platform,
                            formatLabel = obj.optString("formatLabel", "Video"),
                            fileExtension = obj.optString("fileExtension", "mp4"),
                            mimeType = obj.optString("mimeType", "video/mp4"),
                            totalBytes = obj.optLong("totalBytes", 0L),
                            downloadedBytes = obj.optLong("downloadedBytes", 0L),
                            progress = obj.optDouble("progress", 0.0).toFloat(),
                            status = finalStatus,
                            localUri = obj.optString("localUri", "").ifBlank { null },
                            filePath = obj.optString("filePath", "").ifBlank { null },
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            errorMessage = finalError,
                            formatId = obj.optString("formatId", ""),
                            audioUrl = obj.optString("audioUrl", "").ifBlank { null },
                            resolution = obj.optString("resolution", "").ifBlank { null }
                        )
                    )
                }
                list
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}

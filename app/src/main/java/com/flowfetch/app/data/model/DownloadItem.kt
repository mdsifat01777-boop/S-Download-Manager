package com.flowfetch.app.data.model

enum class DownloadStatus {
    ENQUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

data class DownloadItem(
    val id: Long,
    val title: String,
    val originalUrl: String,
    val downloadUrl: String,
    val thumbnailUrl: String,
    val platform: Platform,
    val formatLabel: String,
    val fileExtension: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val progress: Float, // 0.0 to 100.0
    val status: DownloadStatus,
    val localUri: String? = null,
    val filePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val formatId: String = "",
    val audioUrl: String? = null,
    val resolution: String? = null
)

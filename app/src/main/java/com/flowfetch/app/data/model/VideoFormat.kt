package com.flowfetch.app.data.model

data class VideoFormat(
    val id: String,
    val qualityLabel: String,
    val fileExtension: String,
    val mimeType: String,
    val estimatedBytes: Long,
    val downloadUrl: String,
    val isAudioOnly: Boolean = false,
    val resolution: String? = null,
    val audioUrl: String? = null
)

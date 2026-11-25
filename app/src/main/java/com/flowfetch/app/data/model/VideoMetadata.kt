package com.flowfetch.app.data.model

data class VideoMetadata(
    val id: String,
    val originalUrl: String,
    val title: String,
    val author: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val platform: Platform,
    val formats: List<VideoFormat>
) {
    val durationFormatted: String
        get() {
            if (durationSeconds <= 0) return ""
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}


package com.flowfetch.app.data.extractor

import com.flowfetch.app.data.model.VideoMetadata

/**
 * Clean extractor interface for extracting video metadata and direct downloadable stream URLs.
 * Allows implementations for native parsing or passing URLs to remote server endpoints.
 */
interface Extractor {
    val name: String

    /**
     * Checks if this extractor can handle the given URL.
     */
    fun canHandle(url: String): Boolean

    /**
     * Extracts video information, available formats/qualities, and direct stream URLs.
     */
    suspend fun extract(url: String): Result<VideoMetadata>
}


package com.flowfetch.app

import com.flowfetch.app.data.download.DownloadHistoryStore
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.data.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHistoryStoreTest {

    @Test
    fun testSerializeAndDeserializeCompletedAndInterruptedDownloads() {
        val completed = DownloadItem(
            id = 1001L,
            title = "Test Video",
            originalUrl = "https://www.facebook.com/share/r/123/",
            downloadUrl = "https://cdn.example.com/video.mp4",
            thumbnailUrl = "https://cdn.example.com/thumb.jpg",
            platform = Platform.FACEBOOK,
            formatLabel = "1080p Full HD",
            fileExtension = "mp4",
            mimeType = "video/mp4",
            totalBytes = 25_000_000L,
            downloadedBytes = 25_000_000L,
            progress = 100f,
            status = DownloadStatus.COMPLETED,
            filePath = "/storage/emulated/0/Download/test.mp4",
            formatId = "snapsave_1080p_hd",
            audioUrl = "https://cdn.example.com/audio.mp4",
            resolution = "1080p"
        )

        val interrupted = DownloadItem(
            id = 1002L,
            title = "Active Interrupted Video",
            originalUrl = "https://beeg.com/-123",
            downloadUrl = "https://video.beeg.com/1080p.m3u8",
            thumbnailUrl = "https://thumbs.externulls.com/123.webp",
            platform = Platform.GENERIC,
            formatLabel = "1080p Full HD (MP4)",
            fileExtension = "mp4",
            mimeType = "video/mp4",
            totalBytes = 100_000_000L,
            downloadedBytes = 40_000_000L,
            progress = 40f,
            status = DownloadStatus.DOWNLOADING,
            formatId = "beeg_1080p",
            resolution = "1080p"
        )

        val json = DownloadHistoryStore.serialize(listOf(completed, interrupted))
        val restored = DownloadHistoryStore.deserialize(json, markInFlightAsInterrupted = true)

        assertEquals(2, restored.size)

        val r0 = restored[0]
        assertEquals(1001L, r0.id)
        assertEquals(DownloadStatus.COMPLETED, r0.status)
        assertEquals("/storage/emulated/0/Download/test.mp4", r0.filePath)
        assertEquals("snapsave_1080p_hd", r0.formatId)
        assertEquals("https://cdn.example.com/audio.mp4", r0.audioUrl)
        assertEquals("1080p", r0.resolution)
        assertNull(r0.errorMessage)

        val r1 = restored[1]
        assertEquals(1002L, r1.id)
        assertEquals(DownloadStatus.FAILED, r1.status)
        assertTrue(r1.errorMessage!!.contains("Retry"))
        assertEquals("beeg_1080p", r1.formatId)
    }
}

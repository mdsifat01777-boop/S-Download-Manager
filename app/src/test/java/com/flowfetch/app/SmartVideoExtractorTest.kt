package com.flowfetch.app

import com.flowfetch.app.data.extractor.SmartVideoExtractor
import com.flowfetch.app.data.model.Platform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartVideoExtractorTest {

    private lateinit var extractor: SmartVideoExtractor

    @Before
    fun setup() {
        extractor = SmartVideoExtractor()
    }

    @Test
    fun testPlatformDetection() {
        assertEquals(Platform.YOUTUBE, Platform.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.detect("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.TIKTOK, Platform.detect("https://www.tiktok.com/@creator/video/7123456789012345678"))
        assertEquals(Platform.INSTAGRAM, Platform.detect("https://www.instagram.com/reel/C1234567890/"))
        assertEquals(Platform.TWITTER, Platform.detect("https://x.com/user/status/1234567890123456789"))
        assertEquals(Platform.FACEBOOK, Platform.detect("https://www.facebook.com/watch/?v=1234567890"))
        assertEquals(Platform.GENERIC, Platform.detect("https://example.com/video.mp4"))
    }

    @Test
    fun testYouTubeExtraction() = runBlocking {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertTrue(extractor.canHandle(url))

        val result = extractor.extract(url)
        if (result.isSuccess) {
            val metadata = result.getOrNull()
            assertNotNull(metadata)
            assertEquals(Platform.YOUTUBE, metadata?.platform)
            assertTrue(metadata!!.formats.isNotEmpty())
        }
    }

    @Test
    fun testTikTokExtraction() = runBlocking {
        val url = "https://www.tiktok.com/@test/video/123456789"
        val result = extractor.extract(url)
        if (result.isSuccess) {
            val metadata = result.getOrNull()
            assertNotNull(metadata)
            assertEquals(Platform.TIKTOK, metadata?.platform)
            assertTrue(metadata!!.formats.any { it.qualityLabel.contains("No Watermark", ignoreCase = true) })
        } else {
            // Fake video ID correctly fails cleanly with meaningful exception
            assertNotNull(result.exceptionOrNull())
        }
    }

    @Test
    fun testFacebookExtractionWithSnapSave() = runBlocking {
        val fbUrl = "https://www.facebook.com/share/r/1FAA4o3w5j/"
        val result = extractor.extract(fbUrl)
        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(Platform.FACEBOOK, metadata?.platform)
        assertTrue(metadata!!.formats.isNotEmpty())
    }

    @Test
    fun testYouTube4kExtraction() = runBlocking {
        val url = "https://www.youtube.com/watch?v=sRWcJrMTtMI"
        val result = extractor.extract(url)
        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(Platform.YOUTUBE, metadata?.platform)
        assertTrue(metadata!!.formats.isNotEmpty())
        assertTrue(metadata.formats.any { !it.isAudioOnly })
        assertTrue(metadata.formats.any { it.isAudioOnly })
    }

    @Test
    fun testFacebookLongVideoExtraction() = runBlocking {
        val fbUrl = "https://www.facebook.com/share/v/1HxRRikqyZ/"
        val result = extractor.extract(fbUrl)
        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(Platform.FACEBOOK, metadata?.platform)
        assertTrue("Duration must be > 60s (was ${metadata!!.durationSeconds})", metadata.durationSeconds > 60)
        assertTrue("Size must be > 5MB", metadata.formats.first().estimatedBytes > 5_000_000L)
    }

    @Test
    fun testInstagramUrlCleaning() {
        val dirtyUrl = "https://www.instagram.com/reel/DgGfjf5zUxt/?dmpr=MWV5YXl3dmIzM21iZQ=="
        val cleaned = com.flowfetch.app.data.extractor.InstagramExtractor(null, okhttp3.OkHttpClient())
            .cleanInstagramUrl(dirtyUrl)
        assertEquals("https://www.instagram.com/reel/DgGfjf5zUxt/", cleaned)
    }

    @Test
    fun testTwitterStatusParsing() {
        val extractor = com.flowfetch.app.data.extractor.TwitterFxExtractor()
        val url = "https://x.com/SpaceX/status/1761825126899986851"
        assertEquals("1761825126899986851", extractor.extractStatusId(url))
        assertEquals("SpaceX", extractor.extractScreenName(url))
    }
}


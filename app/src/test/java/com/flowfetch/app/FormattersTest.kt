package com.flowfetch.app

import com.flowfetch.app.ui.utils.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun testFormatBytes() {
        assertEquals("500 B", Formatters.formatBytes(500L))
        assertEquals("1.5 KB", Formatters.formatBytes(1536L))
        assertEquals("10 MB", Formatters.formatBytes(10_485_760L))
        assertEquals("1.5 GB", Formatters.formatBytes(1_610_612_736L))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("0:45", Formatters.formatDuration(45))
        assertEquals("4:08", Formatters.formatDuration(248))
        assertEquals("1:05:30", Formatters.formatDuration(3930))
    }

    @Test
    fun testIsValidUrl() {
        assertTrue(Formatters.isValidUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(Formatters.isValidUrl("http://example.com/video.mp4"))
        assertTrue(Formatters.isValidUrl("https://tiktok.com/@user/video/123456789"))
        assertFalse(Formatters.isValidUrl("not a url"))
        assertFalse(Formatters.isValidUrl("ftp://example.com/file"))
        assertFalse(Formatters.isValidUrl(""))
    }

    @Test
    fun testSanitizeFilename() {
        val sanitized = Formatters.sanitizeFilename("Video: Top 10 Things? (Must Watch!) / 2024*")
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("?"))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("*"))
    }
}


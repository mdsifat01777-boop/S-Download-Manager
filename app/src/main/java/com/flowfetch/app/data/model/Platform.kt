package com.flowfetch.app.data.model

import androidx.compose.ui.graphics.Color

enum class Platform(
    val displayName: String,
    val brandColor: Color,
    val iconSymbol: String
) {
    YOUTUBE("YouTube", Color(0xFFFF0000), "▶"),
    TIKTOK("TikTok", Color(0xFF00F2FE), "♪"),
    INSTAGRAM("Instagram", Color(0xFFE1306C), "📷"),
    TWITTER("X / Twitter", Color(0xFF1DA1F2), "𝕏"),
    FACEBOOK("Facebook", Color(0xFF1877F2), "f"),
    GENERIC("Direct Media", Color(0xFF6366F1), "⚡");

    companion object {
        fun detect(url: String): Platform {
            val lower = url.lowercase().trim()
            return when {
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("tiktok.com") -> TIKTOK
                lower.contains("instagram.com") || lower.contains("instagr.am") -> INSTAGRAM
                lower.contains("twitter.com") || lower.contains("x.com") -> TWITTER
                lower.contains("facebook.com") || lower.contains("fb.watch") -> FACEBOOK
                else -> GENERIC
            }
        }
    }
}


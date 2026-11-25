package com.flowfetch.app.ui.utils

import java.text.DecimalFormat
import java.util.Locale
import java.util.regex.Pattern

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[index]
    }

    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val remainder = seconds % 3600
        val minutes = remainder / 60
        val secs = remainder % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }

    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        val pattern = Pattern.compile("^(https?://)?([\\da-z.-]+)\\.([a-z.]{2,6})([/\\w .-]*)*/?", Pattern.CASE_INSENSITIVE)
        return (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) &&
                pattern.matcher(trimmed).find()
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .take(120)
    }
}


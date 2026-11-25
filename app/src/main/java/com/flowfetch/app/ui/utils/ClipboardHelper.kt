package com.flowfetch.app.ui.utils

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {

    fun getClipboardUrl(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!clipboard.hasPrimaryClip()) return null

        val description = clipboard.primaryClipDescription
        if (description != null && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString()?.trim() ?: return null
            if (Formatters.isValidUrl(text)) {
                return text
            }
        }
        return null
    }

    fun copyToClipboard(context: Context, text: String, label: String = "FlowFetch") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}


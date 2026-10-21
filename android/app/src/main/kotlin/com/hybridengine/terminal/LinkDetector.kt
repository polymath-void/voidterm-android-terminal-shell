package com.hybridengine.terminal

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.util.Log

/**
 * LinkDetector: Dispatches URL interaction, supporting either
 * In-App WebView (bottom sheet with full view toggle) or system browser.
 */
object LinkDetector {
    private const val TAG = "LinkDetector"
    val URL_REGEX = Regex("""https?://[^\s"'<>\]\)]+""")

    data class DetectedLink(
        val url: String,
        val rect: RectF
    )

    fun extractUrls(text: String): List<String> {
        return URL_REGEX.findAll(text).map { it.value }.toList()
    }

    fun openExternal(context: Context, url: String) {
        try {
            // Android Termux compliance
            ProcessBuilder("termux-open-url", url).start()
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch external browser for $url: ${e.message}")
            }
        }
    }
}

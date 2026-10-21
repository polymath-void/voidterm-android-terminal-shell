package com.hybridengine.terminal

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TerminalBlock: Sealed model representing structured terminal visual segments.
 */
sealed class TerminalBlock {
    data class CommandEcho(
        val prompt: String,
        val command: String,
        val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    ) : TerminalBlock()

    data class OutputLines(
        val lines: List<List<AnsiParser.StyledText>>
    ) : TerminalBlock()

    data class LinkLine(
        val url: String,
        val displayText: String
    ) : TerminalBlock()

    data class ErrorLine(
        val text: String
    ) : TerminalBlock()

    data class PkgStreamLine(
        val text: String,
        val isProgress: Boolean = false
    ) : TerminalBlock()
}

/**
 * RichOutputClassifier: Evaluates raw output and categorizes into structured blocks.
 */
object RichOutputClassifier {
    private val URL_REGEX = Regex("""https?://[^\s"'<>\]\)]+""")
    private val ERROR_PREFIXES = listOf("error:", "fatal:", "failed:", "exception:", "e: ")

    fun classifyLine(rawLine: String, ansiParser: AnsiParser): TerminalBlock {
        val trimmed = rawLine.trim()
        val plain = rawLine.replace(Regex("\u001B\\[[0-9;]*m"), "").trim()

        val urlMatch = URL_REGEX.find(plain)
        if (urlMatch != null && (plain.startsWith("http://") || plain.startsWith("https://") || plain.contains("--> http"))) {
            return TerminalBlock.LinkLine(urlMatch.value, plain)
        }

        val plainLower = plain.lowercase()
        if (ERROR_PREFIXES.any { plainLower.startsWith(it) } || plain.startsWith("[ERROR]") || plain.startsWith("✘")) {
            return TerminalBlock.ErrorLine(plain)
        }

        if (plain.startsWith("[pkg]") || plain.startsWith("📦") || plain.contains("Unpacking ") || plain.contains("Setting up ")) {
            return TerminalBlock.PkgStreamLine(plain, plain.contains("%") || plain.contains("..."))
        }

        return TerminalBlock.OutputLines(listOf(ansiParser.parse(rawLine)))
    }
}

package com.hybridengine.terminal

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

class AnsiParser {

    data class StyledText(
        val text: String,
        val paint: Paint,
        val backgroundColor: Int? = null,
        val isItalic: Boolean = false
    )

    // Regex to capture standard ANSI color/formatting codes: \x1b[...m
    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")

    // Active theme colors
    var activeTheme: TerminalTheme = TerminalTheme.DRACULA

    // State tracking for markdown codeblocks across lines
    private var inCodeFence = false
    private var codeFenceLang = ""

    // Default terminal aesthetic
    val defaultTextPaint = Paint().apply {
        color = activeTheme.foreground
        textSize = 38f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    fun applyTheme(theme: TerminalTheme, textSize: Float) {
        activeTheme = theme
        defaultTextPaint.color = theme.foreground
        defaultTextPaint.textSize = textSize
    }

    /**
     * Splits raw multiline terminal output and parses ANSI codes + Markdown into styled segments per line.
     */
    fun parseLines(rawOutput: String): List<List<StyledText>> {
        val rawLines = rawOutput.split("\n")
        return rawLines.map { line -> parse(line) }
    }

    /**
     * Parses a single terminal text line into styled segments based on ANSI escape sequences and Markdown syntax.
     */
    fun parse(rawOutput: String): List<StyledText> {
        val parsedBlocks = mutableListOf<StyledText>()
        var currentPaint = Paint(defaultTextPaint)

        // 1. Handle Carriage Returns (\r) and Backspaces (\b)
        var sanitized = rawOutput.replace("\r", "")

        while (sanitized.contains("\b")) {
            sanitized = sanitized.replace(Regex(".\b"), "")
        }

        // Strip OSC sequences (e.g., window title \u001b]0;...\u0007)
        sanitized = sanitized.replace(Regex("\u001B\\][^\u0007\u001B]*[\u0007\u001B\\\\]"), "")

        // Strip non-SGR CSI control sequences (e.g. \u001b[2J, \u001b[H, \u001b[?25h, \u001b[?1049h, \u001b[2K)
        // Keeps color sequences: \u001b[...m
        sanitized = sanitized.replace(Regex("\u001B\\[[?=>]?[0-9;]*[a-ln-zA-LN-Z]"), "")
        sanitized = sanitized.replace(Regex("\u001B[=>][0-9;]*[a-zA-Z]"), "")

        // 2. Check for Markdown Fenced Code Blocks (```)
        val trimmed = sanitized.trim()
        if (trimmed.startsWith("```")) {
            if (!inCodeFence) {
                inCodeFence = true
                codeFenceLang = trimmed.removePrefix("```").trim()
                val langTag = if (codeFenceLang.isNotEmpty()) " [$codeFenceLang]" else ""
                val fencePaint = Paint(defaultTextPaint).apply {
                    color = Color.parseColor("#BD93F9")
                    isFakeBoldText = true
                }
                parsedBlocks.add(StyledText("─── Codeblock$langTag ───", fencePaint, Color.parseColor("#282A36")))
                return parsedBlocks
            } else {
                inCodeFence = false
                codeFenceLang = ""
                val fencePaint = Paint(defaultTextPaint).apply {
                    color = Color.parseColor("#6272A4")
                }
                parsedBlocks.add(StyledText("──────────────────────────", fencePaint, Color.parseColor("#282A36")))
                return parsedBlocks
            }
        }

        // If inside a markdown code block, style with distinct background tint
        if (inCodeFence) {
            val codePaint = Paint(defaultTextPaint).apply {
                color = Color.parseColor("#8BE9FD")
            }
            parsedBlocks.add(StyledText(sanitized, codePaint, Color.parseColor("#1E1E2E")))
            return parsedBlocks
        }

        // 3. Parse ANSI Color Codes
        var currentIndex = 0
        val matches = ansiRegex.findAll(sanitized)

        for (match in matches) {
            if (match.range.first > currentIndex) {
                val textSegment = sanitized.substring(currentIndex, match.range.first)
                if (textSegment.isNotEmpty()) {
                    parsedBlocks.addAll(parseMarkdownInSegment(textSegment, Paint(currentPaint)))
                }
            }

            val codeSequence = match.groupValues[1]
            currentPaint = applyAnsiCode(currentPaint, codeSequence)

            currentIndex = match.range.last + 1
        }

        // Add any remaining text after the last ANSI code
        if (currentIndex < sanitized.length) {
            val textSegment = sanitized.substring(currentIndex)
            if (textSegment.isNotEmpty()) {
                parsedBlocks.addAll(parseMarkdownInSegment(textSegment, Paint(currentPaint)))
            }
        }

        // If line was empty, keep an empty block to preserve blank lines
        if (parsedBlocks.isEmpty()) {
            parsedBlocks.add(StyledText("", Paint(defaultTextPaint)))
        }

        return parsedBlocks
    }

    /**
     * Parses inline markdown (headers, backticks `code`, bold **text**) within a plain text segment.
     */
    private fun parseMarkdownInSegment(segment: String, basePaint: Paint): List<StyledText> {
        val list = mutableListOf<StyledText>()

        // 1. Markdown Headers: # Header, ## Subheader, ### Section
        if (segment.startsWith("# ")) {
            val hPaint = Paint(basePaint).apply {
                color = Color.parseColor("#8BE9FD")
                isFakeBoldText = true
            }
            list.add(StyledText(segment, hPaint))
            return list
        } else if (segment.startsWith("## ")) {
            val hPaint = Paint(basePaint).apply {
                color = Color.parseColor("#BD93F9")
                isFakeBoldText = true
            }
            list.add(StyledText(segment, hPaint))
            return list
        } else if (segment.startsWith("### ")) {
            val hPaint = Paint(basePaint).apply {
                color = Color.parseColor("#50FA7B")
                isFakeBoldText = true
            }
            list.add(StyledText(segment, hPaint))
            return list
        }

        // 2. Inline Backticks `code`
        if (segment.contains('`')) {
            val parts = segment.split('`')
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty()) continue
                if (i % 2 == 1) {
                    // Inside backticks
                    val codePaint = Paint(basePaint).apply {
                        color = Color.parseColor("#50FA7B")
                    }
                    list.add(StyledText(part, codePaint, Color.parseColor("#282A36")))
                } else {
                    // Outside backticks
                    list.addAll(parseBoldInSegment(part, basePaint))
                }
            }
            return list
        }

        return parseBoldInSegment(segment, basePaint)
    }

    private fun parseBoldInSegment(segment: String, basePaint: Paint): List<StyledText> {
        if (!segment.contains("**")) {
            return listOf(StyledText(segment, basePaint))
        }

        val list = mutableListOf<StyledText>()
        val parts = segment.split("**")
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty()) continue
            if (i % 2 == 1) {
                val boldPaint = Paint(basePaint).apply {
                    isFakeBoldText = true
                }
                list.add(StyledText(part, boldPaint))
            } else {
                list.add(StyledText(part, basePaint))
            }
        }
        return list
    }

    private fun applyAnsiCode(basePaint: Paint, codeSequence: String): Paint {
        val newPaint = Paint(basePaint)
        val codes = codeSequence.split(";").mapNotNull { it.toIntOrNull() }

        if (codes.isEmpty() || codes.contains(0)) {
            // Reset to default
            return Paint(defaultTextPaint)
        }

        for (code in codes) {
            when (code) {
                1 -> newPaint.isFakeBoldText = true // Bold
                30 -> newPaint.color = activeTheme.ansiColors[0] // Black
                31 -> newPaint.color = activeTheme.ansiColors[1] // Red
                32 -> newPaint.color = activeTheme.ansiColors[2] // Green
                33 -> newPaint.color = activeTheme.ansiColors[3] // Yellow
                34 -> newPaint.color = activeTheme.ansiColors[4] // Blue
                35 -> newPaint.color = activeTheme.ansiColors[5] // Magenta
                36 -> newPaint.color = activeTheme.ansiColors[6] // Cyan
                37 -> newPaint.color = activeTheme.ansiColors[7] // White
                90 -> newPaint.color = activeTheme.ansiColors[0] // Bright Black
                91 -> newPaint.color = activeTheme.ansiColors[1] // Bright Red
                92 -> newPaint.color = activeTheme.ansiColors[2] // Bright Green
                93 -> newPaint.color = activeTheme.ansiColors[3] // Bright Yellow
                94 -> newPaint.color = activeTheme.ansiColors[4] // Bright Blue
                95 -> newPaint.color = activeTheme.ansiColors[5] // Bright Magenta
                96 -> newPaint.color = activeTheme.ansiColors[6] // Bright Cyan
                97 -> newPaint.color = activeTheme.ansiColors[7] // Bright White
            }
        }
        return newPaint
    }
}

package com.hybridengine.terminal

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

class AnsiParser {

    data class StyledText(val text: String, val paint: Paint)

    // Regex to capture standard ANSI color/formatting codes: \x1b[...m
    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")
    
    // Active theme colors
    var activeTheme: TerminalTheme = TerminalTheme.DRACULA

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
     * Splits raw multiline terminal output and parses ANSI codes into styled segments per line.
     */
    fun parseLines(rawOutput: String): List<List<StyledText>> {
        val rawLines = rawOutput.split("\n")
        return rawLines.map { line -> parse(line) }
    }

    /**
     * Parses a single terminal text line into styled segments based on ANSI escape sequences.
     */
    fun parse(rawOutput: String): List<StyledText> {
        val parsedBlocks = mutableListOf<StyledText>()
        var currentPaint = Paint(defaultTextPaint)
        
        // 1. Handle Carriage Returns (\r) and Backspaces (\b)
        var sanitized = rawOutput.replace("\r", "")
        
        // Simulate backspace by removing the previous character
        while (sanitized.contains("\b")) {
            sanitized = sanitized.replace(Regex(".\b"), "")
        }

        // 2. Parse ANSI Color Codes
        var currentIndex = 0
        val matches = ansiRegex.findAll(sanitized)

        for (match in matches) {
            // Add the text before the ANSI code with the current paint
            if (match.range.first > currentIndex) {
                val textSegment = sanitized.substring(currentIndex, match.range.first)
                if (textSegment.isNotEmpty()) {
                    parsedBlocks.add(StyledText(textSegment, Paint(currentPaint)))
                }
            }

            // Update the paint based on the ANSI code
            val codeSequence = match.groupValues[1]
            currentPaint = applyAnsiCode(currentPaint, codeSequence)
            
            currentIndex = match.range.last + 1
        }

        // Add any remaining text after the last ANSI code
        if (currentIndex < sanitized.length) {
            val textSegment = sanitized.substring(currentIndex)
            if (textSegment.isNotEmpty()) {
                parsedBlocks.add(StyledText(textSegment, Paint(currentPaint)))
            }
        }

        // If line was empty, keep an empty block to preserve blank lines
        if (parsedBlocks.isEmpty()) {
            parsedBlocks.add(StyledText("", Paint(defaultTextPaint)))
        }

        return parsedBlocks
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

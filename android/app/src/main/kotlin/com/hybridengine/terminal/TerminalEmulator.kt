package com.hybridengine.terminal

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * TerminalEmulator: Implements DEC VT100 / XTerm escape sequence state machine,
 * dispatching actions onto the 2D TerminalBuffer.
 */
class TerminalEmulator(
    val buffer: TerminalBuffer = TerminalBuffer(24, 80)
) {
    // Current character drawing attributes
    var currentFg: Int = TerminalChar.DEFAULT_FG
    var currentBg: Int = TerminalChar.DEFAULT_BG
    var currentFlags: Int = 0

    // Terminal mode flags
    var isCursorKeysApplication: Boolean = false
    var isBracketedPasteMode: Boolean = false

    // Parser State Machine
    private var parserState = STATE_NORMAL
    private val csiParams = StringBuilder()
    private val oscBuffer = StringBuilder()

    var onTitleChanged: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onScreenInvalidated: (() -> Unit)? = null

    companion object {
        private const val STATE_NORMAL = 0
        private const val STATE_ESC = 1
        private const val STATE_CSI = 2
        private const val STATE_OSC = 3
        private const val STATE_CHARSET = 4

        // Standard 16 ANSI Palette
        val ANSI_COLORS = intArrayOf(
            Color.parseColor("#000000"), // 0: Black
            Color.parseColor("#FF5555"), // 1: Red
            Color.parseColor("#50FA7B"), // 2: Green
            Color.parseColor("#F1FA8C"), // 3: Yellow
            Color.parseColor("#BD93F9"), // 4: Blue / Purple
            Color.parseColor("#FF79C6"), // 5: Magenta
            Color.parseColor("#8BE9FD"), // 6: Cyan
            Color.parseColor("#BFBFBF"), // 7: White
            Color.parseColor("#4D4D4D"), // 8: Bright Black (Gray)
            Color.parseColor("#FF6E6E"), // 9: Bright Red
            Color.parseColor("#69FF94"), // 10: Bright Green
            Color.parseColor("#FFFFA5"), // 11: Bright Yellow
            Color.parseColor("#D6ACFF"), // 12: Bright Blue
            Color.parseColor("#FF92DF"), // 13: Bright Magenta
            Color.parseColor("#A4FFFF"), // 14: Bright Cyan
            Color.parseColor("#FFFFFF")  // 15: Bright White
        )

        fun get256Color(index: Int): Int {
            if (index in 0..15) {
                return ANSI_COLORS[index]
            }
            if (index in 16..231) {
                // 6x6x6 color cube
                var n = index - 16
                val b = (n % 6) * 51
                n /= 6
                val g = (n % 6) * 51
                n /= 6
                val r = (n % 6) * 51
                return Color.rgb(r, g, b)
            }
            if (index in 232..255) {
                // Grayscale ramp
                val gray = (index - 232) * 10 + 8
                return Color.rgb(gray, gray, gray)
            }
            return TerminalChar.DEFAULT_FG
        }
    }

    fun resetAttributes() {
        currentFg = TerminalChar.DEFAULT_FG
        currentBg = TerminalChar.DEFAULT_BG
        currentFlags = 0
    }

    fun feed(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val b = data[i]
            // UTF-8 decoding support
            val ch: Char
            if (b >= 0) {
                ch = b.toInt().toChar()
                i++
            } else if ((b.toInt() and 0xE0) == 0xC0 && i + 1 < length) {
                val b2 = data[i + 1]
                ch = (((b.toInt() and 0x1F) shl 6) or (b2.toInt() and 0x3F)).toChar()
                i += 2
            } else if ((b.toInt() and 0xF0) == 0xE0 && i + 2 < length) {
                val b2 = data[i + 1]
                val b3 = data[i + 2]
                ch = (((b.toInt() and 0x0F) shl 12) or
                        ((b2.toInt() and 0x3F) shl 6) or
                        (b3.toInt() and 0x3F)).toChar()
                i += 3
            } else {
                ch = (b.toInt() and 0xFF).toChar()
                i++
            }
            processChar(ch)
        }
        onScreenInvalidated?.invoke()
    }

    fun feed(text: String) {
        for (ch in text) {
            processChar(ch)
        }
        onScreenInvalidated?.invoke()
    }

    private fun processChar(ch: Char) {
        when (parserState) {
            STATE_NORMAL -> handleNormalState(ch)
            STATE_ESC -> handleEscState(ch)
            STATE_CSI -> handleCsiState(ch)
            STATE_OSC -> handleOscState(ch)
            STATE_CHARSET -> {
                parserState = STATE_NORMAL
            }
        }
    }

    private fun handleNormalState(ch: Char) {
        when (ch) {
            '\u001b' -> {
                parserState = STATE_ESC
            }
            '\r' -> {
                buffer.carriageReturn()
            }
            '\n' -> {
                buffer.lineFeed()
            }
            '\b' -> {
                buffer.backspace()
            }
            '\t' -> {
                buffer.tab()
            }
            '\u0007' -> {
                onBell?.invoke()
            }
            else -> {
                if (ch.code >= 32) {
                    buffer.putChar(ch, currentFg, currentBg, currentFlags)
                }
            }
        }
    }

    private fun handleEscState(ch: Char) {
        when (ch) {
            '[' -> {
                csiParams.setLength(0)
                parserState = STATE_CSI
            }
            ']' -> {
                oscBuffer.setLength(0)
                parserState = STATE_OSC
            }
            '(', ')' -> {
                parserState = STATE_CHARSET
            }
            '7' -> {
                buffer.saveCursor()
                parserState = STATE_NORMAL
            }
            '8' -> {
                buffer.restoreCursor()
                parserState = STATE_NORMAL
            }
            'M' -> {
                // Reverse index (scroll down if at top)
                if (buffer.cursorRow == buffer.topMargin) {
                    buffer.scrollDown(1)
                } else if (buffer.cursorRow > 0) {
                    buffer.cursorRow--
                }
                parserState = STATE_NORMAL
            }
            'c' -> {
                // Full reset (RIS)
                resetAttributes()
                buffer.clearScreen(2)
                buffer.moveCursor(0, 0)
                parserState = STATE_NORMAL
            }
            else -> {
                parserState = STATE_NORMAL
            }
        }
    }

    private fun handleCsiState(ch: Char) {
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '=' || ch == ' ') {
            csiParams.append(ch)
            return
        }

        val paramStr = csiParams.toString()
        val isPrivate = paramStr.startsWith("?")
        val cleanParamStr = if (isPrivate) paramStr.removePrefix("?") else paramStr
        val tokens = if (cleanParamStr.isEmpty()) emptyList() else cleanParamStr.split(";").mapNotNull { it.toIntOrNull() }

        when (ch) {
            'A' -> { // Cursor Up
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(-n, 0)
            }
            'B' -> { // Cursor Down
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(n, 0)
            }
            'C' -> { // Cursor Forward (Right)
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(0, n)
            }
            'D' -> { // Cursor Backward (Left)
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(0, -n)
            }
            'E' -> { // Cursor Next Line
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(n, 0)
                buffer.cursorCol = 0
            }
            'F' -> { // Cursor Previous Line
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.moveCursorRelative(-n, 0)
                buffer.cursorCol = 0
            }
            'G', '`' -> { // Cursor Horizontal Absolute (1-indexed)
                val col = max(1, tokens.getOrNull(0) ?: 1)
                buffer.cursorCol = min(buffer.cols - 1, col - 1)
            }
            'd' -> { // Line Position Absolute (1-indexed)
                val row = max(1, tokens.getOrNull(0) ?: 1)
                buffer.cursorRow = min(buffer.rows - 1, row - 1)
            }
            'H', 'f' -> { // Cursor Position (row, col) (1-indexed)
                val row = max(1, tokens.getOrNull(0) ?: 1) - 1
                val col = max(1, tokens.getOrNull(1) ?: 1) - 1
                buffer.moveCursor(row, col)
            }
            'J' -> { // Erase in Display
                val mode = tokens.getOrNull(0) ?: 0
                buffer.clearScreen(mode)
            }
            'K' -> { // Erase in Line
                val mode = tokens.getOrNull(0) ?: 0
                buffer.clearLine(mode)
            }
            'L' -> { // Insert Line
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.insertLines(n)
            }
            'M' -> { // Delete Line
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.deleteLines(n)
            }
            'P' -> { // Delete Character
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.deleteChars(n)
            }
            '@' -> { // Insert Characters
                // Treat as erase / blank insert
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.eraseChars(n)
            }
            'X' -> { // Erase Characters
                val n = max(1, tokens.getOrNull(0) ?: 1)
                buffer.eraseChars(n)
            }
            'r' -> { // Set Scrolling Margins (DECSTBM)
                val top = (tokens.getOrNull(0) ?: 1) - 1
                val bottom = (tokens.getOrNull(1) ?: buffer.rows) - 1
                buffer.setMargins(top, bottom)
            }
            's' -> { // Save cursor
                buffer.saveCursor()
            }
            'u' -> { // Restore cursor
                buffer.restoreCursor()
            }
            'm' -> { // Select Graphic Rendition (SGR)
                handleSgr(tokens)
            }
            'h' -> { // Mode Set
                if (isPrivate) {
                    for (mode in tokens) {
                        when (mode) {
                            1 -> isCursorKeysApplication = true
                            25 -> buffer.isCursorVisible = true
                            47, 1049 -> buffer.enterAlternateScreen()
                            2004 -> isBracketedPasteMode = true
                        }
                    }
                }
            }
            'l' -> { // Mode Reset
                if (isPrivate) {
                    for (mode in tokens) {
                        when (mode) {
                            1 -> isCursorKeysApplication = false
                            25 -> buffer.isCursorVisible = false
                            47, 1049 -> buffer.exitAlternateScreen()
                            2004 -> isBracketedPasteMode = false
                        }
                    }
                }
            }
        }

        parserState = STATE_NORMAL
    }

    private fun handleSgr(tokens: List<Int>) {
        if (tokens.isEmpty()) {
            resetAttributes()
            return
        }

        var idx = 0
        while (idx < tokens.size) {
            val code = tokens[idx]
            when (code) {
                0 -> resetAttributes()
                1 -> currentFlags = currentFlags or TerminalChar.FLAG_BOLD
                2 -> currentFlags = currentFlags or TerminalChar.FLAG_DIM
                3 -> currentFlags = currentFlags or TerminalChar.FLAG_ITALIC
                4 -> currentFlags = currentFlags or TerminalChar.FLAG_UNDERLINE
                7 -> currentFlags = currentFlags or TerminalChar.FLAG_INVERSE
                22 -> {
                    currentFlags = currentFlags and (TerminalChar.FLAG_BOLD or TerminalChar.FLAG_DIM).inv()
                }
                23 -> currentFlags = currentFlags and TerminalChar.FLAG_ITALIC.inv()
                24 -> currentFlags = currentFlags and TerminalChar.FLAG_UNDERLINE.inv()
                27 -> currentFlags = currentFlags and TerminalChar.FLAG_INVERSE.inv()
                in 30..37 -> {
                    currentFg = ANSI_COLORS[code - 30]
                }
                38 -> {
                    // Extended foreground: 38;5;n or 38;2;r;g;b
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentFg = get256Color(tokens[idx + 2])
                        idx += 2
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentFg = Color.rgb(r, g, b)
                        idx += 4
                    }
                }
                39 -> currentFg = TerminalChar.DEFAULT_FG
                in 40..47 -> {
                    currentBg = ANSI_COLORS[code - 40]
                }
                48 -> {
                    // Extended background: 48;5;n or 48;2;r;g;b
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentBg = get256Color(tokens[idx + 2])
                        idx += 2
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentBg = Color.rgb(r, g, b)
                        idx += 4
                    }
                }
                49 -> currentBg = TerminalChar.DEFAULT_BG
                in 90..97 -> {
                    currentFg = ANSI_COLORS[(code - 90) + 8]
                }
                in 100..107 -> {
                    currentBg = ANSI_COLORS[(code - 100) + 8]
                }
            }
            idx++
        }
    }

    private fun handleOscState(ch: Char) {
        if (ch == '\u0007' || ch == '\u001b') {
            val content = oscBuffer.toString()
            if (content.startsWith("0;") || content.startsWith("2;")) {
                val title = content.substring(2)
                onTitleChanged?.invoke(title)
            }
            parserState = STATE_NORMAL
        } else {
            oscBuffer.append(ch)
        }
    }

    fun resize(newRows: Int, newCols: Int) {
        buffer.resize(newRows, newCols)
        onScreenInvalidated?.invoke()
    }
}

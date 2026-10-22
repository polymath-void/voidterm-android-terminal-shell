package com.hybridengine.terminal

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * TerminalChar: Represents a single cell in the 2D terminal grid.
 */
class TerminalChar(
    var char: Char = ' ',
    var fg: Int = DEFAULT_FG,
    var bg: Int = DEFAULT_BG,
    var flags: Int = 0
) {
    fun copyFrom(other: TerminalChar) {
        this.char = other.char
        this.fg = other.fg
        this.bg = other.bg
        this.flags = other.flags
    }

    fun reset() {
        this.char = ' '
        this.fg = DEFAULT_FG
        this.bg = DEFAULT_BG
        this.flags = 0
    }

    companion object {
        const val FLAG_BOLD = 1
        const val FLAG_ITALIC = 2
        const val FLAG_UNDERLINE = 4
        const val FLAG_INVERSE = 8
        const val FLAG_DIM = 16

        val DEFAULT_FG = Color.parseColor("#F8F8F2")
        val DEFAULT_BG = Color.TRANSPARENT
    }
}

/**
 * TerminalBuffer: Manages the 2D character matrix, Primary Screen (with scrollback),
 * and Alternate Screen Buffer (used by full-screen interactive TUIs like vim, nano, htop, ext_hub_tui).
 */
class TerminalBuffer(
    var rows: Int = 24,
    var cols: Int = 80,
    private val maxScrollback: Int = 3000
) {
    // Primary screen lines
    private var primaryScreen: Array<Array<TerminalChar>> = createScreen(rows, cols)
    // Scrollback history (circular list)
    private val scrollback: ArrayList<Array<TerminalChar>> = ArrayList()
    // Alternate screen lines (fixed rows x cols, zero scrollback)
    private var alternateScreen: Array<Array<TerminalChar>> = createScreen(rows, cols)

    var isAlternateScreen: Boolean = false
        private set

    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var isCursorVisible: Boolean = true

    // Margins (0-indexed, inclusive)
    var topMargin: Int = 0
    var bottomMargin: Int = rows - 1

    // Saved cursor states
    private var savedPrimaryRow = 0
    private var savedPrimaryCol = 0
    private var savedAltRow = 0
    private var savedAltCol = 0

    val scrollbackSize: Int
        get() = scrollback.size

    val activeRows: Int
        get() = rows

    val activeCols: Int
        get() = cols

    init {
        resetMargins()
    }

    private fun createScreen(r: Int, c: Int): Array<Array<TerminalChar>> {
        return Array(r) { Array(c) { TerminalChar() } }
    }

    private fun getActiveScreen(): Array<Array<TerminalChar>> {
        return if (isAlternateScreen) alternateScreen else primaryScreen
    }

    fun resetMargins() {
        topMargin = 0
        bottomMargin = rows - 1
    }

    fun setMargins(top: Int, bottom: Int) {
        val safeTop = max(0, min(top, rows - 1))
        val safeBottom = max(safeTop, min(bottom, rows - 1))
        topMargin = safeTop
        bottomMargin = safeBottom
        cursorRow = safeTop
        cursorCol = 0
    }

    fun enterAlternateScreen() {
        if (!isAlternateScreen) {
            savedPrimaryRow = cursorRow
            savedPrimaryCol = cursorCol
            isAlternateScreen = true
            clearScreen(2)
            cursorRow = 0
            cursorCol = 0
            resetMargins()
        }
    }

    fun exitAlternateScreen() {
        if (isAlternateScreen) {
            isAlternateScreen = false
            cursorRow = min(rows - 1, savedPrimaryRow)
            cursorCol = min(cols - 1, savedPrimaryCol)
            resetMargins()
        }
    }

    fun saveCursor() {
        if (isAlternateScreen) {
            savedAltRow = cursorRow
            savedAltCol = cursorCol
        } else {
            savedPrimaryRow = cursorRow
            savedPrimaryCol = cursorCol
        }
    }

    fun restoreCursor() {
        if (isAlternateScreen) {
            cursorRow = min(rows - 1, savedAltRow)
            cursorCol = min(cols - 1, savedAltCol)
        } else {
            cursorRow = min(rows - 1, savedPrimaryRow)
            cursorCol = min(cols - 1, savedPrimaryCol)
        }
    }

    fun putChar(ch: Char, fg: Int, bg: Int, flags: Int) {
        if (cursorRow !in 0 until rows) {
            cursorRow = cursorRow.coerceIn(0, rows - 1)
        }
        if (cursorCol >= cols) {
            // Automatic line wrap
            cursorCol = 0
            lineFeed()
        }

        val screen = getActiveScreen()
        val cell = screen[cursorRow][cursorCol]
        cell.char = ch
        cell.fg = fg
        cell.bg = bg
        cell.flags = flags

        cursorCol++
    }

    fun carriageReturn() {
        cursorCol = 0
    }

    fun lineFeed() {
        if (cursorRow == bottomMargin) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun backspace() {
        cursorCol = max(0, cursorCol - 1)
    }

    fun tab() {
        val nextTab = ((cursorCol / 8) + 1) * 8
        cursorCol = min(cols - 1, nextTab)
    }

    fun moveCursor(row: Int, col: Int) {
        cursorRow = max(0, min(rows - 1, row))
        cursorCol = max(0, min(cols - 1, col))
    }

    fun moveCursorRelative(deltaRow: Int, deltaCol: Int) {
        cursorRow = max(0, min(rows - 1, cursorRow + deltaRow))
        cursorCol = max(0, min(cols - 1, cursorCol + deltaCol))
    }

    fun scrollUp(count: Int = 1) {
        val screen = getActiveScreen()
        for (step in 0 until count) {
            if (!isAlternateScreen && topMargin == 0) {
                // Save scrolled off top line to scrollback
                val copy = Array(cols) { colIdx ->
                    TerminalChar().apply { copyFrom(screen[topMargin][colIdx]) }
                }
                scrollback.add(copy)
                if (scrollback.size > maxScrollback) {
                    scrollback.removeAt(0)
                }
            }

            // Shift rows up in margin
            for (r in topMargin until bottomMargin) {
                for (c in 0 until cols) {
                    screen[r][c].copyFrom(screen[r + 1][c])
                }
            }
            // Clear bottom line in margin
            for (c in 0 until cols) {
                screen[bottomMargin][c].reset()
            }
        }
    }

    fun scrollDown(count: Int = 1) {
        val screen = getActiveScreen()
        for (step in 0 until count) {
            for (r in bottomMargin downTo topMargin + 1) {
                for (c in 0 until cols) {
                    screen[r][c].copyFrom(screen[r - 1][c])
                }
            }
            for (c in 0 until cols) {
                screen[topMargin][c].reset()
            }
        }
    }

    fun insertLines(count: Int) {
        if (cursorRow in topMargin..bottomMargin) {
            val oldTop = topMargin
            topMargin = cursorRow
            scrollDown(count)
            topMargin = oldTop
        }
    }

    fun deleteLines(count: Int) {
        if (cursorRow in topMargin..bottomMargin) {
            val oldTop = topMargin
            topMargin = cursorRow
            scrollUp(count)
            topMargin = oldTop
        }
    }

    fun deleteChars(count: Int) {
        if (cursorRow in 0 until rows) {
            val screen = getActiveScreen()
            val row = screen[cursorRow]
            val safeCount = min(count, cols - cursorCol)
            for (c in cursorCol until cols - safeCount) {
                row[c].copyFrom(row[c + safeCount])
            }
            for (c in cols - safeCount until cols) {
                row[c].reset()
            }
        }
    }

    fun eraseChars(count: Int) {
        if (cursorRow in 0 until rows) {
            val screen = getActiveScreen()
            val row = screen[cursorRow]
            val limit = min(cols, cursorCol + count)
            for (c in cursorCol until limit) {
                row[c].reset()
            }
        }
    }

    fun clearLine(mode: Int) {
        if (cursorRow !in 0 until rows) return
        val screen = getActiveScreen()
        val row = screen[cursorRow]
        when (mode) {
            0 -> { // From cursor to end of line
                for (c in cursorCol until cols) row[c].reset()
            }
            1 -> { // From start of line to cursor
                for (c in 0..min(cols - 1, cursorCol)) row[c].reset()
            }
            2 -> { // Entire line
                for (c in 0 until cols) row[c].reset()
            }
        }
    }

    fun clearScreen(mode: Int) {
        val screen = getActiveScreen()
        when (mode) {
            0 -> { // Cursor to end of screen
                clearLine(0)
                for (r in cursorRow + 1 until rows) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
            }
            1 -> { // Start of screen to cursor
                for (r in 0 until cursorRow) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
                clearLine(1)
            }
            2, 3 -> { // Entire screen (3 also clears scrollback)
                for (r in 0 until rows) {
                    for (c in 0 until cols) screen[r][c].reset()
                }
                if (mode == 3 && !isAlternateScreen) {
                    scrollback.clear()
                }
            }
        }
    }

    /**
     * Retrieves row for drawing.
     * scrollOffset: 0 means bottom of screen. > 0 means scrolled up into scrollback.
     */
    fun getLine(rowIdx: Int, scrollOffset: Int = 0): Array<TerminalChar> {
        val screen = getActiveScreen()
        if (scrollOffset == 0 || isAlternateScreen) {
            return if (rowIdx in 0 until rows) screen[rowIdx] else emptyRow()
        }

        // Scrolled into history
        val totalLines = scrollback.size + rows
        val targetIdx = totalLines - rows - scrollOffset + rowIdx
        return if (targetIdx in 0 until scrollback.size) {
            scrollback[targetIdx]
        } else {
            val screenIdx = targetIdx - scrollback.size
            if (screenIdx in 0 until rows) screen[screenIdx] else emptyRow()
        }
    }

    private fun emptyRow(): Array<TerminalChar> {
        return Array(cols) { TerminalChar() }
    }

    /**
     * Resizes the screen matrix while preserving existing text where possible.
     */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0 || (newRows == rows && newCols == cols)) return

        val newPrimary = createScreen(newRows, newCols)
        val copyRows = min(rows, newRows)
        val copyCols = min(cols, newCols)

        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                newPrimary[r][c].copyFrom(primaryScreen[r][c])
            }
        }

        val newAlt = createScreen(newRows, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                newAlt[r][c].copyFrom(alternateScreen[r][c])
            }
        }

        primaryScreen = newPrimary
        alternateScreen = newAlt
        rows = newRows
        cols = newCols
        cursorRow = min(rows - 1, cursorRow)
        cursorCol = min(cols - 1, cursorCol)
        resetMargins()
    }

    /**
     * Copies all visible/scrollback text as plain string.
     */
    fun getAllText(): String {
        val sb = StringBuilder()
        for (line in scrollback) {
            var lastChar = cols - 1
            while (lastChar >= 0 && line[lastChar].char == ' ') lastChar--
            for (c in 0..lastChar) {
                sb.append(line[c].char)
            }
            sb.append("\n")
        }
        val screen = getActiveScreen()
        for (r in 0 until rows) {
            var lastChar = cols - 1
            while (lastChar >= 0 && screen[r][lastChar].char == ' ') lastChar--
            for (c in 0..lastChar) {
                sb.append(screen[r][c].char)
            }
            if (r < rows - 1) sb.append("\n")
        }
        return sb.toString()
    }
}

package com.hybridengine.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TabSession: Encapsulates an isolated shell session with its own
 * Linux process, independent terminal output history, and input buffer.
 */
class TabSession(
    val id: Int,
    var title: String,
    val processSession: LocalProcessSession,
    val emulator: TerminalEmulator = TerminalEmulator(TerminalBuffer(24, 80)),
    val lines: MutableList<List<AnsiParser.StyledText>> = mutableListOf(),
    val commandHistory: MutableList<String> = mutableListOf(),
    var currentInput: String = "",
    var cursorPosition: Int = 0,
    var scrollOffset: Float = 0f,
    var workingDir: String = "~",
    var isPinned: Boolean = false
)

/**
 * SessionManager: Coordinates multiple multi-tab terminal sessions,
 * background process execution, and seamless tab switching.
 */
class SessionManager(
    private val context: Context,
    val voidFS: VoidFS
) {
    private val ansiParser = AnsiParser()
    val sessions = mutableListOf<TabSession>()
    var activeIndex: Int = 0
        private set
    private var nextSessionId = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    var onSessionOutput: ((TabSession, String) -> Unit)? = null
    var onActiveSessionChanged: ((TabSession) -> Unit)? = null
    var onSessionsListChanged: (() -> Unit)? = null
    var onAllSessionsClosed: (() -> Unit)? = null

    fun getActiveSession(): TabSession? {
        if (sessions.isEmpty()) return null
        val safeIndex = activeIndex.coerceIn(0, sessions.size - 1)
        return sessions[safeIndex]
    }

    fun createSession(initialTitle: String? = null, initialWorkingDir: String? = null): TabSession {
        val id = nextSessionId++
        val title = initialTitle ?: "Tab $id"
        val config = TerminalConfig(context)
        val defaultDir = initialWorkingDir ?: config.defaultWorkingDir
        val workDirFile = java.io.File(defaultDir).let { if (it.exists() && it.isDirectory) it else voidFS.homeDir }

        var tabRef: TabSession? = null
        val processSession = LocalProcessSession(context, voidFS) { output ->
            tabRef?.let { tab ->
                tab.emulator.feed(output)
                mainHandler.post {
                    onSessionOutput?.invoke(tab, output)
                }
            }
        }
        processSession.onRawOutput = { bytes, len ->
            tabRef?.let { tab ->
                tab.emulator.feed(bytes, len)
                mainHandler.post {
                    onSessionOutput?.invoke(tab, "")
                }
            }
        }

        val newTab = TabSession(
            id = id,
            title = title,
            processSession = processSession,
            workingDir = workDirFile.absolutePath
        )
        tabRef = newTab

        newTab.emulator.onTitleChanged = { newTitle ->
            val idx = sessions.indexOf(newTab)
            if (idx in sessions.indices && newTitle.isNotBlank()) {
                renameSession(idx, newTitle)
            }
        }

        processSession.onExitRequested = {
            mainHandler.post {
                val idx = sessions.indexOf(newTab)
                if (idx in sessions.indices) {
                    closeSession(idx)
                }
            }
        }

        sessions.add(newTab)
        processSession.start(workDirFile)

        val newIndex = sessions.size - 1
        activeIndex = newIndex

        mainHandler.post {
            onActiveSessionChanged?.invoke(newTab)
            onSessionsListChanged?.invoke()
        }

        Log.i(TAG, "Created new terminal session [ID: $id, Title: $title]. Total active: ${sessions.size}")
        return newTab
    }

    fun renameSession(index: Int, newTitle: String) {
        if (index in sessions.indices && newTitle.isNotBlank()) {
            sessions[index].title = newTitle.trim()
            mainHandler.post {
                onSessionsListChanged?.invoke()
            }
        }
    }

    fun togglePinSession(index: Int) {
        if (index in sessions.indices) {
            sessions[index].isPinned = !sessions[index].isPinned
            mainHandler.post {
                onSessionsListChanged?.invoke()
            }
        }
    }

    fun switchToSession(index: Int) {
        if (index !in sessions.indices) return
        if (index == activeIndex && sessions.isNotEmpty()) return

        activeIndex = index
        val active = sessions[index]

        // Update working dir on switch
        try {
            val cwd = active.processSession.getWorkingDir()
            active.workingDir = cwd
        } catch (_: Exception) {}

        mainHandler.post {
            onActiveSessionChanged?.invoke(active)
            onSessionsListChanged?.invoke()
        }

        Log.i(TAG, "Switched to tab session [Index: $index, ID: ${active.id}]")
    }

    fun closeSession(index: Int) {
        if (index !in sessions.indices) return

        val sessionToClose = sessions[index]
        if (sessionToClose.isPinned) {
            Log.w(TAG, "Cannot close pinned tab [Index: $index, ID: ${sessionToClose.id}]")
            return
        }
        Log.i(TAG, "Closing terminal session [Index: $index, ID: ${sessionToClose.id}]")
        sessionToClose.processSession.destroy()
        sessions.removeAt(index)

        if (sessions.isEmpty()) {
            onAllSessionsClosed?.invoke()
        } else {
            val newIndex = (if (index >= sessions.size) sessions.size - 1 else index).coerceAtLeast(0)
            activeIndex = newIndex
            val active = sessions[newIndex]
            mainHandler.post {
                onActiveSessionChanged?.invoke(active)
                onSessionsListChanged?.invoke()
            }
        }
    }

    fun updateActiveWorkingDir(): String {
        val active = getActiveSession() ?: return voidFS.homeDir.absolutePath
        val cwd = active.processSession.getWorkingDir()
        active.workingDir = cwd

        // Format short title based on cwd (e.g. "Tab 1: storage")
        val shortName = when {
            cwd == voidFS.homeDir.absolutePath -> "~"
            cwd.startsWith(voidFS.homeDir.absolutePath + "/") -> cwd.removePrefix(voidFS.homeDir.absolutePath + "/")
            cwd == "/storage/emulated/0" -> "storage"
            cwd.startsWith("/storage/emulated/0/") -> cwd.removePrefix("/storage/emulated/0/")
            else -> java.io.File(cwd).name.ifEmpty { "/" }
        }
        active.title = "Tab ${active.id}: $shortName"

        mainHandler.post {
            onSessionsListChanged?.invoke()
        }
        return cwd
    }

    fun destroyAll() {
        Log.i(TAG, "Destroying all active terminal sessions (${sessions.size})")
        for (session in sessions) {
            try {
                session.processSession.destroy()
            } catch (_: Exception) {}
        }
        sessions.clear()
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}

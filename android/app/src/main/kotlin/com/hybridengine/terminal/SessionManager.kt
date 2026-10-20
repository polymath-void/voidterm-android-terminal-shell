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
    val lines: MutableList<List<AnsiParser.StyledText>> = mutableListOf(),
    val commandHistory: MutableList<String> = mutableListOf(),
    var currentInput: String = "",
    var cursorPosition: Int = 0,
    var scrollOffset: Float = 0f,
    var workingDir: String = "~"
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

    fun getActiveSession(): TabSession? {
        if (sessions.isEmpty()) return null
        val safeIndex = activeIndex.coerceIn(0, sessions.size - 1)
        return sessions[safeIndex]
    }

    fun createSession(initialTitle: String? = null): TabSession {
        val id = nextSessionId++
        val title = initialTitle ?: "Tab $id"

        var tabRef: TabSession? = null
        val processSession = LocalProcessSession(context, voidFS) { output ->
            tabRef?.let { tab ->
                val styled = ansiParser.parseLines(output)
                synchronized(tab.lines) {
                    tab.lines.addAll(styled)
                    if (tab.lines.size > 1000) {
                        tab.lines.subList(0, tab.lines.size - 1000).clear()
                    }
                }
                mainHandler.post {
                    onSessionOutput?.invoke(tab, output)
                }
            }
        }

        val newTab = TabSession(
            id = id,
            title = title,
            processSession = processSession,
            workingDir = voidFS.homeDir.absolutePath
        )
        tabRef = newTab

        sessions.add(newTab)
        processSession.start()

        val newIndex = sessions.size - 1
        activeIndex = newIndex

        mainHandler.post {
            onActiveSessionChanged?.invoke(newTab)
            onSessionsListChanged?.invoke()
        }

        Log.i(TAG, "Created new terminal session [ID: $id, Title: $title]. Total active: ${sessions.size}")
        return newTab
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
        Log.i(TAG, "Closing terminal session [Index: $index, ID: ${sessionToClose.id}]")
        sessionToClose.processSession.destroy()
        sessions.removeAt(index)

        if (sessions.isEmpty()) {
            createSession("Tab 1")
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

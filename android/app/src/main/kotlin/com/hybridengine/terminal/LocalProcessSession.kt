package com.hybridengine.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * LocalProcessSession: Manages real Linux / Android process execution,
 * piping bidirectional stdin/stdout/stderr directly to TerminalSurfaceView.
 */
class LocalProcessSession(
    private val context: Context,
    val voidFS: VoidFS,
    private val onOutput: (String) -> Unit
) {
    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private val ioExecutor = Executors.newFixedThreadPool(2)
    @Volatile private var isRunning = false
    private var lastWorkingDir: String = voidFS.homeDir.absolutePath

    fun start(workingDir: File = voidFS.homeDir) {
        if (isRunning && process?.isAlive == true) return

        val shellBinary = resolveDefaultShell()
        val dir = if (workingDir.exists()) workingDir else context.filesDir
        lastWorkingDir = dir.absolutePath
        Log.i(TAG, "Starting shell process: $shellBinary in ${dir.absolutePath}")

        val commandList = voidFS.buildExecutionCommand(shellBinary)
        val pb = ProcessBuilder(commandList)
        pb.directory(dir)

        val env = pb.environment()
        val filesPath = context.filesDir.absolutePath
        val usrPath = "$filesPath/usr"
        val homePath = "$filesPath/home"
        val tmpPath = "$usrPath/tmp"

        env["HOME"] = homePath
        env["PREFIX"] = usrPath
        env["TMPDIR"] = tmpPath
        env["ENV"] = "$homePath/.bashrc"
        env["TERM"] = "xterm-256color"
        env["SHELL"] = shellBinary
        env["LD_LIBRARY_PATH"] = "$usrPath/lib"
        env["PATH"] = "$usrPath/bin:/system/bin:/system/xbin"
        env["VOIDTERM_VERSION"] = "1.0.0-alpha"

        // Ensure executable permissions on all usr/bin scripts
        File(usrPath, "bin").listFiles()?.forEach { file ->
            try {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            } catch (_: Exception) {}
        }

        try {
            val proc = pb.start()
            process = proc
            isRunning = true
            stdinWriter = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)

            // Asynchronous stdout reader
            ioExecutor.execute {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                    val buffer = CharArray(1024)
                    var readChars = 0
                    while (isRunning && reader.read(buffer).also { readChars = it } != -1) {
                        onOutput(String(buffer, 0, readChars))
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "stdout reader closed: ${e.message}")
                }
            }

            // Asynchronous stderr reader
            ioExecutor.execute {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
                    val buffer = CharArray(1024)
                    var readChars = 0
                    while (isRunning && reader.read(buffer).also { readChars = it } != -1) {
                        onOutput(String(buffer, 0, readChars))
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "stderr reader closed: ${e.message}")
                }
            }

            onOutput("\r\n\u001b[32m🚀 VoidTerm Shell Host Active\u001b[0m\r\n")
            val engineType = "Rootless POSIX Container (Termux-Architecture)"
            onOutput("\u001b[36mEngine:\u001b[0m $engineType | \u001b[35mShell:\u001b[0m $shellBinary\r\n")
            onOutput("\u001b[33mType 'help' or 'void' to begin.\u001b[0m\r\n\r\n")

        } catch (e: Exception) {
            Log.e(TAG, "Failed spawning shell process", e)
            onOutput("\r\n\u001b[31m[ERROR] Failed to spawn shell: ${e.message}\u001b[0m\r\n")
        }
    }

    fun write(command: String) {
        val writer = stdinWriter
        if (writer == null || process?.isAlive != true) {
            // Auto-restart if exited
            start(File(lastWorkingDir))
        }
        try {
            stdinWriter?.apply {
                write(command)
                if (!command.endsWith("\n")) {
                    write("\n")
                }
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to process stdin", e)
            onOutput("\r\n\u001b[31m[Process Error] ${e.message}\u001b[0m\r\n")
        }
    }

    fun getWorkingDir(): String {
        val proc = process ?: return lastWorkingDir
        val pid = getProcessPid(proc) ?: return lastWorkingDir
        return try {
            val cwdFile = File("/proc/$pid/cwd")
            if (cwdFile.exists()) {
                val realPath = cwdFile.canonicalPath
                lastWorkingDir = realPath
                realPath
            } else {
                lastWorkingDir
            }
        } catch (_: Exception) {
            lastWorkingDir
        }
    }

    fun getProcessPid(proc: Process): Int? {
        return try {
            val field = proc.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(proc)
        } catch (_: Exception) {
            try {
                val method = proc.javaClass.getMethod("pid")
                val res = method.invoke(proc)
                (res as? Number)?.toInt()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isAlive(): Boolean = isRunning && process?.isAlive == true

    fun restart() {
        destroy()
        start(File(lastWorkingDir))
    }

    fun destroy() {
        isRunning = false
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        stdinWriter = null
    }

    private fun resolveDefaultShell(): String {
        val candidates = listOf(
            File(context.filesDir, "usr/bin/bash").absolutePath,
            File(context.filesDir, "usr/bin/sh").absolutePath,
            "/data/data/com.termux/files/usr/bin/bash",
            "/system/bin/sh"
        )
        return candidates.firstOrNull { File(it).canExecute() } ?: "/system/bin/sh"
    }

    companion object {
        private const val TAG = "LocalProcessSession"
    }
}

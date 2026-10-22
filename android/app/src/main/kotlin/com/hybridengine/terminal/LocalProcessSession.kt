package com.hybridengine.terminal

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * LocalProcessSession: Manages real Linux / Android process execution,
 * running over a true POSIX pseudo-terminal (PTY) when available, with
 * transparent fallback to standard process pipes.
 */
class LocalProcessSession(
    private val context: Context,
    val voidFS: VoidFS,
    private val onOutput: (String) -> Unit
) {
    var onRawOutput: ((ByteArray, Int) -> Unit)? = null
    var onExitRequested: (() -> Unit)? = null

    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private var ptyMasterFd: Int = -1
    private var ptyPid: Int = -1
    private var pfd: ParcelFileDescriptor? = null
    private val ioExecutor = Executors.newFixedThreadPool(3)
    @Volatile private var isRunning = false
    private var lastWorkingDir: String = voidFS.homeDir.absolutePath

    fun start(workingDir: File = voidFS.homeDir) {
        if (isRunning && isAlive()) return

        val shellBinary = resolveDefaultShell()
        val dir = if (workingDir.exists()) workingDir else context.filesDir
        lastWorkingDir = dir.absolutePath
        Log.i(TAG, "Starting shell process: $shellBinary in ${dir.absolutePath}")

        val filesPath = context.filesDir.absolutePath
        val usrPath = "$filesPath/usr"
        val homePath = "$filesPath/home"
        val tmpPath = "$usrPath/tmp"

        val envMap = mutableMapOf<String, String>()
        envMap["HOME"] = homePath
        envMap["PREFIX"] = usrPath
        envMap["TMPDIR"] = tmpPath
        envMap["USER"] = "voidterm"
        envMap["LOGNAME"] = "voidterm"
        envMap["HOSTNAME"] = "voidterm"
        envMap["PS1"] = "\\[\\e[1;32m\\]voidterm\\[\\e[0m\\]:\\[\\e[1;36m\\]\\w\\[\\e[0m\\]\\$ "
        envMap["ENV"] = "$homePath/.bashrc"
        envMap["TERM"] = "xterm-256color"
        envMap["COLORTERM"] = "truecolor"
        envMap["SHELL"] = shellBinary
        envMap["LD_LIBRARY_PATH"] = "$usrPath/lib"
        envMap["PATH"] = "$usrPath/bin:/system/bin:/system/xbin"
        envMap["TERMINFO"] = "$usrPath/share/terminfo"
        envMap["TERMINFO_DIRS"] = "$usrPath/share/terminfo:$usrPath/etc/terminfo"
        envMap["VOIDTERM_VERSION"] = "1.0.0-alpha"
        envMap["SSL_CERT_FILE"] = "$usrPath/etc/tls/cert.pem"
        envMap["GODEBUG"] = "netdns=cgo"

        // Ensure executable permissions on all usr/bin scripts
        File(usrPath, "bin").listFiles()?.forEach { file ->
            try {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            } catch (_: Exception) {}
        }

        // 1. Try Native POSIX PTY First (Guarantees isatty=true, termios, and full TUI support)
        if (isPtyLoaded) {
            try {
                val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
                val argsArray = if (shellBinary.endsWith("bash")) {
                    arrayOf("--login")
                } else {
                    arrayOf("-l")
                }
                val ptyRes = createSubprocessNative(shellBinary, argsArray, envArray, dir.absolutePath, 24, 80)
                if (ptyRes != null && ptyRes.size >= 2 && ptyRes[0] >= 0) {
                    ptyMasterFd = ptyRes[0]
                    ptyPid = ptyRes[1]
                    isRunning = true

                    val parcelFd = ParcelFileDescriptor.adoptFd(ptyMasterFd)
                    pfd = parcelFd
                    val inStream = FileInputStream(parcelFd.fileDescriptor)
                    val outStream = FileOutputStream(parcelFd.fileDescriptor)
                    stdinWriter = OutputStreamWriter(outStream, Charsets.UTF_8)

                    // Single unified PTY stream reader (stdout & stderr are multiplexed on PTY slave)
                    ioExecutor.execute {
                        try {
                            val buffer = ByteArray(4096)
                            var readBytes = 0
                            while (isRunning && inStream.read(buffer).also { readBytes = it } != -1) {
                                val rawCb = onRawOutput
                                if (rawCb != null) {
                                    rawCb(buffer, readBytes)
                                } else {
                                    onOutput(String(buffer, 0, readBytes, Charsets.UTF_8))
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning) Log.w(TAG, "PTY reader closed: ${e.message}")
                        }
                    }

                    // Background process exit monitor
                    ioExecutor.execute {
                        try {
                            val exitCode = waitForProcessNative(ptyPid)
                            Log.i(TAG, "Native PTY process $ptyPid exited with code $exitCode")
                            isRunning = false
                            onOutput("\r\n\u001b[33m[Process completed with code $exitCode]\u001b[0m\r\n")
                        } catch (e: Exception) {}
                    }

                    onOutput("\r\n\u001b[32m🚀 VoidTerm Shell Host Active (Native POSIX PTY Engine)\u001b[0m\r\n")
                    val engineType = "VoidCore Shell Active [AArch64] | Engine: VoidFS Dynamic POSIX Architecture"
                    onOutput("\u001b[36mEngine:\u001b[0m $engineType | \u001b[35mShell:\u001b[0m $shellBinary\r\n")
                    onOutput("\u001b[33mType 'help' or 'void' to begin.\u001b[0m\r\n\r\n")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native PTY spawn failed, falling back to ProcessBuilder: ${e.message}")
            }
        }

        // 2. Standard Pipe-based Process Fallback
        val commandList = voidFS.buildExecutionCommand(shellBinary)
        val pb = ProcessBuilder(commandList)
        pb.directory(dir)
        pb.environment().putAll(envMap)

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
            val engineType = "VoidCore Shell Active [AArch64] | Engine: VoidFS Dynamic POSIX Architecture"
            onOutput("\u001b[36mEngine:\u001b[0m $engineType | \u001b[35mShell:\u001b[0m $shellBinary\r\n")
            onOutput("\u001b[33mType 'help' or 'void' to begin.\u001b[0m\r\n\r\n")

        } catch (e: Exception) {
            Log.e(TAG, "Failed spawning shell process", e)
            onOutput("\r\n\u001b[31m[ERROR] Failed to spawn shell: ${e.message}\u001b[0m\r\n")
        }
    }

    fun write(command: String) {
        if (!isAlive()) {
            val cleanExit = when (command.trim()) {
                "exit", "quit" -> true
                else -> false
            }
            if (cleanExit) {
                onOutput("\r\n\u001b[33m[VoidTerm] Shell exit requested. Closing tab...\u001b[0m\r\n")
                destroy()
                onExitRequested?.invoke()
                return
            }
            start(File(lastWorkingDir))
        }
        try {
            stdinWriter?.apply {
                write(command)
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to process stdin", e)
            onOutput("\r\n\u001b[31m[Process Error] ${e.message}\u001b[0m\r\n")
        }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (!isRunning) return
        try {
            val parcelFd = pfd
            if (parcelFd != null) {
                val out = FileOutputStream(parcelFd.fileDescriptor)
                out.write(bytes, offset, length)
                out.flush()
            } else {
                process?.outputStream?.apply {
                    write(bytes, offset, length)
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw bytes to process stdin", e)
        }
    }

    fun getWorkingDir(): String {
        val pid = if (ptyPid > 0) ptyPid else (process?.let { getProcessPid(it) } ?: return lastWorkingDir)
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

    fun isAlive(): Boolean {
        if (!isRunning) return false
        if (ptyPid > 0) {
            if (isPtyLoaded) {
                return try {
                    val status = checkProcessStatusNative(ptyPid)
                    if (status == -999) {
                        true
                    } else {
                        isRunning = false
                        false
                    }
                } catch (_: Exception) {
                    true
                }
            }
            return true
        }
        return process?.isAlive == true
    }

    fun resizePty(rows: Int, cols: Int) {
        if (ptyMasterFd >= 0 && isPtyLoaded) {
            try {
                setPtyWindowSizeNative(ptyMasterFd, rows, cols)
            } catch (e: Exception) {
                Log.w(TAG, "Failed resizing PTY: ${e.message}")
            }
        }
    }

    fun sendSignalSigInt() {
        if (ptyMasterFd >= 0) {
            // Under POSIX PTY, writing ETX (0x03) sends SIGINT to the foreground process group
            try {
                stdinWriter?.apply {
                    write("\u0003")
                    flush()
                }
            } catch (_: Exception) {}
        }

        val pid = if (ptyPid > 0) ptyPid else (process?.let { getProcessPid(it) } ?: return)
        try {
            // Also probe child processes in procfs
            val childrenFile = File("/proc/$pid/task/$pid/children")
            val childPids = if (childrenFile.exists()) {
                childrenFile.readText().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            } else {
                emptyList()
            }

            if (childPids.isNotEmpty()) {
                for (cpid in childPids) {
                    try {
                        ProcessBuilder("kill", "-2", cpid).start().waitFor()
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "SIGINT sent to active child process(es): ${childPids.joinToString()}")
            } else {
                val pb = ProcessBuilder("kill", "-2", "-$pid")
                val p = pb.start()
                val code = p.waitFor()
                if (code != 0) {
                    val pbFallback = ProcessBuilder("kill", "-2", "$pid")
                    pbFallback.start().waitFor()
                }
                Log.i(TAG, "SIGINT sent to shell PID=$pid")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send SIGINT: ${e.message}")
        }
    }

    fun restart() {
        destroy()
        start(File(lastWorkingDir))
    }

    fun destroy() {
        isRunning = false
        if (ptyPid > 0) {
            try {
                ProcessBuilder("kill", "-9", "$ptyPid").start()
            } catch (_: Exception) {}
            ptyPid = -1
        }
        if (ptyMasterFd >= 0) {
            try {
                pfd?.close()
            } catch (_: Exception) {}
            ptyMasterFd = -1
        }
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        pfd = null
        stdinWriter = null
    }

    private fun resolveDefaultShell(): String {
        val candidates = listOf(
            File(context.filesDir, "usr/bin/bash").absolutePath,
            File(context.filesDir, "usr/bin/sh").absolutePath,
            "/system/bin/sh"
        )
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.canExecute() && testBinaryExecution(candidate)) {
                return candidate
            }
        }
        return "/system/bin/sh"
    }

    private fun testBinaryExecution(binaryPath: String): Boolean {
        if (binaryPath == "/system/bin/sh") return true
        return try {
            val usrLib = File(context.filesDir, "usr/lib").absolutePath
            val usrBin = File(context.filesDir, "usr/bin").absolutePath
            val pb = ProcessBuilder(binaryPath, "-c", "exit 0")
            pb.environment()["LD_LIBRARY_PATH"] = usrLib
            pb.environment()["PATH"] = "$usrBin:/system/bin:/system/xbin"
            val p = pb.start()
            val code = p.waitFor()
            Log.i(TAG, "testBinaryExecution for $binaryPath result: $code")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "testBinaryExecution for $binaryPath failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "LocalProcessSession"
        private var isPtyLoaded = false

        init {
            try {
                System.loadLibrary("void_pty")
                isPtyLoaded = true
                Log.i(TAG, "Native POSIX PTY engine (libvoid_pty.so) loaded successfully.")
            } catch (e: Throwable) {
                try {
                    System.load("/data/data/com.hybridengine.terminal/files/usr/lib/libvoid_pty.so")
                    isPtyLoaded = true
                    Log.i(TAG, "Loaded libvoid_pty.so from /files/usr/lib/")
                } catch (e2: Throwable) {
                    Log.w(TAG, "libvoid_pty.so not loaded, pipe fallback active: ${e.message}; fallback: ${e2.message}")
                }
            }
        }

        @JvmStatic
        private external fun createSubprocessNative(
            cmd: String,
            args: Array<String>,
            envp: Array<String>,
            cwd: String,
            rows: Int,
            cols: Int
        ): IntArray?

        @JvmStatic
        private external fun setPtyWindowSizeNative(masterFd: Int, rows: Int, cols: Int): Int

        @JvmStatic
        private external fun waitForProcessNative(pid: Int): Int

        @JvmStatic
        private external fun checkProcessStatusNative(pid: Int): Int
    }
}

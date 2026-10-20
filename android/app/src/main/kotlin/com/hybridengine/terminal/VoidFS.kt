package com.hybridengine.terminal

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * VoidFS: Rootless User-space POSIX Filesystem Architecture for Android.
 * Operates completely without root/su, identically to Termux.
 * Manages the FHS tree (usr/bin, usr/lib, usr/etc, usr/tmp, home) and
 * shared external storage symlinks.
 */
class VoidFS(private val context: Context) {

    val filesDir: File = context.filesDir

    // POSIX FHS directories
    val homeDir: File = File(filesDir, "home")
    val usrDir: File = File(filesDir, "usr")
    val usrBinDir: File = File(filesDir, "usr/bin")
    val usrLibDir: File = File(filesDir, "usr/lib")
    val etcDir: File = File(filesDir, "usr/etc")
    val tmpDir: File = File(filesDir, "usr/tmp")

    // Storage bridge
    val storageDir: File = File(homeDir, "storage")

    fun setupFilesystem(): Boolean {
        // 1. Ensure basic POSIX directory tree exists
        listOf(homeDir, usrDir, usrBinDir, usrLibDir, etcDir, tmpDir, storageDir).forEach {
            if (!it.exists()) it.mkdirs()
        }

        // 2. Setup Termux-style storage symlinks in $HOME/storage/
        setupStorageSymlinks()

        Log.i(TAG, "Rootless POSIX VoidFS initialized at ${filesDir.absolutePath}")
        return true
    }

    private fun setupStorageSymlinks() {
        try {
            val externalRoot = Environment.getExternalStorageDirectory()
            val links = mapOf(
                "shared" to externalRoot,
                "downloads" to File(externalRoot, "Download"),
                "dcim" to File(externalRoot, "DCIM"),
                "pictures" to File(externalRoot, "Pictures"),
                "music" to File(externalRoot, "Music"),
                "movies" to File(externalRoot, "Movies")
            )

            for ((linkName, target) in links) {
                val linkFile = File(storageDir, linkName)
                if (!linkFile.exists()) {
                    try {
                        android.system.Os.symlink(target.absolutePath, linkFile.absolutePath)
                    } catch (_: Exception) {}
                }
            }

            // Also maintain legacy $HOME/sdcard symlink for convenience
            val legacySdcard = File(homeDir, "sdcard")
            if (!legacySdcard.exists()) {
                try {
                    android.system.Os.symlink(externalRoot.absolutePath, legacySdcard.absolutePath)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Storage symlinks notice: ${e.message}")
        }
    }

    /**
     * Builds execution command for the target shell without requiring root.
     */
    fun buildExecutionCommand(shellBinary: String): List<String> {
        return listOf(shellBinary)
    }

    companion object {
        const val TAG = "VoidFS"
    }
}

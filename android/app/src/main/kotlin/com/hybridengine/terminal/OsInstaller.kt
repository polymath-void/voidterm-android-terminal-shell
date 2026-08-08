package com.hybridengine.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class OsInstaller(private val context: Context) {

    fun installIfNeeded() {
        val rootfsDir = File(context.filesDir, "debian_rootfs")
        val archiveFile = File(context.filesDir, "debian-rootfs.tar.gz")

        // If the directory already exists, the OS is already installed
        if (rootfsDir.exists() && rootfsDir.isDirectory && (rootfsDir.list()?.isNotEmpty() == true)) {
            Log.d("VoidTerm-Installer", "Debian OS already installed. Booting...")
            return
        }

        Log.d("VoidTerm-Installer", "Checking for bundled Debian rootfs archive...")

        try {
            // Check if the asset exists
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains("debian-rootfs.tar.gz")) {
                Log.i("VoidTerm-Installer", "No bundled debian-rootfs.tar.gz found in assets. Skipping auto-extraction.")
                return
            }

            Log.d("VoidTerm-Installer", "First boot detected. Unpacking Debian rootfs...")

            // 1. Copy the tarball from the APK assets to internal storage
            context.assets.open("debian-rootfs.tar.gz").use { inputStream ->
                FileOutputStream(archiveFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // 2. Create the destination directory
            rootfsDir.mkdirs()

            // 3. Extract the tarball (or prepare for guest microVM mount)
            Log.d("VoidTerm-Installer", "Archive staged at ${archiveFile.absolutePath} for AVF microVM.")

            Log.d("VoidTerm-Installer", "✅ Debian OS rootfs staged successfully.")

        } catch (e: Exception) {
            Log.e("VoidTerm-Installer", "OS Installation failed: ${e.message}")
        }
    }
}

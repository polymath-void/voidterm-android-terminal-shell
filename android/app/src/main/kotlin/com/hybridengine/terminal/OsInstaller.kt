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
            // Check if rootfs asset exists (.tar or .tar.gz)
            val tarAsset = assetList.firstOrNull { it.startsWith("debian-rootfs.tar") }
            if (tarAsset != null) {
                Log.d("VoidTerm-Installer", "First boot detected. Unpacking Debian rootfs ($tarAsset)...")
                context.assets.open(tarAsset).use { inputStream ->
                    FileOutputStream(archiveFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                rootfsDir.mkdirs()
                Log.d("VoidTerm-Installer", "✅ Debian OS rootfs staged successfully.")
            } else {
                Log.i("VoidTerm-Installer", "No bundled debian-rootfs found in assets. Skipping auto-extraction.")
            }

            // 2. Copy the static guest_daemon binary if bundled in assets
            if (assetList.contains("guest_daemon")) {
                val guestDaemonFile = File(context.filesDir, "guest_daemon")
                context.assets.open("guest_daemon").use { inputStream ->
                    FileOutputStream(guestDaemonFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                guestDaemonFile.setExecutable(true, false)
                Log.d("VoidTerm-Installer", "✅ Static guest_daemon staged at ${guestDaemonFile.absolutePath}")
            }

            Log.d("VoidTerm-Installer", "Archive staged at ${archiveFile.absolutePath} for AVF microVM.")

        } catch (e: Exception) {
            Log.e("VoidTerm-Installer", "OS Installation failed: ${e.message}")
        }
    }
}

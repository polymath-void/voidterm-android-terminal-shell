package com.hybridengine.terminal

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * DynamicCoreLoader: Manages the instant-editable POSIX filesystem hierarchy,
 * runtime JSON configurations, and dynamic CLI commands.
 */
object DynamicCoreLoader {

    private const val TAG = "DynamicCoreLoader"
    var onConfigReloaded: (() -> Unit)? = null

    fun initialize(context: Context) {
        val filesDir = context.filesDir
        val usrBin = File(filesDir, "usr/bin").apply { mkdirs() }
        val usrEtc = File(filesDir, "usr/etc").apply { mkdirs() }
        val usrTmp = File(filesDir, "usr/tmp").apply { mkdirs() }
        val homeDir = File(filesDir, "home").apply { mkdirs() }

        // 1. Create or load runtime configuration
        val configFile = File(usrEtc, "voidterm.json")
        if (!configFile.exists()) {
            val defaultConfig = JSONObject().apply {
                put("theme", "DRACULA")
                put("font_size", 34.0)
                put("cursor_style", "BLOCK")
                put("default_shell", "/system/bin/sh")
                put("motd", "Welcome to VoidTerm Instant Runtime Container.")
            }
            try {
                FileOutputStream(configFile).use {
                    it.write(defaultConfig.toString(2).toByteArray(Charsets.UTF_8))
                }
                Log.i(TAG, "Created default voidterm.json at ${configFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating voidterm.json: ${e.message}")
            }
        }

        // 2. Deploy starter executable scripts in usr/bin
        deployScript(
            File(usrBin, "void"),
            """
            #!/system/bin/sh
            case "${'$'}1" in
              "pkg")
                shift
                echo "\033[36m[VoidPkg]\033[0m Package directive: ${'$'}*"
                ;;
              "reload")
                echo "\033[35m[VoidTerm]\033[0m Broadcasting RELOAD_CORE intent..."
                am broadcast -a com.hybridengine.terminal.RELOAD_CORE
                ;;
              "status")
                echo "\033[32m[VoidTerm Status]\033[0m"
                echo "  Kernel: ${'$'}(uname -a)"
                echo "  User: ${'$'}(id)"
                echo "  Prefix: ${'$'}PREFIX"
                echo "  Home: ${'$'}HOME"
                ;;
              "version"|"-v"|"--version")
                echo "VoidTerm v0.1.0-alpha (Instant Runtime Host)"
                ;;
              *)
                echo "Usage: void [pkg|reload|status|version]"
                ;;
            esac
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "pkg"),
            """
            #!/system/bin/sh
            ACTION="${'$'}1"
            PKG="${'$'}2"
            case "${'$'}ACTION" in
              "update"|"upgrade")
                echo "\033[36m[VoidPkg]\033[0m Updating package: ${'$'}PKG"
                ;;
              "install"|"add")
                echo "\033[32m[VoidPkg]\033[0m Installing package: ${'$'}PKG"
                ;;
              "list")
                echo "\033[33m[VoidPkg Installed Packages]\033[0m"
                echo "  - void-core (v0.1.0)"
                echo "  - nodeos (dynamic linker)"
                echo "  - jage (semantic AST versioning)"
                ;;
              *)
                echo "Usage: pkg [install|update|list] <package_name>"
                ;;
            esac
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "reload"),
            """
            #!/system/bin/sh
            echo "\033[35m[VoidTerm]\033[0m Reloading runtime..."
            am broadcast -a com.hybridengine.terminal.RELOAD_CORE
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "voidfs"),
            """
            #!/system/bin/sh
            echo "\033[36m=== VoidFS Linux Kernel Architecture ===\033[0m"
            echo "Mount Namespaces: Supported (CLONE_NEWNS)"
            echo "OverlayFS Status: ${'$'}(grep overlay /proc/filesystems || echo 'unavailable')"
            echo "Active Mounts:"
            grep -E "overlay|tmpfs|debian" /proc/mounts || echo "  (Standard POSIX sandbox mounts active)"
            echo "Storage Bridge: /sdcard/VoidTerm -> ${'$'}HOME/sdcard"
            """.trimIndent()
        )

        // 3. Deploy default .bashrc in home if not present
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.exists()) {
            deployScript(
                bashrc,
                """
                export PS1="root@voidterm:\w# "
                export PATH="$usrBin:/system/bin:/system/xbin"
                export TERM="xterm-256color"
                alias ls="ls --color=auto"
                alias ll="ls -la"
                """.trimIndent()
            )
        }
    }

    private fun deployScript(file: File, content: String) {
        if (!file.exists()) {
            try {
                FileOutputStream(file).use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                file.setExecutable(true, false)
                file.setReadable(true, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error deploying script ${file.name}: ${e.message}")
            }
        }
    }

    fun loadConfig(context: Context): JSONObject {
        val configFile = File(context.filesDir, "usr/etc/voidterm.json")
        return if (configFile.exists()) {
            try {
                JSONObject(configFile.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    fun reload(context: Context) {
        Log.i(TAG, "Hot-reloading runtime core and settings.")
        initialize(context)
        val usrBin = File(context.filesDir, "usr/bin")
        if (usrBin.exists()) {
            usrBin.listFiles()?.forEach { file ->
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
        onConfigReloaded?.invoke()
    }
}

package com.hybridengine.terminal

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * DynamicCoreLoader: Manages the instant-editable POSIX filesystem hierarchy,
 * DNS resolver configuration, standard Linux coreutils applets, and runtime JSON configurations.
 */
object DynamicCoreLoader {

    private const val TAG = "DynamicCoreLoader"
    var onConfigReloaded: (() -> Unit)? = null

    fun initialize(context: Context) {
        val filesDir = context.filesDir
        val usrBin = File(filesDir, "usr/bin").apply { mkdirs() }
        val usrEtc = File(filesDir, "usr/etc").apply { mkdirs() }
        val usrLib = File(filesDir, "usr/lib").apply { mkdirs() }
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

        // 2. Deploy DNS Resolver (resolv.conf) & Local Hosts (hosts)
        val resolvConf = File(usrEtc, "resolv.conf")
        if (!resolvConf.exists()) {
            deployFile(
                resolvConf,
                """
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                nameserver 2001:4860:4860::8888
                nameserver 2606:4700:4700::1111
                """.trimIndent()
            )
        }

        val hostsFile = File(usrEtc, "hosts")
        if (!hostsFile.exists()) {
            deployFile(
                hostsFile,
                """
                127.0.0.1 localhost loopback
                ::1 localhost ip6-localhost ip6-loopback
                """.trimIndent()
            )
        }

        // 3. Deploy standard POSIX applets (cat, ls, grep, pwd, ping, ip, ifconfig, etc.)
        deployBaseApplets(usrBin)

        // 4. Deploy VoidPkg engine & configuration
        deployVoidPkg(context, usrBin, usrEtc)

        // 5. Deploy starter executable scripts in usr/bin
        deployScript(
            File(usrBin, "void"),
            """
            #!/system/bin/sh
            # VoidTerm Core CLI Router
            SELF_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            PREFIX="${'$'}(cd "${'$'}SELF_DIR/.." && pwd)"

            case "${'$'}1" in
              "pkg")
                shift
                "${'$'}SELF_DIR/pkg" "${'$'}@"
                ;;
              "reload")
                echo "\033[35m[VoidTerm]\033[0m Broadcasting RELOAD_CORE intent..."
                am broadcast -a com.hybridengine.terminal.RELOAD_CORE
                ;;
              "status")
                echo "\033[36m================================================\033[0m"
                echo "\033[32m✔ VoidTerm Instant Simulator Container Status\033[0m"
                echo "  Kernel     : ${'$'}(uname -a)"
                echo "  User       : ${'$'}(id)"
                echo "  Device     : ${'$'}(getprop ro.product.model) (${'$'}(getprop ro.product.cpu.abi))"
                echo "  Android OS : ${'$'}(getprop ro.build.version.release) (API ${'$'}(getprop ro.build.version.sdk))"
                echo "  Prefix     : ${'$'}PREFIX"
                echo "  Binaries   : ${'$'}(ls "${'$'}SELF_DIR" | wc -l) tools in PATH"
                echo "\033[36m================================================\033[0m"
                ;;
              "storage")
                echo "\033[33m=== VoidTerm Storage Links ===\033[0m"
                ls -l "${'$'}HOME/storage" 2>/dev/null || echo "Run: voidfs to inspect storage links"
                ;;
              "version"|"-v"|"--version")
                echo "VoidTerm v1.0.0-alpha (Rootless POSIX Simulator Container)"
                ;;
              *)
                echo "Usage: void [status|pkg|reload|storage|version]"
                ;;
            esac
            """.trimIndent(),
            overwrite = true
        )

        deployScript(
            File(usrBin, "pkg"),
            """
            #!/system/bin/sh
            # VoidTerm Package Manager (VoidPkg Router)
            SELF_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            PREFIX="${'$'}(cd "${'$'}SELF_DIR/.." && pwd)"

            # If VoidPkg Python engine is present, route to full universal package manager
            if [ -f "${'$'}PREFIX/bin/voidpkg.py" ] && [ -x "${'$'}PREFIX/bin/python3" ]; then
                export PREFIX="${'$'}PREFIX"
                export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
                export PYTHONHOME="${'$'}PREFIX"
                export PYTHONPATH="${'$'}PREFIX/lib/python3.14:${'$'}PREFIX/lib/python3.14/lib-dynload"
                export SSL_CERT_FILE="${'$'}PREFIX/etc/tls/cert.pem"
                exec "${'$'}PREFIX/bin/python3" "${'$'}PREFIX/bin/voidpkg.py" "${'$'}@"
            fi

            # Offline Fallback: Local MiniPkg Applet Linker
            BIN_DIR="${'$'}PREFIX/bin"
            LIB_DIR="${'$'}PREFIX/lib"
            ETC_DIR="${'$'}PREFIX/etc"
            VAR_DIR="${'$'}PREFIX/var/pkg"
            TOYBOX="/system/bin/toybox"

            mkdir -p "${'$'}VAR_DIR" "${'$'}BIN_DIR" "${'$'}LIB_DIR"

            COREUTILS_APPLETS="base64 basename cal cat chattr chmod chown cksum clear cmp comm cp cpio cut date dd df diff dirname dos2unix du echo env expand expr false file find flock fmt free getconf grep groups gunzip gzip head hostname id install ionice kill killall ln logname ls md5sum mkdir mkfifo mktemp mv nice nl nohup nproc od paste patch printenv printf ps pwd readlink realpath renice rev rm rmdir sed seq sha1sum sha224sum sha256sum sha384sum sha512sum sleep sort split stat strings sync tac tail tar taskset tee test time timeout touch tr true truncate tty uname uniq unix2dos unlink uptime vi watch wc which whoami xargs xxd yes zcat"
            NET_APPLETS="ifconfig nc netcat netstat ping ping6 traceroute"
            PROC_APPLETS="dmesg free iotop kill killall lsof pgrep pidof pkill pmap prlimit ps pwdx sysctl top uptime vmstat"
            DEV_APPLETS="cmp file od readelf strings xxd"

            install_applets() {
              pkg_name="${'$'}1"
              applets="${'$'}2"
              count=0
              echo "\033[36m[*] Installing package: ${'$'}pkg_name...\033[0m"
              for app in ${'$'}applets; do
                if [ -x "/system/bin/${'$'}app" ]; then
                  ln -sf "/system/bin/${'$'}app" "${'$'}BIN_DIR/${'$'}app"
                  count=${'$'}((count + 1))
                elif [ -x "${'$'}TOYBOX" ]; then
                  ln -sf "${'$'}TOYBOX" "${'$'}BIN_DIR/${'$'}app"
                  count=${'$'}((count + 1))
                fi
              done
              echo "${'$'}pkg_name" >> "${'$'}VAR_DIR/installed.list"
              sort -u "${'$'}VAR_DIR/installed.list" -o "${'$'}VAR_DIR/installed.list"
              echo "\033[32m[✔] ${'$'}pkg_name installed (${'$'}count binaries linked into ${'$'}BIN_DIR)\033[0m"
            }

            remove_applets() {
              pkg_name="${'$'}1"
              applets="${'$'}2"
              echo "\033[33m[*] Removing package: ${'$'}pkg_name...\033[0m"
              for app in ${'$'}applets; do
                if [ -L "${'$'}BIN_DIR/${'$'}app" ]; then
                  rm -f "${'$'}BIN_DIR/${'$'}app"
                fi
              done
              if [ -f "${'$'}VAR_DIR/installed.list" ]; then
                grep -v "^${'$'}pkg_name${'$'}" "${'$'}VAR_DIR/installed.list" > "${'$'}VAR_DIR/installed.list.tmp"
                mv "${'$'}VAR_DIR/installed.list.tmp" "${'$'}VAR_DIR/installed.list"
              fi
              echo "\033[32m[✔] ${'$'}pkg_name uninstalled.\033[0m"
            }

            case "${'$'}1" in
              "install"|"add")
                case "${'$'}2" in
                  "coreutils") install_applets "coreutils" "${'$'}COREUTILS_APPLETS" ;;
                  "net-tools")  install_applets "net-tools" "${'$'}NET_APPLETS" ;;
                  "proc-tools") install_applets "proc-tools" "${'$'}PROC_APPLETS" ;;
                  "dev-tools")  install_applets "dev-tools" "${'$'}DEV_APPLETS" ;;
                  "all"|"base")
                    install_applets "coreutils" "${'$'}COREUTILS_APPLETS"
                    install_applets "net-tools" "${'$'}NET_APPLETS"
                    install_applets "proc-tools" "${'$'}PROC_APPLETS"
                    install_applets "dev-tools" "${'$'}DEV_APPLETS"
                    ;;
                  *)
                    echo "\033[31m[!] Unknown package: ${'$'}2\033[0m"
                    echo "Available offline packages: coreutils, net-tools, proc-tools, dev-tools, all"
                    ;;
                esac
                ;;
              "uninstall"|"remove")
                case "${'$'}2" in
                  "coreutils") remove_applets "coreutils" "${'$'}COREUTILS_APPLETS" ;;
                  "net-tools")  remove_applets "net-tools" "${'$'}NET_APPLETS" ;;
                  "proc-tools") remove_applets "proc-tools" "${'$'}PROC_APPLETS" ;;
                  "dev-tools")  remove_applets "dev-tools" "${'$'}DEV_APPLETS" ;;
                  *) echo "Usage: pkg remove [coreutils|net-tools|proc-tools|dev-tools]" ;;
                esac
                ;;
              "list")
                echo "\033[36m=== VoidTerm Installed Packages ===\033[0m"
                if [ -f "${'$'}VAR_DIR/installed.list" ] && [ -s "${'$'}VAR_DIR/installed.list" ]; then
                  cat "${'$'}VAR_DIR/installed.list" | while read -r line; do
                    echo "  \033[32m✔\033[0m ${'$'}line"
                  done
                else
                  echo "  (No packages installed yet. Run: pkg install all)"
                fi
                echo ""
                echo "\033[33m=== Available Package Repositories ===\033[0m"
                echo "  - \033[1mcoreutils\033[0m  : 70+ essential POSIX utilities (grep, sed, awk, tar, gzip, find, wc...)"
                echo "  - \033[1mnet-tools\033[0m  : Networking utilities (nc, netcat, netstat, ping, ifconfig)"
                echo "  - \033[1mproc-tools\033[0m : Process inspectors (top, ps, kill, uptime, free, vmstat)"
                echo "  - \033[1mdev-tools\033[0m  : Binary inspection (xxd, strings, readelf, cmp, od)"
                echo "  - \033[1mall\033[0m        : Bootstrap the complete standard POSIX suite"
                ;;
              "status")
                echo "\033[36m=== VoidTerm System & Storage Status ===\033[0m"
                echo "  Prefix: ${'$'}PREFIX"
                echo "  Binaries in PATH: ${'$'}(ls "${'$'}BIN_DIR" | wc -l)"
                echo "  Storage Usage: ${'$'}(du -sh "${'$'}PREFIX" 2>/dev/null || echo 'unknown')"
                ;;
              *)
                echo "VoidTerm MiniPkg v1.0.0"
                echo "Usage: pkg [install|remove|list|status] [package_name]"
                ;;
            esac
            """.trimIndent(),
            overwrite = true
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
            File(usrBin, "help"),
            """
            #!/system/bin/sh
            echo "\033[36m--- VoidTerm Builtin Directives ---\033[0m"
            echo "  void       : Core control script (pkg, reload, status, version)"
            echo "  voidfs     : Inspect VoidFS rootless POSIX architecture"
            echo "  pkg        : Package manager interface (voidpkg)"
            echo "  ipconfig   : View network interfaces and IP addresses"
            echo "  reload     : Hot-reload /files/usr/bin/* scripts and voidterm.json"
            echo "  restart    : Restart the active shell session"
            echo "  storage/   : Access external storage (/sdcard) via ${'$'}HOME/storage"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "voidfs"),
            """
            #!/system/bin/sh
            echo "\033[36m=== VoidFS Rootless POSIX Architecture ===\033[0m"
            echo "Type: Rootless Termux-compatible FHS"
            echo "Prefix: ${'$'}PREFIX"
            echo "Home: ${'$'}HOME"
            echo "Storage Bridge: ${'$'}HOME/storage/shared -> /sdcard"
            echo "Active FHS Dirs:"
            ls -ld ${'$'}PREFIX/bin ${'$'}PREFIX/etc ${'$'}PREFIX/tmp ${'$'}HOME
            """.trimIndent()
        )

        // 5. Deploy default .bashrc in home if not present
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.exists()) {
            deployScript(
                bashrc,
                """
                export PREFIX="/data/user/0/com.hybridengine.terminal/files/usr"
                export HOME="/data/user/0/com.hybridengine.terminal/files/home"
                export TMPDIR="${'$'}PREFIX/tmp"
                export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
                export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
                export TERM="xterm-256color"
                export PS1="voidterm:\w$ "

                alias ll="ls -la"
                alias l="ls -CF"
                alias pkg="${'$'}PREFIX/bin/pkg"
                alias void="${'$'}PREFIX/bin/void"
                alias voidfs="${'$'}PREFIX/bin/voidfs"
                alias help="${'$'}PREFIX/bin/help"
                alias reload="${'$'}PREFIX/bin/reload"
                alias cls="clear"
                alias ipconfig="${'$'}PREFIX/bin/ipconfig"

                echo "\033[36m========================================\033[0m"
                echo "\033[32m✔ VoidTerm Rootless Container v1.0.0\033[0m"
                echo "  Prefix: ${'$'}PREFIX"
                echo "  Packages: ${'$'}(ls ${'$'}PREFIX/bin 2>/dev/null | wc -l) binaries active"
                echo "  Type 'help' or 'pkg list' to explore"
                echo "\033[36m========================================\033[0m"
                """.trimIndent()
            )
        }
    }

    private fun deployBaseApplets(usrBin: File) {
        val toybox = File("/system/bin/toybox")
        val hasToybox = toybox.exists() && toybox.canExecute()
        val applets = listOf(
            // Core POSIX file & text utilities
            "cat", "ls", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "pwd", "echo",
            "chmod", "chown", "grep", "egrep", "fgrep", "find", "sed", "awk", "head",
            "tail", "wc", "which", "uname", "whoami", "id", "date", "env", "printenv",
            "sleep", "kill", "killall", "ps", "top", "df", "du", "tar", "gzip", "gunzip",
            "clear", "cut", "sort", "uniq", "diff", "cmp", "file", "strings",
            "stat", "readlink", "realpath", "basename", "dirname", "tee", "xargs",
            "base64", "md5sum", "sha256sum", "test", "true", "false", "yes", "uptime",
            // Networking utilities
            "ifconfig", "ip", "ping", "netstat", "nc", "traceroute"
        )

        for (app in applets) {
            val dest = File(usrBin, app)
            if (!dest.exists()) {
                val sysBin = File("/system/bin/$app")
                val target = when {
                    sysBin.exists() && sysBin.canExecute() -> sysBin.absolutePath
                    hasToybox -> toybox.absolutePath
                    else -> null
                }
                if (target != null) {
                    try {
                        android.system.Os.symlink(target, dest.absolutePath)
                    } catch (e: Exception) {
                        try {
                            dest.writeText("#!/system/bin/sh\nexec $target $app \"\$@\"\n")
                            dest.setExecutable(true, false)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Deploy ipconfig script alias
        val ipconfig = File(usrBin, "ipconfig")
        if (!ipconfig.exists()) {
            try {
                ipconfig.writeText(
                    """
                    #!/system/bin/sh
                    # VoidTerm ipconfig utility
                    if [ -x "/system/bin/ifconfig" ]; then
                      /system/bin/ifconfig "$@"
                    elif [ -x "/system/bin/toybox" ]; then
                      /system/bin/toybox ifconfig "$@"
                    else
                      ip addr show "$@"
                    fi
                    """.trimIndent()
                )
                ipconfig.setExecutable(true, false)
            } catch (_: Exception) {}
        }
    }

    private fun deployFile(file: File, content: String) {
        if (!file.exists()) {
            try {
                FileOutputStream(file).use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deploying file ${file.name}: ${e.message}")
            }
        }
    }

    private fun deployVoidPkg(context: Context, usrBin: File, usrEtc: File) {
        val voidpkgDest = File(usrBin, "voidpkg.py")
        try {
            context.assets.open("voidpkg.py").use { input ->
                FileOutputStream(voidpkgDest).use { output ->
                    input.copyTo(output)
                }
            }
            voidpkgDest.setExecutable(true, false)
            voidpkgDest.setReadable(true, false)
            Log.i(TAG, "Successfully extracted voidpkg.py from assets.")
        } catch (e: Exception) {
            Log.w(TAG, "Notice: voidpkg.py extraction from assets: ${e.message}")
        }

        // Deploy TLS CA bundle if present in assets
        val tlsDir = File(usrEtc, "tls").apply { mkdirs() }
        val certDest = File(tlsDir, "cert.pem")
        if (!certDest.exists()) {
            try {
                context.assets.open("tls/cert.pem").use { input ->
                    FileOutputStream(certDest).use { output ->
                        input.copyTo(output)
                    }
                }
                certDest.setReadable(true, false)
                Log.i(TAG, "Successfully extracted tls/cert.pem from assets.")
            } catch (e: Exception) {
                Log.w(TAG, "Notice: tls/cert.pem extraction from assets: ${e.message}")
            }
        }

        // Deploy default repos.json
        val pkgEtc = File(usrEtc, "pkg").apply { mkdirs() }
        val reposJson = File(pkgEtc, "repos.json")
        if (!reposJson.exists()) {
            val defaultRepos = JSONObject().apply {
                put("default_provider", "termux")
                val providers = JSONObject().apply {
                    val termux = JSONObject().apply {
                        put("name", "Termux Main (AArch64)")
                        put("url", "https://packages-cf.termux.dev/apt/termux-main/")
                        put("packages_path", "dists/stable/main/binary-aarch64/Packages.gz")
                        put("enabled", true)
                    }
                    put("termux", termux)
                }
                put("providers", providers)
            }
            try {
                FileOutputStream(reposJson).use {
                    it.write(defaultRepos.toString(2).toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {}
        }
    }

    private fun deployScript(file: File, content: String, overwrite: Boolean = false) {
        if (!file.exists() || overwrite) {
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
                try {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                } catch (_: Exception) {}
            }
        }
        onConfigReloaded?.invoke()
    }
}

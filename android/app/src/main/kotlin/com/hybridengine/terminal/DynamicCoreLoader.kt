package com.hybridengine.terminal

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * DynamicCoreLoader: Manages the instant-editable POSIX filesystem hierarchy,
 * DNS resolver configuration, standard Linux coreutils applets, and runtime JSON configurations.
 */
object DynamicCoreLoader {

    private const val TAG = "DynamicCoreLoader"
    private const val CURRENT_BOOTSTRAP_VERSION = "1.0.4"
    var onConfigReloaded: (() -> Unit)? = null

    fun initialize(context: Context) {
        val filesDir = context.filesDir
        val usrBin = File(filesDir, "usr/bin").apply { mkdirs() }
        val usrEtc = File(filesDir, "usr/etc").apply { mkdirs() }
        val usrLib = File(filesDir, "usr/lib").apply { mkdirs() }
        val usrTmp = File(filesDir, "usr/tmp").apply { mkdirs() }
        val homeDir = File(filesDir, "home").apply { mkdirs() }

        // 0. Extract self-contained bootstrap rootfs (bash, python, patchelf, core libs) if present in assets
        unpackBootstrapIfNeeded(context, File(filesDir, "usr"))

        // Run self-repair scanner on shared libraries and binaries
        repairAndVerifySharedLibraries(usrLib)

        // 1. Create or load runtime configuration
        val configFile = File(usrEtc, "voidterm.json")
        if (!configFile.exists()) {
            val defaultConfig = JSONObject().apply {
                put("_guide", "VoidTerm Sovereign Configuration Guide: Adjust visual styling, keyboard layout, and POSIX defaults here.")
                put("theme", "DRACULA")
                put("_theme_options", "DRACULA, NORD, GRUVBOX, TOKYO_NIGHT, CYBERPUNK, MATERIAL_DARK, SOLARIZED_DARK, ONE_DARK, MONOKAI_PRO, MATRIX_GREEN")
                put("font_size", 34.0)
                put("font_family", "JetBrainsMono")
                put("_font_options", "JetBrainsMono, FiraCode, Monospace")
                put("padding_top_dp", 8)
                put("padding_bottom_dp", 8)
                put("padding_horizontal_dp", 12)
                put("cursor_style", "BLOCK")
                put("_cursor_style_options", "BLOCK, UNDERLINE, BAR")
                put("cursor_blink", true)
                put("cursor_blink_rate_ms", 500)
                put("default_shell", "/system/bin/sh")
                put("default_working_dir", "\$HOME")
                put("row1_limit", 8)
                put("row2_limit", 8)
                put("row1_unlocked", false)
                put("row2_unlocked", false)
                put("motd", "Welcome to VoidTerm Sovereign Architecture.")
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

        // Deploy configuration markdown comments guide
        val guideFile = File(usrEtc, "voidterm.json.guide.md")
        if (!guideFile.exists()) {
            deployFile(
                guideFile,
                """
                # VoidTerm Visual & System Configuration Guide
                
                This guide describes all customization properties supported in `voidterm.json`:
                
                ## Visual & Terminal Surface
                - `theme`: Active color theme preset. Options:
                  - `DRACULA` (Dracula vibrant dark)
                  - `NORD` (Nord Arctic blue palette)
                  - `GRUVBOX` (Retro groove earthy tones)
                  - `TOKYO_NIGHT` (Clean modern indigo)
                  - `CYBERPUNK` (Neon yellow & cyan high-contrast)
                  - `MATERIAL_DARK` (Standard Android dark palette)
                  - `SOLARIZED_DARK` (Low-contrast solarized dark)
                  - `ONE_DARK` (Atom One Dark syntax palette)
                  - `MONOKAI_PRO` (Monokai Pro developer palette)
                  - `MATRIX_GREEN` (Terminal phosphor green)
                - `font_size`: Terminal font size in points (e.g. 28.0 to 44.0).
                - `font_family`: Font asset name (`JetBrainsMono`, `FiraCode`, `Monospace`).
                - `padding_top_dp`, `padding_bottom_dp`, `padding_horizontal_dp`: Adjusts terminal canvas spacing.
                - `cursor_style`: Cursor glyph shape (`BLOCK`, `UNDERLINE`, `BAR`).
                - `cursor_blink`: Boolean toggle for cursor blinking animation.
                - `cursor_blink_rate_ms`: Cursor blink interval in milliseconds (default: 500ms).
                
                ## Shell & Workspace
                - `default_shell`: Absolute path to initial shell binary (default: `/system/bin/sh` or `${'$'}PREFIX/bin/bash`).
                - `default_working_dir`: Directory to open on app startup and in new tabs (e.g. `${'$'}HOME` or `~/Projects`).
                
                ## Keyboard Shortcuts & Row Budgeting
                - `row1_limit`, `row2_limit`: Number of keys visible per row before horizontal scroll (default: 8).
                - `row1_unlocked`, `row2_unlocked`: Set to `true` to enable smooth horizontal swipe scrolling for extra keys.
                
                ## MOTD & Welcome Banner
                - Configured in `${'$'}PREFIX/etc/motd.json`. Adjust `banner_title`, `welcome_message`, and `quick_hints`.
                """.trimIndent()
            )
        }

        // Deploy default motd.json
        val motdFile = File(usrEtc, "motd.json")
        if (!motdFile.exists()) {
            val defaultMotd = JSONObject().apply {
                put("show_motd", true)
                put("banner_title", "VoidCore Dynamic POSIX Shell [AArch64]")
                put("welcome_message", "Welcome to VoidTerm Sovereign Architecture")
                put("show_device_info", true)
                put("show_quick_hints", true)
                val hints = org.json.JSONArray().apply {
                    put("Type '/pkg' to open interactive package manager")
                    put("Type '/file' to launch visual VFS file browser")
                    put("Type '/hub' or tap 🧩 to open Extensions Hub")
                    put("Type 'void status' to inspect container state")
                }
                put("quick_hints", hints)
                put("default_working_dir", "\$HOME")
            }
            try {
                FileOutputStream(motdFile).use {
                    it.write(defaultMotd.toString(2).toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {}
        }

        // Ensure home storage shortcuts exist ($HOME/storage and $HOME/sdcard -> /sdcard)
        val homeStorage = File(homeDir, "storage")
        if (!homeStorage.exists()) {
            try {
                android.system.Os.symlink("/sdcard", homeStorage.absolutePath)
            } catch (_: Exception) {}
        }
        val homeSdcard = File(homeDir, "sdcard")
        if (!homeSdcard.exists()) {
            try {
                android.system.Os.symlink("/sdcard", homeSdcard.absolutePath)
            } catch (_: Exception) {}
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
              "ext")
                shift
                exec "${'$'}SELF_DIR/void-ext" "${'$'}@"
                ;;
              "reload")
                # Write filesystem trigger file instead of insecure broadcast intent
                TRIGGER_FILE="${'$'}PREFIX/var/run/.reload_trigger"
                mkdir -p "${'$'}(dirname "${'$'}TRIGGER_FILE")"
                touch "${'$'}TRIGGER_FILE"
                echo "\033[35m[VoidTerm]\033[0m Reload trigger written to filesystem."
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
            TRIGGER_FILE="${'$'}PREFIX/var/run/.reload_trigger"
            mkdir -p "${'$'}(dirname "${'$'}TRIGGER_FILE")"
            touch "${'$'}TRIGGER_FILE"
            """.trimIndent()
        )

        // VoidOS API Gateway CLI wrappers
        deployScript(
            File(usrBin, "void-clipboard"),
            """
            #!/system/bin/sh
            # VoidTerm Clipboard CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" clipboard.set "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-toast"),
            """
            #!/system/bin/sh
            # VoidTerm Toast CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" toast.set "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-notify"),
            """
            #!/system/bin/sh
            # VoidTerm Notification CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" notification.post "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-vibrate"),
            """
            #!/system/bin/sh
            # VoidTerm Vibrate CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" vibrator.vibrate "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-battery"),
            """
            #!/system/bin/sh
            # VoidTerm Battery Status CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" battery.status "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-wakelock"),
            """
            #!/system/bin/sh
            # VoidTerm WakeLock CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" power.wakelock_acquire "$@"
            """.trimIndent()
        )

        deployScript(
            File(usrBin, "void-intent"),
            """
            #!/system/bin/sh
            # VoidTerm Intent Launcher CLI Wrapper
            exec "${'$'}PREFIX/bin/voidpkg.py" intent.launch "$@"
            """.trimIndent()
        )

        // Visual directory listing applet
        deployScript(
            File(usrBin, "voidls"),
            """
            #!/system/bin/sh
            python3 "${'$'}PREFIX/bin/voidls.py" "$@"
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
            echo "\033[36m=== VoidFS Sovereign Rootless Architecture ===\033[0m"
            echo "Type: Sovereign Rootless POSIX Container Architecture (VoidFS)"
            echo "Prefix: ${'$'}PREFIX"
            echo "Home: ${'$'}HOME"
            echo "Storage Bridge: ${'$'}HOME/storage -> /sdcard"
            echo "Active FHS Dirs:"
            ls -ld ${'$'}PREFIX/bin ${'$'}PREFIX/etc ${'$'}PREFIX/tmp ${'$'}HOME
            """.trimIndent()
        )

        // 5. Deploy default .bashrc in home
        val bashrc = File(homeDir, ".bashrc")
        deployScript(
            bashrc,
            """
            export PREFIX="${'$'}(cd "${'$'}SELF_DIR/.." && pwd)"
            export HOME="${'$'}HOME"
            export TMPDIR="${'$'}PREFIX/tmp"
            export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
            export TERM="xterm-256color"

            # Dynamic prompt function: username in green, colon in white, directory in cyan, $ in yellow
            __void_prompt() {
                _u="${'$'}{USER:-${'$'}(whoami 2>/dev/null || echo voidterm)}"
                _d="${'$'}PWD"
                case "${'$'}_d" in
                    "${'$'}HOME") _d="~" ;;
                    "${'$'}HOME"/*) _d="~${'$'}{_d#${'$'}HOME}" ;;
                esac
                printf "\033[1;32m%s\033[0m:\033[1;36m%s\033[0m\033[1;33m$\033[0m " "${'$'}_u" "${'$'}_d"
            }
            export PS1='${'$'}(__void_prompt)'

            # cd wrapper with proper argument handling and PWD sync
            cd() {
                if [ "${'$'}#" -eq 0 ]; then
                    builtin cd "${'$'}HOME" || return ${'$'}?
                elif [ "${'$'}1" = "-" ]; then
                    builtin cd "${'$'}OLDPWD" || return ${'$'}?
                elif [ "${'$'}1" = "~" ]; then
                    builtin cd "${'$'}HOME" || return ${'$'}?
                elif [ "${'$'}{1#~/}" != "${'$'}1" ]; then
                    builtin cd "${'$'}HOME/${'$'}{1#~/}" || return ${'$'}?
                else
                    builtin cd "${'$'}@" || return ${'$'}?
                fi
                export PWD
            }

            # Visual directory listing with file icons and colors
            alias ls="python3 \"${'$'}PREFIX/bin/voidls.py\" 2>/dev/null || ls"
            alias ll="python3 \"${'$'}PREFIX/bin/voidls.py\" -l 2>/dev/null || ls -la"
            alias l="ls -CF"
            alias pkg="${'$'}PREFIX/bin/pkg"
            alias void="${'$'}PREFIX/bin/void"
            alias ext="${'$'}PREFIX/bin/void-ext"
            alias voidfs="${'$'}PREFIX/bin/voidfs"
            alias help="${'$'}PREFIX/bin/help"
            alias reload="${'$'}PREFIX/bin/reload"
            alias cls="clear"
            alias ipconfig="${'$'}PREFIX/bin/ipconfig"
            alias voidls="python3 \"${'$'}PREFIX/bin/voidls.py\""
            alias /pkg="void ext run pkg"
            alias /file="void ext run file"
            alias /hub="void ext list"
            alias /ext="void ext list"

            # MOTD from motd.json
            if [ -f "${'$'}PREFIX/etc/motd.json" ]; then
                python3 -c "
import json, os
try:
    with open(os.path.expandvars('${'$'}PREFIX/etc/motd.json')) as f:
        m = json.load(f)
    if m.get('show_motd', True):
        print('\033[36m========================================\033[0m')
        print('\033[1;32m✔ ' + m.get('banner_title', 'VoidCore Shell Active [AArch64]') + '\033[0m')
        print('  ' + m.get('welcome_message', 'Welcome to VoidTerm Sovereign Architecture'))
        if m.get('show_device_info', True):
            print('  Prefix: ' + os.environ.get('PREFIX', ''))
        for hint in m.get('quick_hints', []):
            print('  \033[33m•\033[0m ' + hint)
        print('\033[36m========================================\033[0m')
except Exception:
    pass
" 2>/dev/null
            fi
            """.trimIndent(),
            overwrite = true
        )

        // 6. Initialize extensions directory structure
        com.hybridengine.terminal.extensions.ExtensionManager.initExtensions(context)
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

        // Deploy universal void-ext CLI router
        val extDest = File(usrBin, "void-ext")
        try {
            context.assets.open("void_ext_router.py").use { input ->
                FileOutputStream(extDest).use { output ->
                    input.copyTo(output)
                }
            }
            extDest.setExecutable(true, false)
            extDest.setReadable(true, false)
            val extSymlink = File(usrBin, "ext")
            if (!extSymlink.exists()) {
                try {
                    android.system.Os.symlink(extDest.absolutePath, extSymlink.absolutePath)
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Successfully extracted void-ext from assets.")
        } catch (e: Exception) {
            Log.w(TAG, "Notice: void-ext extraction from assets: ${e.message}")
        }

        // Deploy Python SDK: site-packages/voidterm/__init__.py
        val pySdkDir = File(context.filesDir, "usr/lib/python3.14/site-packages/voidterm").apply { mkdirs() }
        val pySdkInit = File(pySdkDir, "__init__.py")
        try {
            context.assets.open("sdk/python/voidterm/__init__.py").use { input ->
                FileOutputStream(pySdkInit).use { output ->
                    input.copyTo(output)
                }
            }
            pySdkInit.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: Python SDK extraction: ${e.message}")
        }

        // Deploy Node.js SDK: node_modules/voidterm/index.js
        val jsSdkDir = File(context.filesDir, "usr/lib/node_modules/voidterm").apply { mkdirs() }
        val jsSdkIndex = File(jsSdkDir, "index.js")
        try {
            context.assets.open("sdk/js/voidterm/index.js").use { input ->
                FileOutputStream(jsSdkIndex).use { output ->
                    input.copyTo(output)
                }
            }
            jsSdkIndex.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: Node.js SDK extraction: ${e.message}")
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

    private fun unpackBootstrapIfNeeded(context: Context, usrDir: File) {
        val stampFile = File(usrDir, ".bootstrapped")
        if (stampFile.exists() && stampFile.readText().trim() == CURRENT_BOOTSTRAP_VERSION) {
            repairAndVerifySharedLibraries(File(usrDir, "lib"))
            return
        }

        val assetName = "bootstrap-aarch64.zip"
        val hasAsset = try {
            context.assets.open(assetName).close()
            true
        } catch (_: Exception) {
            false
        }
        if (!hasAsset) return

        try {
            Log.i(TAG, "Unpacking $assetName into ${usrDir.absolutePath}...")
            
            // Retain persistent backup copy of bootstrap container
            val backupFile = File(usrDir, "var/bootstrap.bak.zip")
            if (!backupFile.exists() || backupFile.length() < 1024) {
                backupFile.parentFile?.mkdirs()
                try {
                    context.assets.open(assetName).use { rawIn ->
                        FileOutputStream(backupFile).use { fos ->
                            rawIn.copyTo(fos)
                        }
                    }
                    Log.i(TAG, "Successfully archived bootstrap backup to ${backupFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: Unable to write bootstrap backup: ${e.message}")
                }
            }

            context.assets.open(assetName).use { rawIn ->
                ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                    var entry = zis.nextEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        val outFile = File(usrDir, entry.name)
                        // Security check: Zip Slip path traversal guard
                        if (!outFile.canonicalPath.startsWith(usrDir.canonicalPath)) {
                            entry = zis.nextEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                            if (entry.name.startsWith("bin/") || entry.name.contains(".so")) {
                                outFile.setExecutable(true, false)
                            }
                            outFile.setReadable(true, false)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            repairAndVerifySharedLibraries(File(usrDir, "lib"))
            stampFile.writeText(CURRENT_BOOTSTRAP_VERSION)
            Log.i(TAG, "Successfully extracted bootstrap container rootfs v$CURRENT_BOOTSTRAP_VERSION.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unpacking bootstrap container: ${e.message}", e)
        }
    }

    private fun repairAndVerifySharedLibraries(usrLib: File) {
        if (!usrLib.exists() || !usrLib.isDirectory) return
        try {
            val files = usrLib.listFiles() ?: return
            for (file in files) {
                if (file.isFile && (file.name.endsWith(".so") || file.name.contains(".so."))) {
                    // Detect placeholder files (e.g. symlink strings extracted by ZipInputStream)
                    if (file.length() in 1..255) {
                        try {
                            val targetName = file.readText().trim()
                            val targetFile = File(usrLib, targetName)
                            if (targetFile.exists() && targetFile.length() > 1024) {
                                file.delete()
                                try {
                                    android.system.Os.symlink(targetName, file.absolutePath)
                                } catch (_: Exception) {
                                    java.nio.file.Files.copy(
                                        targetFile.toPath(),
                                        file.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                    )
                                }
                                Log.i(TAG, "Repaired library placeholder: ${file.name} -> $targetName")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error repairing library ${file.name}: ${e.message}")
                        }
                    }
                }
            }

            // Guarantee libz.so.1 presence
            val libz1 = File(usrLib, "libz.so.1")
            if (!libz1.exists() || libz1.length() < 1024) {
                val localLibz = File(usrLib, "libz.so.1.3.2")
                val sysLibz = File("/system/lib64/libz.so")
                try {
                    if (libz1.exists()) libz1.delete()
                    if (localLibz.exists()) {
                        java.nio.file.Files.copy(localLibz.toPath(), libz1.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } else if (sysLibz.exists()) {
                        android.system.Os.symlink(sysLibz.absolutePath, libz1.absolutePath)
                    }
                } catch (_: Exception) {}
            }

            val libz = File(usrLib, "libz.so")
            if (!libz.exists() || libz.length() < 1024) {
                val localLibz = File(usrLib, "libz.so.1.3.2")
                val sysLibz = File("/system/lib64/libz.so")
                try {
                    if (libz.exists()) libz.delete()
                    if (localLibz.exists()) {
                        java.nio.file.Files.copy(localLibz.toPath(), libz.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } else if (sysLibz.exists()) {
                        android.system.Os.symlink(sysLibz.absolutePath, libz.absolutePath)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Library verification notice: ${e.message}")
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

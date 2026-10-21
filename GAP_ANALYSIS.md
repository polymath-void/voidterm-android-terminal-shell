# VoidTerm Architectural Gap Analysis
## Sovereign Void-Architecture Transition Review

**Date**: 2026-10-20
**Auditor**: Principal Systems Architect & Android OS Bionic/Linux Platform Specialist
**Scope**: Comparison of VT-IMPL-AGENTS-001 implementation blueprint vs. current hybrid-engine codebase

---

## EXECUTIVE SUMMARY

The current implementation covers approximately 35-40% of the 7-workstream VoidTerm architectural blueprint. Critical gaps exist in Android 13+ security compliance, the Embedded OS API Gateway, Permissions Gatekeeper, NodeOS telemetry synchronization, and 5 of 7 workstreams have minimal or no implementation. The most urgent issues are the `am broadcast` SecurityException on Android 13+ and the missing bootstrap assets (`voidpkg.py`, `bootstrap-aarch64.zip`).

---

## 1. ANDROID 13-16 SECURITY & SELINUX LIMITATIONS

### 1.1 `am broadcast` SecurityException (CRITICAL)
**Location**: `DynamicCoreLoader.kt` lines 102, 262; `TerminalService.kt` line 134; `ReloadCoreReceiver.kt`; `RunCommandReceiver.kt`
**Issue**: Multiple `am broadcast -a com.hybridengine.terminal.RELOAD_CORE` calls will fail on Android 13+ (API 33) with:
```
SecurityException: Permission Denial: broadcast asks to run as user -2; requiring android:grantUriPermissions or flag
```
**Root Cause**: App-internal broadcasts from non-system apps are restricted on Android 13+. The `RELOAD_CORE` receiver is declared in `AndroidManifest.xml` but will not be triggered from within the app's own process.

**Enhancement**: Replace all `am broadcast` calls with filesystem-based triggers:
- Use `$PREFIX/var/run/.reload_trigger` file touch monitored by `FileObserver` in `TerminalService.kt`
- Remove `ReloadCoreReceiver` and `RELOAD_CORE` from manifest
- Implement `FileObserver` on `$PREFIX/var/run/.reload_trigger` to call `DynamicCoreLoader.reload(context)` directly

### 1.2 Foreground Service Special Use Declaration
**Location**: `AndroidManifest.xml` line 62-65; `TerminalService.kt` line 45-46
**Issue**: `FOREGROUND_SERVICE_SPECIAL_USE` requires explicit subtype configuration for Android 14+. The current `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration may not match expected values on Dimensity 7200 Pro.

**Enhancement**: 
- Verify `android:foregroundServiceType` values against Android 14 SDK requirements
- Add fallback for apps without `MANAGE_VIRTUAL_MACHINE` permission
- Implement `onTrimMemory` and `onBackgroundStarted` lifecycle callbacks

### 1.3 Clipboard Privacy Restrictions Android 13+
**Location**: `MainActivity.kt` clipboard operations (multiple locations)
**Issue**: Android 13+ (API 33) introduces `SECURE_CLIPBOARD` mode and may restrict clipboard access when an app is in the background. Multiple rapid clipboard reads/writes may trigger `IllegalArgumentException`.

**Enhancement**:
- Add `android:clipboard` manager with rate limiting
- Implement `ClipboardManager.OnPrimaryClipChangedListener` with permission state tracking
- Add user prompt for clipboard access similar to the Permissions Gatekeeper dialog

### 1.4 WakeLock Leak in Doze Mode
**Location**: `TerminalService.kt` line 44-48
**Issue**: Partial WakeLock acquired for 24 hours may be released or restricted by Android's Doze mode on Android 13+. If the app goes into background, the WakeLock may not keep the CPU active, causing background cron jobs and cron.schedule endpoints to stall.

**Enhancement**:
- Use `WorkManager` with `setExpedited()` for time-critical background tasks instead of WakeLock
- Implement `PowerManager.assertPowerSaveMode()` checks before acquiring WakeLock
- Add `WakeLock` release in `onStop()` and `onDestroy()` with proper null guards

---

## 2. EMBEDDED OS API GATEWAY EDGE CASES

### 2.1 Unix Domain Socket Design Missing
**Location**: No `VoidApiServer.kt` or `VoidApiEndpoints.kt` files exist
**Issue**: The architectural blueprint calls for a Unix Domain Socket at `$PREFIX/var/run/void_api.sock` with HTTP localhost fallback, but no implementation exists. Without this, extensions cannot communicate with the core without using `am broadcast`.

**Edge Cases**:
- **Socket credential passing via `SO_PEERCRED`**: Not implemented. Need to capture process UID/GID when handling extension API requests for ACL isolation.
- **Concurrent request handling**: No request ID tracking or correlation ID system. Simultaneous `clipboard.get` and `notification.post` calls may race.
- **Background WakeLock leaks**: API gateway endpoints (especially `power.wakelock_acquire`) must track and release wakelocks properly.

**Enhancement**: Implement `VoidApiServer.kt` with:
```kotlin
// Unix Domain Socket server using ServerSocketChannel
val socketPath = "$PREFIX/var/run/void_api.sock"
// SO_PEERCRED credential passing for ACL verification
socket.socket?.setOption(SoOption.soPeerCred, true)
// Request correlation IDs for concurrent handling
// Background task tracking with WeakReference-based cleanup
```

### 2.2 JSON-RPC Dispatcher Missing
**Issue**: No JSON-RPC method dispatcher for the 11 gateway endpoints:
- `notification.post`, `notification.cancel`
- `clipboard.get`, `clipboard.set`
- `vibrator.vibrate`
- `toast.show`
- `audio.play`
- `battery.status`
- `power.wakelock_acquire`, `power.wakelock_release`
- `intent.launch`, `intent.view_url`
- `process.spawn_bg`, `process.kill`
- `cron.schedule`, `cron.cancel`

**Enhancement**: Implement threaded socket handler with method dispatch:
```kotlin
suspend fun handleClient(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.inputStream))
    val writer = PrintWriter(socket.outputStream, true)
    while (true) {
        val request = reader.readLine()
        if (request == null) break
        val json = JSONObject(request)
        val method = json["method"].toString()
        val id = json["id"]?.toString()
        val result = when (method) {
            "clipboard.set" -> ClipboardManager.apply { setPrimaryText(json["params"]["text"].toString()) }.run { putString("text", ...) }
            // ... other methods
        }
        writer.println(gson.toJson(mapOf("result" to result, "id" to id)))
    }
}
```

### 2.3 Clipboard Privacy in Android 13+
**Edge Case**: `clipboard.set` from background extension scripts may be blocked. Android 13+ requires that the requesting activity be in the foreground or have `MANAGE_ALL_FILES_ACCESS_PERMISSION`.

**Enhancement**: 
- Track clipboard permission state in `PermissionGatekeeper`
- Show interactive dialog on first `clipboard.set` usage (Allow Once / Allow for Days / Always Allow / Deny)
- Cache clipboard content with expiration timestamp

### 2.4 Vibrator Pattern Array Support
**Edge Case**: `vibrator.vibrate` with pattern arrays requires `VIBRATE` permission and may be restricted in Doze mode. Pattern arrays longer than 400ms may not play if the app is not foreground.

**Enhancement**:
- Verify `hasVibrator()` before invoking
- Fallback to single `long` parameter if pattern not supported
- Request `FOREGROUND_SERVICE` permission if vibration needs to continue in background

---

## 3. NODEOS BI-DAILY TELEMETRY SYNC

### 3.1 Missing AlarmManager Scheduling
**Issue**: No `AlarmManager` setup for bi-daily sync at 00:01 and 12:01. The architectural blueprint requires telemetry accumulator to commit data to `agy_nodeos.db` at these times, but the implementation has no scheduled jobs.

**Battery Optimization Exemptions Required**:
- **Android 12+ (API 31)**: `setExactAndAllowWhileIdle()` requires APP_EXACT_ALARM permission
- **Android 6-11 (API 23-30)**: `setExact()` may be restricted by Doze
- **Android 13+ (API 33)**: Background exact alarms are restricted; need foreground service or WorkManager with `setMergeWork()`

**Enhancement**: Implement `NodeOsTelemetrySync.kt` with:
```kotlin
// Bi-daily telemetry sync using AlarmManager
val alarmManager = context.getSystemService(AlarmManager::class.java)
val intent = Intent(context, TelemetrySyncReceiver::class.java)
val pendingIntent = PendingIntent.getBroadcast(
    context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

// 00:01 sync
calendar.set(Calendar.HOUR_OF_DAY, 0)
calendar.set(Calendar.MINUTE, 1)
alarmManager.setExactAndAllowWhileIdelay(
    AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
)

// 12:01 sync
calendar.set(Calendar.HOUR_OF_DAY, 12)
calendar.set(Calendar.MINUTE, 1)
alarmManager.setExactAndAllowWhileIdelay(
    AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
)
```

### 3.2 WorkManager Integration
**Issue**: `PeriodicWorkRequest` for cron tasks needs proper `WorkManager` configuration to run during Doze without keeping CPU awake.

**Enhancement**:
```kotlin
val workRequest = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(24, TimeUnit.HOURS)
    .setFlexibleDurationSeconds(300)
    .addTag("telemetry-sync")
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "nodeos-telemetry", ExistingPeriodicWorkStrategy.KEEP, workRequest
)
```

### 3.3 Runtime Watchdog Missing
**Issue**: No monitor for expired permissions or cancelled jobs. The blueprint requires automatic revocation of expired time-bound permissions.

**Enhancement**: Implement `RuntimeWatchdog` that:
- Queries `agy_nodeos.db` periodically for expired permissions
- Sends `action: VOID_EXT_REVOKE` via filesystem trigger (not broadcast)
- Updates extension permission state in SharedPreferences

---

## 4. 7 WORKSTREAMS COMPLETENESS ANALYSIS

### Workstream 1: Core POSIX Runtime & Rootless IPC
**Status**: PARTIALLY IMPLEMENTED (40% complete)

**Missing Components**:
- ❌ `sendSignal(SIGINT)` for Ctrl+C - not implemented; terminal uses pipe-based IO without process group signaling
- ❌ CWD integrity via `/proc/$pid/cwd` - `SessionManager.kt` exists but doesn't query `/proc/$pid/cwd` after command completion
- ❌ Storage bridge auto-symlinks `$HOME/storage` -> `/sdcard` and `$HOME/sdcard` -> `/sdcard` - `VoidFS.kt` has partial implementation but missing `$HOME/storage` creation and `/sdcard` symlink
- ❌ Sovereign welcome banner replacing "Rootless POSIX Container (Termux-Architecture)" - still shows legacy descriptor
- ❌ Signal propagation for `Ctrl+Z` (suspend), `Ctrl+D` (EOF)

**Verification Gates to Add**:
- `exit` - verify tab closes gracefully (not auto-restart loop)
- `cd -` - verify CWD changes correctly, `/proc/$pid/cwd` queried
- `void reload` - verify no SecurityException (filesystem trigger instead of broadcast)
- `cd ~` and `cd ..` - verify home and parent directory navigation

### Workstream 2: Embedded OS API Gateway
**Status**: NOT IMPLEMENTED (0% complete)

**Missing Components**: All of the following are NEW files:
- ❌ `VoidApiServer.kt` - Unix Domain Socket server
- ❌ `VoidApiEndpoints.kt` - JSON-RPC method dispatcher (11 endpoints)
- ❌ Native Android automation bindings (NotificationManager, ClipboardManager, Vibrator, BatteryManager, PowerManager)
- ❌ CLI wrappers `$PREFIX/bin/void-*` (`void-clipboard`, `void-notify`, `void-toast`, `void-vibrate`, `void-battery`, `void-wakelock`, `void-intent`)

**Edge Cases Not Anticipated**:
- ❌ Socket credential passing via `SO_PEERCRED` for ACL enforcement
- ❌ Concurrent request correlation IDs
- ❌ Background `WakeLock` acquisition/release for `power.wakelock_acquire`
- ❌ Clipboard privacy dialogs on first `clipboard.set` invocation
- ❌ Vibrator pattern array restrictions in Doze mode

**Verification Gates**:
- `void-clipboard set "Test"` - instant toast + clipboard updated < 2ms latency
- `void-toast "Hello"` - on-screen toast displayed
- `void-vibrate 200` - haptic vibration triggered
- `void-battery status` - returns percentage, charging state, temperature
- `void-notify "Test"` - custom status bar void icon displayed

### Workstream 3: Permissions Gatekeeper & NodeOS Telemetry
**Status**: NOT IMPLEMENTED (0% complete)

**Missing Components** (all NEW):
- ❌ `PermissionGatekeeper.kt` - ACL mapping, interactive authorization dialog
- ❌ `NodeOsTelemetrySync.kt` - bi-daily AlarmManager sync, runtime watchdog
- ❌ `agy_nodeos.db` schema migration - `node_type = "extension"` table creation
- ❌ Permission grant tracking: asked, allowed, denied, grant type, expiration, remaining calls
- ❌ Daily/Monthly usage hours tracking
- ❌ Manual trigger count, cronjob trigger count, last execution timestamp

**Edge Cases Not Anticipated**:
- ❌ `AGY_NODEOS_DB` path relative to CWD - needs absolute path resolution
- ❌ SQLite WAL mode configuration for telemetry DB
- ❌ Permission dialog appearing over fullscreen terminal - may hide UI
- ❌ Count-bound limits (e.g., 50 calls) interacting with time-bound limits (7 days)
- ❌ Revocation of expired permissions when app goes to background

**Verification Gates**:
- First invocation of extension API shows interactive dialog with 4 options
- SQLite entries in `agy_nodeos.db` show extension node with telemetry
- Unauthorized extension calls return `403 Forbidden`
- Bi-daily sync at 00:01 and 12:01 flushes metrics to SQLite
- Runtime watchdog revokes expired time-bound permissions

### Workstream 4: Viewport, Typography & Visual UX
**Status**: PARTIALLY IMPLEMENTED (55% complete)

**Missing Components**:
- ❌ `voidls.py` - visual directory listing with Unicode glyphs (📁, ⚡, 📦, 📄, 🔗)
- ❌ Font assets - `JetBrainsMono-Regular.ttf` and `FiraCode-Regular.ttf` not bundled in `assets/fonts/`
- ❌ `TerminalTheme` expansion from 3 to 10 presets (Monokai included, missing Nord, Solarized, Gruvbox, Tokyo Night, Cyberpunk, Material Dark)
- ❌ Compact configurable padding via `voidterm.json` (currently hardcoded in `TerminalSurfaceView.kt`)
- ❌ Mobile typography engine configuration

**Edge Cases Not Anticipated**:
- ❌ ANSI `\r` (carriage return) handling - `AnsiParser.kt` may strip `\r` incorrectly
- ❌ TrueColor `38;2;R;G;B` SGR parsing with invalid RGB values
- ❌ Font fallback when `JetBrainsMono` is not installed on device
- ❌ `line_spacing` 1.15 rendering across different API levels

**Verification Gates**:
- Terminal displays JetBrains Mono font
- 4-color PS1 prompt: username `#50FA7B`, colon `#F8F8F2`, directory `#8BE9FD`, prompt `#F1FA8C`
- `voidls` output shows Unicode glyphs for directory/script/archive/file/symlink
- `voidterm.json` padding properties adjustable without UI restart

### Workstream 5: Input Subsystem, Physical Keymap & Tab Card Manager
**Status**: PARTIALLY IMPLEMENTED (45% complete)

**Missing Components**:
- ❌ Real physical arrow cluster: `▲` directly above `▼`, flanked by `◀` and `▶` - current keyboard toolbar has separate arrows
- ❌ Universal Key Catalog `KeyCatalog.kt` - not implemented
- ❌ `TabsCardBottomSheet.kt` - grid icon `⊞` sheet with visual cards not implemented
- ❌ Header mic button removal from `activity_main.xml` while preserving `RECORD_AUDIO` pipelines
- ❌ Tab pinning (`📌`) and renaming support
- ❌ Global Linux shortcuts: `Ctrl+C`, `Ctrl+T`, `Ctrl+1/2`, `Ctrl+Shift+C`, `Ctrl+V`, `Ctrl+D`, `Ctrl+L`, `Ctrl+Z`

**Edge Cases Not Anticipated**:
- ❌ Arrow key conflict with scroll gestures in `HorizontalScrollView`
- ❌ `Ctrl+C` SIGINT propagation to child process group - may not kill stuck executions
- ❌ Tab card preview rendering performance with 3-line terminal output
- ❌ Horizontal swipe scroll budgeting with configurable row limits

**Verification Gates**:
- `▲` sits directly above `▼` on right side of keymap
- Row limits configurable (8 keys per row with even width distribution)
- Horizontal swipe scroll unlocks when `rowN_unlocked` = true
- Tab card `⊞` shows visual cards with CWD, process status, 3-line preview
- `Ctrl+C` sends SIGINT to child process group, killing stuck executions
- `Ctrl+T` opens new tab, `Ctrl+1/2` switches tabs

### Workstream 6: Extensions Core & VFS `/file` TUI Engine
**Status**: NOT IMPLEMENTED (0% complete)

**Missing Components** (all NEW):
- ❌ `$PREFIX/etc/extensions/<id>/` directory structure (`metadata.json`, `scripts/fileview.js`, `environments/integrations.js`, `environments/cronjob.js`)
- ❌ Interactive VFS `/file` slash command file browser (dual-mode: ANSI TUI script + touch bottom-sheet)
- ❌ Settings Extension Store in `preferences.xml` with enable/disable toggles
- ❌ Permissions inspector for extension ACLs

**Edge Cases Not Anticipated**:
- ❌ `/file` browser in bottom-sheet may not render ANSI sequences correctly
- ❌ File operations (copy/move/rename) across storage boundaries requiring `MANAGE_EXTERNAL_STORAGE`
- ❌ `nano`/`vim` editor launching from VFS with proper PTY allocation
- ❌ Metadata.json schema validation for extension auto-discovery
- ❌ Extension isolation: granting clipboard to `filemanager` not allowing other scripts access

**Verification Gates**:
- Typing `/file` in VoidTerm opens interactive file manager
- File operations (navigate, copy, move, rename, delete, new file, new folder) functional
- Editor launching (`nano`/`vim`) works within VFS
- Settings Extension Store shows installed extensions with permission inspector

### Workstream 7: Cloud Package Engine & Linker Hardening
**Status**: NOT IMPLEMENTED (0% complete) - also CRITICAL bootstrap gap

**Missing Components** (all NEW or missing assets):
- ❌ `voidpkg.py` - not in assets directory (only `architecture.svg` and `banner.jpg` exist)
- ❌ `bootstrap-aarch64.zip` - not in assets (CONTEXT.md confirms bootstrap paradox: ZipInputStream flattens symlinks)
- ❌ Multi-mirror CDN failover (Cloudflare, Termux Main, Grimler)
- ❌ Automated ELF retargeting via bundled `patchelf`
- ❌ `pkg doctor` self-diagnostic gate (ELF SONAME resolution, certificates, terminfo)
- ❌ Bootstrap offline backup snapshot `$PREFIX/var/bootstrap.bak.zip`
- ❌ Commented configuration `$PREFIX/etc/voidterm.json` with comprehensive documentation

**Critical Issues** (from CONTEXT.md binary analysis):
- ❌ `bootstrap-aarch64.zip` stores library names as POSIX symlinks; `ZipInputStream` flattens them to 11-18 byte regular files
- ❌ `libpython3.14.so` contains no zlib symbols; `_zlib` and `_readline` missing from `lib/python3.14/lib-dynload/`
- ❌ `voidpkg.py:6` does `import gzip` → `ModuleNotFoundError: zlib` → package manager dead on arrival
- ❌ `build_artifacts/app-debug.apk` (98 MB) missing `bootstrap-aarch64.zip`, `voidpkg.py`, `tls/cert.pem`
- ❌ Hardcoded `/data/user/0/...` paths in `.bashrc` and `voidpkg.py` shebang break multi-user installs
- ❌ `targetSdk=28` conflicts with `specialUse` foreground-service declaration
- ❌ `.bootstrapped` stamp has no version binding and no SHA-256 integrity gate
- ❌ `TERMINFO_DIRS` never set despite bundled `share/terminfo/x/xterm-256color`

**Edge Cases Not Anticipated**:
- ❌ ZIP Slip path traversal during bootstrap extraction (partially guarded in `DynamicCoreLoader.kt` line 498)
- ❌ Symlink repair race condition: file may exist but be broken placeholder from previous extraction
- ❌ `libz.so.1` absence causes `gzip`/`zlib` import failures throughout the package manager
- ❌ `patchelf` not bundled, ELF RUNPATHs remain unretargeted
- ❌ TLS cert chain validation may fail without `cert.pem` in `$PREFIX/etc/tls/`

**Verification Gates**:
- `pkg doctor` runs and all checks PASS
- `pkg install nano` completes cleanly without missing shared libraries
- Bootstrap extraction preserves symlinks (library `.so` files functional)
- `voidpkg.py` runs with `python3` without `ModuleNotFoundError: zlib`
- Cloud package installation with multi-mirror failover works

---

## 5. IPC RACE CONDITIONS & BACKGROUND PROCESS SAFETY

### 5.1 Broadcast Receiver Race on Android 13+
**Issue**: `ReloadCoreReceiver` and `RunCommandReceiver` as `BroadcastReceiver` components will not receive ordered broadcasts from within the app's own process on Android 13+. The `am broadcast` intents sent from `DynamicCoreLoader.kt` and `TerminalService.kt` will be swallowed or throw `SecurityException`.

**Race Timeline**:
1. `void reload` script sends `am broadcast -a RELOAD_CORE`
2. System raises `SecurityException: Permission Denial: broadcast asks to run as user -2`
3. Exception caught silently, no reload occurs
4. User sees stale configuration, no error indication

**Enhancement**: Replace all `am broadcast` with:
- `FileObserver` on `$PREFIX/var/run/.reload_trigger` 
- Direct method calls via `TerminalService.instance?.broker?.reload()` 
- AYC (Already Yet Consistent) pattern: write trigger file → observer fires → handler runs

### 5.2 Service Lifecycle & WakeLock Leak
**Issue**: `TerminalService` acquires `PARTIAL_WAKE_LOCK` for 24 hours in `onCreate()` but may not properly release if:
- App process is killed by Android LMK (Low Memory Killer)
- Activity destruction doesn't unbind service cleanly
- `onDestroy()` not called if process crashes

**Enhancement**:
- Use `setReferenceCounted(false)` only with explicit release in `onDestroy()`
- Implement `onTaskRemoved()` in `TerminalService` for complete cleanup
- Use `WakeLockFlags` that auto-release on screen off/sleep transitions
- Add `ProcessLifecycleOwner` observation for background state tracking

### 5.3 Concurrent Shell Process Access
**Issue**: Multiple UI tabs may attempt to write to the same shell process session simultaneously. `SessionManager` tracks active tabs but doesn't serialize process input.

**Enhancement**:
- Implement input queue per `ProcessSession` with `ConcurrentLinkedQueue`
- Display `⚡ Busy` indicator when shell is processing prior command
- Add `Ctrl+Z` suspend before issuing new command if previous still running

### 5.4 FileObserver vs Broadcast Intent Reliability
**Issue**: `FileObserver` on `$PREFIX/var/run/.reload_trigger` may fire multiple times or have debouncing issues. The trigger file may be cleaned up before observer processes the event.

**Enhancement**:
- Use `FileObserver.MASK_DELETE | FileObserver.MASK_MOVED_FROM` to catch trigger creation
- Implement debounce: require trigger file to exist for > 100ms before firing
- Atomic trigger pattern: write to `.reload_trigger.tmp`, then `renameTo(.reload_trigger)`

---

## 6. COMPREHENSIVE IMPLEMENTATION ROADMAP

### Priority 0: Critical Fixes (Immediate)
1. ✅ Replace `am broadcast` in `DynamicCoreLoader.kt` `void reload` script with filesystem trigger
2. ✅ Remove `ReloadCoreReceiver` from `AndroidManifest.xml`
3. ✅ Implement `FileObserver` on `$PREFIX/var/run/.reload_trigger` in `TerminalService.kt`
4. ✅ Fix `.bashrc` hardcoded paths - use dynamic `$PREFIX` resolution
5. ✅ Add `bootstrap-aarch64.zip` and `voidpkg.py` to assets directory
6. ✅ Fix symlink flattening in `unpackBootstrapIfNeeded()` - use `tar` extraction instead of `ZipInputStream`

### Priority 1: Workstream 2 - OS API Gateway (High)
1. Implement `VoidApiServer.kt` with Unix Domain Socket
2. Implement `VoidApiEndpoints.kt` with 11 JSON-RPC endpoints
3. Add native Android automation bindings
4. Deploy `$PREFIX/bin/void-*` CLI wrapper scripts
5. Implement SO_PEERCRED credential passing for ACL enforcement

### Priority 2: Workstream 3 - Permissions Gatekeeper (High)
1. Implement `PermissionGatekeeper.kt` with ACL mapping
2. Build interactive authorization dialog (Allow Once / Days / Always / Deny)
3. Create `agy_nodeos.db` schema for `node_type = "extension"`
4. Implement bi-daily `AlarmManager` sync at 00:01 & 12:01
5. Build runtime watchdog for expired permission revocation

### Priority 3: Workstream 1 - Core POSIX Runtime (High)
1. Implement `sendSignal(SIGINT)` for Ctrl+C
2. Add CWD integrity via `/proc/$pid/cwd` queries
3. Complete storage bridge auto-symlinks `$HOME/storage` -> `/sdcard`
4. Replace legacy "Rootless POSIX Container" banner with "VoidCore Shell Active [AArch64]"
5. Implement clean process exit protocol (crash vs intentional exit)

### Priority 4: Workstream 4 - Visual UX (Medium)
1. Bundle `JetBrainsMono` and `FiraCode` font assets
2. Implement `voidls.py` with Unicode glyphs
3. Expand `TerminalTheme` to 10 presets
4. Add configurable padding via `voidterm.json`
5. Fix ANSI `\r` handling and TrueColor SGR parsing

### Priority 5: Workstream 5 - Keymap & Tab Card (Medium)
1. Implement physical arrow cluster `▲` above `▼`
2. Build `KeyCatalog.kt` universal key catalog
3. Implement `TabsCardBottomSheet.kt` with grid `⊞`
4. Add global Linux shortcuts (Ctrl+C, Ctrl+T, etc.)
5. Remove mic button from `activity_main.xml`, preserve audio pipelines

### Priority 6: Workstream 6 - Extensions & VFS /file (Medium)
1. Deploy `$PREFIX/etc/extensions/<id>/` directory structure
2. Implement interactive VFS `/file` slash command browser
3. Add Settings Extension Store with permissions inspector
4. Implement metadata.json schema validation
5. Build editor launching (nano/vim) from VFS

### Priority 7: Workstream 7 - Cloud Package Engine (Medium-High)
1. Create `voidpkg.py` with multi-mirror CDN failover
2. Fix bootstrap symlink extraction (use `tar` not `ZipInputStream`)
3. Implement `pkg doctor` self-diagnostic gate
4. Add `pkg restore` / `pkg backup` CLI applets
5. Deploy commented `$PREFIX/etc/voidterm.json` configuration

### Priority 8: Battery/Doze Exemptions (High)
1. Request `EXACT_ALARM` permission for bi-daily sync
2. Implement WorkManager with `setExpedited()` for cron tasks
3. Add `MANAGE_VIRTUAL_MACHINE` permission handling
4. Configure `WorkManager` overflow settings for Android 13+
5. Test battery drain under Doze mode with cron.schedule enabled

---

## 7. VERIFICATION GATES SUMMARY

| Workstream | Verification Command / Action | Success Criteria |
|---|---|---|
| **WS 1** | `void reload` & `exit` | Zero SecurityException, tab closes gracefully |
| **WS 1** | `cd -`, `cd ~`, `cd ..` | CWD integrity, `/proc/$pid/cwd` accurate |
| **WS 2** | `void-clipboard set "Test"` | Instant toast, clipboard updated < 2ms |
| **WS 2** | `void-toast "Hello"` | On-screen toast displayed |
| **WS 2** | `void-vibrate 200` | Haptic vibration triggered |
| **WS 3** | First extension API call | Interactive dialog: Allow Once / Days / Always / Deny |
| **WS 3** | SQLite query `agy_nodeos.db` | Extension node with telemetry metrics |
| **WS 3** | Unauthorized API call | Returns `403 Forbidden` |
| **WS 3** | Time at 00:01 / 12:01 | Bi-daily sync flushes metrics to SQLite |
| **WS 4** | Terminal inspection | JetBrains Mono, 4-color PS1, glyph icons |
| **WS 5** | Arrow key inspection | `▲` above `▼`, row limits adapt, card view functional |
| **WS 5** | Global shortcuts | `Ctrl+C` SIGINT, `Ctrl+T` new tab, `Ctrl+1/2` switch |
| **WS 6** | `/file` command | Interactive VFS file browser opens and edits files |
| **WS 7** | `pkg doctor` | All checks PASS, nano installed without missing libs |
| **WS 7** | `pkg install git` | Cloud package installed with multi-mirror failover |

---
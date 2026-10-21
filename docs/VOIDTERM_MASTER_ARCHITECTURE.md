# VoidTerm: Master Architectural Specification & System Blueprints
## Document Version: 3.0.0-SOVEREIGN | Status: Approved & Active in Production
### Target Platform: Android 13–16 (`aarch64` / Bionic libc) | Target SDK 34+ (Google Play Store Compliant)

---

## 1. Architectural Philosophy & Sovereign Paradigm

VoidTerm is engineered as a **tri-plane sovereign execution and automation platform**:
1. **The Human Visual & Input Plane**: A 60 FPS hardware-accelerated terminal interface for mobile developers featuring an inverted-T arrow cluster (`▲` over `◀`, `▼`, `▶`), dynamic key budgeting with gesture scrolling, multi-session tab pinning, and visual card overview (`TabsCardBottomSheet`).
2. **The Sovereign Rootless Execution Plane**: A self-contained rootless POSIX container operating on VoidFS (`$PREFIX = /data/data/com.hybridengine.terminal/files/usr`), dynamic `.bashrc` prompt (`username:directory$`), custom directory lister (`voidls`), hardened package manager (`voidpkg`), and in-process filesystem hot-reload triggers via `FileObserver`.
3. **The Polyglot Extensions & OS API Gateway Plane**: An embedded Zero-Trust OS automation gateway (`VoidApiServer` on `void_api.sock` and HTTP `8765`), granular Permissions Gatekeeper (`PermissionGatekeeper`), polyglot Python and Node.js extension SDKs, built-in interactive ANSI terminal browsers (`/pkg`, `/file`), and NodeOS concurrency watchdog with bi-daily telemetry sync.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        HUMAN VISUAL & INPUT PLANE                      │
│         Android App (Kotlin / SurfaceView 60 FPS / Jetpack Compose)    │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │     Terminal Display Area     │  │   Inverted-T Keypad Cluster   │  │
│  │   (10 Themes, JetBrainsMono)  │  │   (▲ over ◀ ▼ ▶ / Budgeting)  │  │
│  ├───────────────────────────────┤  ├───────────────────────────────┤  │
│  │   TabsCardBottomSheet (Pin)   │  │ ExtensionsHubBottomSheet (Kill│  │
│  └───────────────▲───────────────┘  └───────────────▲───────────────┘  │
└──────────────────┼──────────────────────────────────┼──────────────────┘
                   │ Local Process / JNI Broker
┌──────────────────▼──────────────────────────────────▼──────────────────┐
│                   SOVEREIGN ROOTLESS EXECUTION PLANE                   │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │    TerminalService (FGS)      │  │ DynamicCoreLoader (VoidFS)    │  │
│  │(FileObserver .reload_trigger) │  │(.bashrc prompt, voidls, bak)  │  │
│  ├───────────────────────────────┤  ├───────────────────────────────┤  │
│  │     SessionManager.kt         │  │       voidpkg.py Engine       │  │
│  │(Tab Pinning, CWD Inheritance) │  │(ctypes libz preloading & doc) │  │
│  └───────────────┬───────────────┘  └───────────────┬───────────────┘  │
└──────────────────┼──────────────────────────────────┼──────────────────┘
                   │ Local Domain Socket & HTTP Fallback
┌──────────────────▼──────────────────────────────────▼──────────────────┐
│              POLYGLOT EXTENSIONS & OS API GATEWAY PLANE                │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │  VoidApiServer (Unix / HTTP)  │  │  PermissionGatekeeper (ACL)   │  │
│  │  (Clipboard, Toast, Notify,   │  │  (Allow Once, 7D, Always,     │  │
│  │   Vibrator, Battery, Overlay) │  │   Deny Zero-Trust Isolation)  │  │
│  ├───────────────────────────────┤  ├───────────────────────────────┤  │
│  │  CronBroadcastReceiver        │  │  NodeOS Concurrency Watchdog  │  │
│  │  (AlarmManager + WakeLock)    │  │  (/proc/pid/stat Singleton &  │  │
│  │                               │  │   Bi-Daily 00:01 & 12:01 Sync)│  │
│  ├───────────────────────────────┴──┴───────────────────────────────┤  │
│  │ Polyglot SDKs: Python (void_extensions.py) & JS (void_extensions.js) │
│  │ Built-in Interactive TUIs: /pkg Browser & /file Navigator        │
│  │ CLI Extension Router: void-ext [list|run|kill|kill-all|create]   │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Specifications & Codebase Topology

### 2.1 Android Application Frontend (`android/app`)
- **`TerminalSurfaceView.kt`**: High-throughput Android `SurfaceView` running a dedicated rendering thread at 60 FPS. Monospace canvas rendering supporting 10 color themes (Dracula, Nord, Monokai, Void Matrix, Cyberpunk, Solarized, OneDark, etc.), custom typeface loader (`JetBrainsMono`/`FiraCode`), dynamic user prompt, and selection toolbar.
- **`MainActivity.kt`**: Main activity managing input lifecycle, double-row virtual toolbar with physical inverted-T arrow layout, dynamic button budgeting when locked, unlocked horizontal swipe gestures, and global Linux shortcut dispatcher (`Ctrl+C`, `Ctrl+T`, `Ctrl+1..9`, `Ctrl+Shift+C`, `Ctrl+V`, `Ctrl+D`, `Ctrl+L`, `Ctrl+Z`).
- **`SessionManager.kt`**: Manages tab lifecycles, tab pinning (`isPinned`) protecting long-running tasks from accidental closure, tab renaming, and active working directory context propagation.
- **`TabsCardBottomSheet.kt`**: Card-based visual overview of active terminal sessions with live CWD indicators, tab creation, and pin toggles.
- **`ExtensionsHubBottomSheet.kt`**: Interactive bottom sheet displaying installed extensions, live runtime PIDs, permission states, and single-extension & master panic kill switches.

### 2.2 Sovereign Core Services
- **`TerminalService.kt`**: Android Foreground Service maintaining terminal processes under Android Low Memory Killer (LMK). Contains an in-process `FileObserver` watching `$PREFIX/var/run/.reload_trigger` to hot-reload configuration safely without calling `am broadcast`.
- **`DynamicCoreLoader.kt`**: Initializes the sovereign VoidFS POSIX container, deploys dynamic `.bashrc` prompt `username:directory$`, creates `$HOME/storage` and `$HOME/sdcard` symlinks, packages bootstrap backups to `$PREFIX/var/bootstrap.bak.zip`, and extracts polyglot assets.
- **`voidpkg.py`**: Sovereign package manager with dynamic `ctypes` candidate preloader resolving `libz` issues on Android 13+, multi-mirror fallback, and `pkg doctor` self-diagnostics.
- **`voidls.py`**: Unicode directory lister with distinct glyphs (`📁`, `📄`, `⚡`, `📦`, `🔗`) for clean mobile terminal navigation.

### 2.3 Embedded OS API Gateway & Security
- **`VoidApiServer.kt`**: Native in-process server listening on Unix Domain Socket `$PREFIX/var/run/void_api.sock` with local HTTP fallback on `127.0.0.1:8765`.
- **`VoidApiEndpoints.kt`**: JSON-RPC and REST endpoints for clipboard access, notifications, toast displays, haptic vibration, battery telemetry, device properties, app overlays, and process execution.
- **`PermissionGatekeeper.kt`**: Zero-Trust access control engine that intercepts extension API calls and presents an interactive authorization dialog:
  - `Allow Once`: Valid for a single call; expires immediately upon return.
  - `Allow for 7 Days`: Time-stamped epoch expiration.
  - `Always Allow`: Permanent grant stored in configuration.
  - `Deny`: Permanent or session rejection returning `403 Forbidden`.
- **`CronBroadcastReceiver.kt`**: AlarmManager receiver executing background cron jobs with partial `WakeLock` CPU protection and automatic release timers.
- **`NodeOsTelemetrySync.kt`**: Bi-daily telemetry engine syncing extension usage durations, invocation counts, and permission states at `00:01` and `12:01` local time into `agy_nodeos.db`.

### 2.4 Polyglot Extensions Platform
- **Python SDK (`void_extensions.py`)** & **Node.js SDK (`void_extensions.js`)**: Universal client libraries communicating with `VoidApiServer` over local sockets or HTTP.
- **`/pkg` Extension**: Interactive ANSI terminal browser for searching, installing, upgrading, and removing packages with background execution.
- **`/file` Extension**: Interactive ANSI file browser with directory tree navigation, file inspection, and bookmarking.
- **`void-ext` CLI (`void_ext_router.py`)**: Command-line router enforcing singleton PID verification (`/proc/<pid>/stat`) to prevent duplicate instances, plus single-kill and master panic kill commands.

---

## 3. Physical Inverted-T Keypad Specification

The virtual keyboard layout strictly adheres to the physical inverted-T layout standard:

```
Row 1: [ ESC ] [ TAB ] [  ~  ] [  /  ] [  -  ] ... [  ▲  ]
Row 2: [CTRL ] [ ALT ] [  |  ] [  $  ] [  "  ] ... [ ◀ ] [ ▼ ] [ ▶ ]
                                                    └────┬────┘
                                            Directly beneath ▲
```

- **Dynamic Budgeting Algorithm**: When the keypad is locked, each key's width is dynamically computed as:
  $$\text{KeyWidth} = \frac{\text{rowWeight}}{\sum \text{rowWeights}} \times \text{AvailableScreenWidth}$$
  ensuring all keys fit without clipping.
- **Horizontal Swipe Gesture**: When unlocked, a horizontal scroll viewport allows fluid panning across function keys (`F1`–`F12`), editing keys (`HOME`, `END`, `INS`, `DEL`), and special characters.

---

## 4. Play Store Compliance Matrix

| Requirement | Sovereign VoidTerm Architecture | Play Store Audit Status |
| :--- | :--- | :--- |
| **Target SDK 34+** | Compiled against API 34+ with modern Android 14+ FGS types | **COMPLIANT** |
| **W^X Policy** | Zero executable extractions from unsafe public storage; all assets mapped through app data container | **COMPLIANT** |
| **No Inter-User Broadcasts** | In-process `FileObserver` filesystem triggers replace `am broadcast` | **COMPLIANT** |
| **Background Persistence** | Foreground Service with Ongoing Notification & WakeLock guards | **COMPLIANT** |
| **User Data Privacy** | Granular Zero-Trust Permission Gatekeeper for all OS API access | **COMPLIANT** |

---

## 5. Verification & CI/CD Status

- **GitHub Actions Workflow**: `build-apk.yml`
- **Latest Successful Run**: **#39528355770** (`✓`)
- **Published Artifacts**:
  - `VoidTerm-Debug-APK` (17.2 MB)
  - `voidterm-guest-daemon` (Static musl ARM64 binary)
- **Universal Static QA**: Passed with 0 unmatched brackets and complete interface integrity.

# Sovereign VoidTerm Architecture & Multi-Agent Implementation Matrix

**Project:** Sovereign VoidTerm Terminal Architecture  
**Repository:** `polymath-void/voidterm-android-terminal-shell`  
**Location:** `/data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine`  
**Build Status:** [![Build Android APK](https://github.com/polymath-void/voidterm-android-terminal-shell/actions/workflows/build-apk.yml/badge.svg)](https://github.com/polymath-void/voidterm-android-terminal-shell/actions/workflows/build-apk.yml) — **SUCCESS (`✓`)**

---

## 1. Executive Summary & Rootless Architecture

VoidTerm is a rootless, sovereign terminal emulation engine and Android OS API bridge running natively on Android (`aarch64` / Bionic libc). It completely deprecates legacy Termux branding, package references, and external helper application dependencies.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              VOIDTERM FRONTEND                                │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │  TerminalSurfaceView  │  │ Dual-Row Inverted-T   │  │  SessionManager  │  │
│  │ (10 Themes, JetBrains)│  │ Toolbar (▲ over ◀▼▶)  │  │(Pinning/Rename/  │  │
│  │                       │  │ Dynamic Budgeting     │  │ CWD Spawning)    │  │
│  └───────────┬───────────┘  └───────────┬───────────┘  └────────┬─────────┘  │
└──────────────┼──────────────────────────┼───────────────────────┼────────────┘
               ▼                          ▼                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           TERMINAL CORE SERVICES                             │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │    TerminalService    │  │   DynamicCoreLoader   │  │  LocalProcess    │  │
│  │(FileObserver Trigger) │  │  (VoidFS, prompt, ls) │  │     Session      │  │
│  └───────────┬───────────┘  └───────────┬───────────┘  └────────┬─────────┘  │
└──────────────┼──────────────────────────┼───────────────────────┼────────────┘
               ▼                          ▼                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                    POLYGLOT EXTENSIONS & OS API GATEWAY                      │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │     VoidApiServer     │  │ PermissionGatekeeper  │  │  CronReceiver    │  │
│  │ (LocalSocket + HTTP)  │  │ (Once, 7D, Always,    │  │ (AlarmManager +  │  │
│  │                       │  │  Deny Zero-Trust ACL) │  │  WakeLock Safe)  │  │
│  ├───────────────────────┴──┴───────────────────────┴──────────────────┤  │
│  │ Built-in Extensions: /pkg (TUI Browser), /file (File Navigator)      │  │
│  │ SDKs: Python (void_extensions.py) & Node.js (void_extensions.js)    │  │
│  │ NodeOS Gatekeeper: PID Singleton Watchdog & Bi-Daily Telemetry Sync │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Multi-Agent Task Categorization & Skill Matrix

To avoid hallucinations, isolate context boundaries, and guarantee deterministic delivery, all architectural domains were assigned to specialized agents with distinct skill sets:

| Workstream | Domain / Subsystem | Primary Agent Profile | Core Skills Applied | Target Files & Deliverables |
|---|---|---|---|---|
| **Stream A** | Android Core & Lifecycle | `android-ninja` / Architect | `android-ninja`, `adaptive` | `TerminalService.kt`, `MainActivity.kt`, `TerminalConfig.kt` |
| **Stream B** | Virtual Keypad & Gestures | `generative-tui` / UI Specialist | `styles`, `edge-to-edge` | `KeyCatalog.kt`, `ToolbarRowLayout.kt`, Inverted-T Cluster |
| **Stream C** | Sessions & Pinning | `android-terminal-core` | `android-terminal-core` | `SessionManager.kt`, `TabsCardBottomSheet.kt` |
| **Stream D** | Embedded OS Gateway | `omniswarm-edge-node` | `android-permissions-security` | `VoidApiServer.kt`, `VoidApiEndpoints.kt`, `PermissionGatekeeper.kt` |
| **Stream E** | Cron & Telemetry | `polymath-core` / NodeOS Agent | `polymath-core`, `nodeos_standard` | `CronBroadcastReceiver.kt`, `NodeOsTelemetrySync.kt`, AlarmManager |
| **Stream F** | Polyglot SDKs & TUIs | `agy-hybrid-coder` | `syntycode-engine`, `generative-tui` | `void_extensions.py`, `void_extensions.js`, `/pkg`, `/file`, `void-ext` |
| **Stream G** | Package Engine & Filesystem | `native_ai_orchestration` | `safe-filesystem`, `qa-analyzer` | `voidpkg.py`, `voidls.py`, `DynamicCoreLoader.kt`, VoidFS |
| **Stream H** | CI/CD Assembly & QA | Verifier / QA Agent | `qa-analyzer`, `android-apk-ci` | GitHub Actions workflow, Gradle 8.6, universal QA analyzer |

---

## 3. Detailed Component Implementations

### 3.1 Unprivileged Reload Fix (Resolving Broadcast SecurityException)
- **Problem**: Running `void reload` triggered `am broadcast --user -2 act=com.hybridengine.terminal.RELOAD_CORE`, which crashed with `java.lang.SecurityException: Permission Denial: broadcast asks to run as user -2 but is calling from uid u0a280`.
- **Solution**: Replaced inter-user broadcast with an intra-process POSIX filesystem trigger:
  1. `void reload` writes a touch stamp to `$PREFIX/var/run/.reload_trigger`.
  2. `TerminalService.kt` registers an Android `FileObserver` on `$PREFIX/var/run/`.
  3. Upon detecting `CREATE` or `CLOSE_WRITE` on `.reload_trigger`, `DynamicCoreLoader.reload(context)` is safely invoked in-process.

### 3.2 Dual-Row Keymap with Inverted-T Cluster
- **Layout Invariant**:
  - Upper Row (Row 1): Functions, `ESC`, `TAB`, symbols, terminating strictly with **`▲`**.
  - Lower Row (Row 2): Modifiers (`CTRL`, `ALT`), `/`, `~`, `-`, terminating strictly with **`◀`**, **`▼`**, **`▶`**.
  - `▲` sits directly above `▼`, creating a physical inverted-T cluster aligned to the right edge.
- **Dynamic Budgeting**:
  - When locked: Keys dynamically budget widths across screen bounds (`KeyCatalog.kt`).
  - When unlocked: Horizontal swipe gestures allow free pan and scroll.

### 3.3 Zero-Trust Granular Permissions Gatekeeper
- **Interactive ACL Prompts**: Extension endpoints check authorization before execution:
  - `Allow Once`: Expires upon return.
  - `Allow for 7 Days`: Time-bound expiration tracked against epoch timestamps.
  - `Always Allow`: Permanent grant recorded in configuration.
  - `Deny`: Permanent or session-level denial.
- **Grant Isolation**: Extension grantees have strictly isolated namespaces (`extensionId:endpoint`).

### 3.4 Concurrency Watchdog & Bi-Daily Telemetry Sync
- **Singleton Guard**: `void_ext_router.py` verifies `/proc/<pid>/stat` before launching new extension processes, blocking duplicate instances.
- **Telemetry Sync**: Registered `AlarmManager` triggers synchronization at `00:01` and `12:01` local time into `agy_nodeos.db`.

### 3.5 Panic Master Kill Switches
- **CLI**: `void ext kill-all` signals `SIGTERM` and `SIGKILL` to all tracked extension PIDs.
- **GUI**: `ExtensionsHubBottomSheet.kt` features an instant one-tap **Master Panic Kill** button and per-extension termination controls.

---

## 4. Verification Evidence Matrix

| Checkpoint | Target | Expected Behavior | Actual Result | Status |
|---|---|---|---|---|
| **Syntax & Brackets** | `android/` | Universal QA pass | 0 unmatched brackets, clean AST | **PASS** |
| **Rust Daemon** | `hybrid-term-broker` | Musl static compilation | AArch64 static binary compiled in 47s | **PASS** |
| **Kotlin Compilation** | `android/app` | AGP Gradle assembleDebug | Zero unresolved references, 0 errors | **PASS** |
| **APK Generation** | `app-debug.apk` | Uploaded GitHub artifact | `VoidTerm-Debug-APK` (17MB) created | **PASS** |
| **Package Manager** | `voidpkg.py` | Standalone CLI help | Valid CLI flag parser with doctors | **PASS** |
| **Extension Router** | `void_ext_router.py` | Subcommand parsing | Valid `list`, `run`, `kill`, `kill-all` | **PASS** |
| **Directory Iconography** | `voidls.py` | Formatted Unicode listing | `📁`, `📄`, `⚡`, `📦`, `🔗` rendered | **PASS** |

---

## 5. Deployment Instructions for Android Devices

1. **Download APK**: Download `VoidTerm-Debug-APK` from the latest GitHub Actions workflow run:
   ```bash
   gh run download 39528355770 -n VoidTerm-Debug-APK
   ```
2. **Install on Device**:
   ```bash
   adb install -r app-debug.apk
   ```
3. **Launch Terminal**:
   - Opens directly into rootless POSIX container.
   - Initial bootstrap extracts assets automatically.
   - Dual-row keyboard with inverted-T arrows ready immediately.
   - Type `void ext list` or `void pkg` to explore the sovereign ecosystem.

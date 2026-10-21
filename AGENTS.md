# AGENTS.md — Governance & Operational Directives for Sovereign VoidTerm

## 1. Project Mission & Sovereign Identity
**VoidTerm** is a sovereign, rootless Android terminal emulator, POSIX execution container, and polyglot extension platform running natively on Android (`aarch64` / Bionic libc). It completely deprecates legacy Termux branding, package references, and external helper applications.

The core architecture combines:
- **Terminal UI & Input Layer**: Monospace 60 FPS `TerminalSurfaceView`, 10 customizable themes, and a dual-row virtual keyboard featuring a physical inverted-T arrow cluster (`▲` directly over `◀`, `▼`, `▶`) with dynamic key budgeting and unlocked swipe scrolling.
- **Session & Tab Manager**: Multi-session lifecycle manager with tab pinning (`isPinned`) for mission-critical tasks, dynamic CWD context propagation, visual overview (`TabsCardBottomSheet`), and global Linux keybindings.
- **Sovereign VoidFS Container**: Self-contained POSIX root (`$PREFIX`), dynamic `.bashrc` prompt (`username:directory$`), custom directory lister (`voidls`), and package manager (`voidpkg`) hardened against dynamic linker failures.
- **Polyglot Extensions Platform**: Python & Node.js SDKs (`void_extensions.py`, `void_extensions.js`), standardized `metadata.json` schemas, interactive ANSI terminal browsers (`/pkg`, `/file`), and unified CLI router (`void ext`).
- **Embedded Zero-Trust OS API Gateway**: Native Unix Domain Socket (`void_api.sock`) + HTTP loopback gateway (`VoidApiServer`), granular Permissions Gatekeeper (`PermissionGatekeeper`), and partial WakeLock-protected AlarmManager task scheduling (`CronBroadcastReceiver`).
- **NodeOS Concurrency Watchdog**: Singleton process PID validation via `/proc/<pid>/stat` and bi-daily telemetry sync at `00:01` and `12:01` into `agy_nodeos.db`.

---

## 2. Core Operational Directives (STRICT ENFORCEMENT)

### Rule 1: Mandatory Context Synchronization (`CONTEXT.md`)
1. **Pre-Task Check**: Every agent session or subagent invocation MUST review [`CONTEXT.md`](file:///data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/CONTEXT.md) before making modifications.
2. **Atomic Context Updates**: Whenever any subsystem is modified, designed, refactored, or tested, the agent MUST immediately update [`CONTEXT.md`](file:///data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/CONTEXT.md).
3. **No Phantom State**: Never introduce architectural components, configuration flags, or protocol changes without recording them in [`CONTEXT.md`](file:///data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/CONTEXT.md).

### Rule 2: Category-Based Event Logging Protocol
Every event, modification, architectural decision, or validation test MUST be appended to the **Event Log** section in [`CONTEXT.md`](file:///data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/CONTEXT.md) using the following strict category schema:

| Category Tag | Domain / Scope |
| :--- | :--- |
| `[ARCHITECTURE]` | High-level system design, pipeline modifications, AIDL/JNI boundaries. |
| `[TERMINAL_CORE]` | TerminalService, DynamicCoreLoader, VoidFS, rootless POSIX bootstrap. |
| `[EXTENSIONS_ENGINE]`| Polyglot SDKs (Python/Node.js), `/pkg` & `/file` TUIs, `void-ext` CLI. |
| `[OS_GATEWAY]` | Unix domain socket, HTTP fallback, permission gatekeeper, OS automation. |
| `[KEYPAD_INPUT]` | KeyCatalog, inverted-T cluster, dynamic budgeting, gesture scrolling. |
| `[SESSION_TABS]` | SessionManager, tab pinning, tab renaming, TabsCardBottomSheet. |
| `[NODEOS_TELEMETRY]`| NodeOS concurrency PID tracking, SQLite schema, bi-daily AlarmManager sync. |
| `[BUILD_CI]` | Gradle wrapper, Rust static musl toolchain, GitHub Actions workflows. |

---

## 3. Subsystem Invariants & Technical Rules

### 1. In-Process Reload Invariant (No `am broadcast`)
- **Strict Prohibition**: Never invoke `am broadcast` from shell scripts or background processes to notify the Android host app. Unprivileged UID calls trigger `SecurityException: Permission Denial: broadcast asks to run as user -2`.
- **Filesystem Trigger Standard**: All hot-reload signals must write a touch stamp to `$PREFIX/var/run/.reload_trigger`. `TerminalService` monitors this path using an in-process `FileObserver` and triggers `DynamicCoreLoader.reload(context)` safely.

### 2. Inverted-T Keypad Geometry Invariant
- **Cluster Positions**:
  - Upper Row (Row 1): Functions, `ESC`, `TAB`, symbols, terminating strictly with **`▲`**.
  - Lower Row (Row 2): Modifiers (`CTRL`, `ALT`), `/`, `~`, `-`, terminating strictly with **`◀`**, **`▼`**, **`▶`**.
  - `▲` sits directly above `▼`, flanked by `◀` on the left and `▶` on the right.
- **Budgeting**: Buttons must dynamically budget widths when locked to fit screen dimensions without clipping.

### 3. Zero-Trust Permission Gatekeeper Invariant
- **Interactive Prompts**: Extensions must not receive unprompted access to sensitive OS APIs.
- **ACL Options**: On first endpoint invocation, the user must be offered: *Allow Once*, *Allow for 7 Days*, *Always Allow*, or *Deny*.
- **Grant Isolation**: Permissions are keyed strictly by `extensionId:endpoint`.

### 4. NodeOS Concurrency & Process Singleton Invariant
- **PID Verification**: When an extension is launched, `void_ext_router.py` must record its PID and verify `/proc/<pid>/stat` before spawning to prevent duplicate runtime instances.
- **Panic Switches**: Both single extension termination (`void ext kill <id>`) and master panic termination (`void ext kill-all` / GUI panic button) must be maintained and verified.

### 5. Memory & Asynchronous Safety (Android LMK)
- Android's Low Memory Killer terminates heavy processes without warning. Keep test runs and sub-processes single-threaded; never use unrestricted parallel jobs (`make -j$(nproc)` is prohibited).
- **Node.js**: Never use `Promise.all()` for parallel API calls. Await sequentially (`for...of`).
- **SSE Streams**: Always implement string buffer split loops (`buffer.indexOf("\n\n")`).

---

## 4. Verification & QA Standard
Before committing changes to any language (Kotlin, Rust, Python, JavaScript):
1. **Universal QA Analyzer**:
   ```bash
   python3 ~/.gemini/config/skills/qa-analyzer/scripts/analyzer.py android/
   ```
2. **Interface Reference Integrity**: Verify all imported symbols, types, and methods exist before finalizing edits.
3. **Smoke Check**: Test script executions via CLI (`python3 voidpkg.py --help`, `python3 void_ext_router.py list`).

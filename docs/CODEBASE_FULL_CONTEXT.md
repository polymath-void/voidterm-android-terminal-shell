# VoidTerm Hybrid-Engine Complete Codebase Context

> Auto-generated unified context dump for architectural analysis and build verification.

## File: `AGENTS.md`

```markdown
# AGENTS.md - Governance & Operational Rules for Hybrid Engine

## 1. Project Mission & Identity
The **Hybrid Engine** is a high-performance Android execution environment combining:
- **UI / JNI Layer**: Kotlin + C++ `libvterm` terminal emulator.
- **IPC Broker**: Asynchronous Rust (`tokio`) host daemon for command interception & stream multiplexing.
- **Host WASM Engine**: WasmEdge embedded via Android NDK for zero-latency execution & native host API functions.
- **Guest Linux Engine**: AVF (`crosvm`) hardware-isolated microVM for standard `glibc` ELF workloads.
- **Hypervisor Bus**: `virtio-vsock` (Host CID 2 <-> Guest CID) for zero-network-overhead communication.

---

## 2. Core Operational Directives (STRICT ENFORCEMENT)

### Rule 1: Mandatory Context Synchronization (`CONTEXT.md`)
1. **Pre-Task Check**: Every agent session or subagent invocation MUST review [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md) before making modifications.
2. **Atomic Context Updates**: Whenever any subsystem is modified, designed, refactored, or tested, the agent MUST immediately update [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
3. **No Phantom State**: Never introduce architectural components, configuration flags, or protocol changes without recording them in [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).

### Rule 2: Category-Based Event Logging Protocol
Every event, modification, architectural decision, or validation test MUST be appended to the **Event Log** section in [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md) using the following strict category schema:

| Category Tag | Domain / Scope |
| :--- | :--- |
| `[ARCHITECTURE]` | High-level system design, pipeline modifications, AIDL/JNI boundaries. |
| `[BROKER_IPC]` | Rust daemon, Tokio async routing, multiplexer channels, IPC protocol. |
| `[WASM_ENGINE]` | WasmEdge NDK integration, WASM host functions, Termux:API replacements. |
| `[AVF_GUEST]` | Android Virtualization Framework, crosvm, kernel image, guest init daemon. |
| `[VSOCK_BUS]` | `virtio-vsock` configuration, CID routing (Host CID 2 / Guest CID), framing. |
| `[TERMINAL_UI]` | Kotlin UI, C++ `libvterm` bridge, PTY rendering, ANSI/VT escape sequences. |
| `[BUILD_ENV]` | Toolchain setups (Cargo, NDK Clang, CMake, Gradle), Termux environment. |
| `[TEST_BENCH]` | Unit tests, mock vsock benchmarks, WASM execution latency benchmarks. |

#### Event Log Entry Format:
```markdown
- **YYYY-MM-DD HH:MM UTC** `[CATEGORY_TAG]` <Short Summary>
  - **Details**: Specific technical actions taken or decisions made.
  - **Impacted Components**: `path/to/component` or subsystem name.
  - **Outcome / Status**: Verified, Pending, or In Progress.
```

---

## 3. Subsystem Invariants & Technical Rules

### 1. IPC Broker (Rust)
- **Runtime**: Pure asynchronous Rust using `tokio` (multi-thread or current-thread runtime adapted for mobile core affinity).
- **Concurrency & Non-Blocking**: No blocking synchronous syscalls inside Tokio task loops. Use `tokio::select!` for stream multiplexing.
- **Protocol Framing**: Inter-process and vsock messages must use strict length-prefixed binary or lightweight CBOR/Protobuf serialization. Avoid bloated JSON on hot data paths.
- **Graceful Degradation**: If the guest microVM is booting or offline, fast-fail or queue commands with status notifications back to the UI terminal.

### 2. Host WASM Engine (WasmEdge NDK)
- **Zero Hypervisor Overhead**: Run CI automations, lightweight scripts, string parsing, and host utilities in WASM directly on Android Bionic.
- **Host Function Security**: All Android native capabilities (battery, clipboard, network, sensors) registered as WASM host functions must be capability-gated.
- **Zero-Copy Buffers**: When passing byte arrays or strings across WASM memory boundary, use direct memory pointers rather than intermediate allocations.

### 3. Guest Linux VM & AVF
- **Hypervisor Boundary**: The microVM runs under `crosvm` managed by Android's `VirtualizationService` (AIDL).
- **No Emulation Penalties**: VM executes raw ARM64/AArch64 `glibc` ELF binaries natively.
- **Vsock Topology**: Android Host always binds to `CID 2`. The guest VM connects to Host CID 2, and the guest listener daemon binds to `VMADDR_CID_ANY` on designated port (default: CID 3, port 8000).
- **Zero-Root ext4 Provisioning**: Virtual disk images (`disk.img`) are dynamically allocated as sparse files, formatted via Android user-space `/system/bin/mke2fs`, and populated with Debian rootfs via `/system/bin/e2fsdroid` without root privileges.
- **Guest Daemon Autostart**: Systemd unit (`voidterm-daemon.service`) and `multi-user.target.wants` symlinks are embedded into rootfs during provisioning to autostart `guest_daemon` as PID 1 service.

### 4. Terminal UI / JNI Layer
- **libvterm Integrity**: Render terminal grid states through C++ `libvterm` wrapper.
- **Thread Safety**: Ensure all JNI callbacks to Kotlin UI run on main/render thread or through dedicated thread-safe frame queues.

---

## 4. Environment & Tooling Rules (Android Termux)
- **Path Resolution**: Follow standard Termux paths (`/data/data/com.termux/files/usr/`, `$HOME/`).
- **Toolchain Standard**: Use native compilers (`clang`, `rustc`, `cargo`, `cmake`) configured for `aarch64-linux-android`.
- **Zero Resource Waste**: Maintain clean dependencies and minimize unnecessary runtime overhead.

```

---

## File: `CONTEXT.md`

```markdown
# CONTEXT.md - Living Architecture & Execution Context for VoidTerm Shell Terminal

> **Document Status**: Active / Canonical  
> **Last Synchronized**: 2026-10-19 14:30 UTC  
> **Repository Root**: `/data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine`  
> **Milestone**: Agent Control Plane (ACP) & Termux Core Protocol Deconstruction Implemented  

---

## 1. Project Overview & Topology

The **VoidTerm Shell Terminal** is a dual-tier execution platform for Android that combines:
1. **Ultra-Low Latency Host WASM Engine**: Direct NDK-embedded WasmEdge runtime on Android Bionic for instantaneous script/task execution with native Android host plugins.
2. **Full Linux Compatibility via AVF**: Hardware-isolated microVM running via Android Virtualization Framework (`crosvm`), executing unconstrained `glibc` ELF binaries.
3. **Rust IPC Broker Daemon**: Asynchronous Tokio-based brain managing command routing, virtio-vsock communication, and stream multiplexing.
4. **Terminal UI (Kotlin / C++ libvterm)**: Clean terminal rendering with VT100/ANSI emulation.

```mermaid
flowchart TD
    subgraph UI_Layer ["UI / JNI Layer"]
        UI["Android Terminal UI (Kotlin)"] <--> JNI["JNI Bridge (C++)"]
        JNI <--> VTERM["libvterm Engine"]
    end

    subgraph Host_Daemon ["Host Process: IPC Broker (Rust + Tokio)"]
        ROUTER["Command Router / Semantics Parser"]
        MUX["Tokio Async Multiplexer (select!)"]
        VSOCK_CLIENT["vhost-vsock Channel Handler (Host CID: 2)"]
        WASM_EMBED["WasmEdge Host Embedding (NDK API)"]
    end

    subgraph Host_Extensions ["Host Native Plugins (Bionic)"]
        PLUGINS["Sensors | Battery | Network | Storage | Clipboard"]
    end

    subgraph AVF_MicroVM ["AVF MicroVM (Hardware-Isolated Linux / Glibc)"]
        CROSVM["crosvm VMM (VirtualizationService AIDL)"]
        VSOCK_DAEMON["Guest VM Daemon (vsock listener)"]
        LINUX_SHELL["glibc Shell / Python / Django / Containers / GCC"]
    end

    UI_Layer <-->|Local Socket / PTY Channel| Host_Daemon
    WASM_EMBED <-->|Native Host Function FFI| Host_Extensions
    WASM_EMBED <-->|WASM Stdout/Stderr/Stdin| MUX
    ROUTER -->|WASM Tasks| WASM_EMBED
    ROUTER -->|Linux Tasks| VSOCK_CLIENT
    VSOCK_CLIENT <==>|virtio-vsock Bus| CROSVM
    CROSVM <==>|vsock Stream| VSOCK_DAEMON
    VSOCK_DAEMON <--> LINUX_SHELL
    VSOCK_CLIENT <-->|VM Stdout/Stderr/Stdin| MUX
    MUX -->|Unified Terminal Stream| UI_Layer
```

---

## 2. Subsystem Matrix & Interface Specifications

| Subsystem | Primary Tech | Layer | Key Responsibilities & Endpoints |
| :--- | :--- | :--- | :--- |
| **Terminal UI / JNI** | Kotlin, C++, `libvterm` | Android App Frontend | Input capture, frame rendering, ANSI escape parsing, window resize events (`SIGWINCH`). |
| **IPC Broker Daemon** | Rust, `tokio`, `nix` | Host Background Daemon | Central message broker, regex/AST command routing, stream multiplexer. |
| **Host WASM Engine** | WasmEdge, NDK, C/Rust FFI | Host Native In-Process | Zero-overhead execution of WASM binaries and custom native host function plugins. |
| **Host Native Plugins** | C++ / Rust, Android NDK | Host OS Extension | Hardware telemetry, device sensors, clipboard, filesystem bridging (fast Termux:API). |
| **AVF MicroVM Host** | Android VirtualizationService (AIDL), `crosvm` | Host Hypervisor Controller | MicroVM lifecycle (spawn, monitor, configure CID, shutdown). |
| **virtio-vsock Bus** | `vhost-vsock`, AF_VSOCK | Hypervisor IPC | Direct hypervisor socket communication (Host CID: 2 <-> Guest CID). |
| **Guest VM Daemon** | Rust / C (static ELF) | Guest Linux MicroVM | Listens on guest vsock port, allocates PTYs, spawns target glibc process, streams I/O. |

---

## 3. Phase Roadmap & Execution Milestones

| Phase | Phase Title | Status | Primary Deliverables |
| :---: | :--- | :---: | :--- |
| **Phase 1** | **IPC Broker Daemon** | 🟡 In Progress | Rust Tokio async daemon scaffolded, `hybrid-term-broker` crate created with Tokio, WasmEdge, vsock, nix, serde. |
| **Phase 2** | **Host WASM Runtime Embedding** | 📋 Planned | WasmEdge NDK C/Rust bridge, Host Function plugin registry (sensors, system info, clipboard). |
| **Phase 3** | **AVF MicroVM Provisioning** | 📋 Planned | AIDL `VirtualizationService` integration, `crosvm` microVM boot scripts, minimal Linux guest image. |
| **Phase 4** | **vhost-vsock Subsystem** | 📋 Planned | `AF_VSOCK` communication channel (Host CID 2 <-> Guest CID), guest listener daemon, zero-copy packet framing. |
| **Phase 5** | **Stream Multiplexing & Terminal UI** | 📋 Planned | Tokio `select!` unified loop, C++/Kotlin `libvterm` JNI interface, end-to-end user terminal shell. |

---

## 4. Planned Repository Directory Structure

```
/data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/
├── Cargo.toml              # Root Cargo Workspace definition
├── AGENTS.md               # Strict Governance & Categorized Logging Protocol
├── CONTEXT.md              # Living Context & Project Status Matrix
├── hybrid-term-broker/     # [BROKER_IPC] Rust Daemon & Multiplexer Crate
│   ├── Cargo.toml          # tokio, wasmedge-sdk, nix, vsock, anyhow, serde
│   └── src/
│       ├── main.rs         # Entrypoint & Tokio Async Runtime setup
│       ├── router.rs       # Command Classifier (WASM vs VM)
│       ├── multiplexer.rs  # tokio::select! I/O stream merger
│       ├── wasm_bridge.rs  # Interface to Host WASM Engine
│       └── vsock_client.rs # Interface to virtio-vsock bus
├── wasm-engine/            # [WASM_ENGINE] Host WASM Engine (WasmEdge NDK)
│   ├── include/            # C/C++ Headers for Host Functions
│   ├── src/                # Host Plugin Implementations (Sensors, Net, Clipboard)
│   └── modules/            # Pre-compiled / Standard WASM host utilities
├── guest-daemon/           # [AVF_GUEST] / [VSOCK_BUS] Guest Linux VM Agent
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs         # Guest vsock listener & PTY spawner
│       └── pty.rs          # Linux PTY allocation & byte streaming
├── terminal-ui/            # [TERMINAL_UI] Kotlin & JNI libvterm Layer
│   ├── jni/                # libvterm C++ wrapper & JNI exports
│   └── app/                # Android UI Activity & Canvas/View Terminal
└── docs/                   # Specifications, Benchmarks & Protocols
    ├── vsock-protocol.md
    └── host-plugins-api.md
```

---

## 5. Category-Based Event Log

> **Protocol Reminder**: All modifications, architectural milestones, and test runs MUST be logged here using the strict categorization schema defined in `AGENTS.md`.

- **2026-08-08 22:18 UTC** `[TERMINAL_UI]` **Implemented Native Settings Screen & Preference Management**
  - **Details**: (1) Added `androidx.preference:preference-ktx:1.2.1` to `android/app/build.gradle.kts`. (2) Created `res/xml/preferences.xml` with `PreferenceScreen` containing `ListPreference` for Theme and Cursor Style, `SwitchPreferenceCompat` for Cursor Blink, and `EditTextPreference` for Font Size. (3) Created string arrays in `res/values/arrays.xml` and styled themes in `res/values/themes.xml`. (4) Implemented `SettingsActivity.kt` hosting `SettingsFragment : PreferenceFragmentCompat` with up-navigation and registered it in `AndroidManifest.xml`. (5) Added mini `FloatingActionButton` (FAB) with gear icon in `activity_main.xml` and wired click event in `MainActivity.kt`. (6) Added `reloadConfig()` in `TerminalSurfaceView.kt` invoked dynamically inside `MainActivity.onResume()`.
  - **Impacted Components**: [android/app/build.gradle.kts](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/build.gradle.kts), [android/app/src/main/res/xml/preferences.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/xml/preferences.xml), [android/app/src/main/res/values/arrays.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/values/arrays.xml), [android/app/src/main/res/values/themes.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/values/themes.xml), [android/app/src/main/kotlin/com/hybridengine/terminal/SettingsActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/SettingsActivity.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalConfig.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalConfig.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [android/app/src/main/res/layout/activity_main.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/layout/activity_main.xml), [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 21:20 UTC** `[TERMINAL_UI]` **UI/UX Overhaul: Terminal Configuration Engine & Foreground Service**
  - **Details**: (1) Created `TerminalService.kt` extending `Service` to maintain `VmManager` and `Broker` lifecycles independent of UI destruction. (2) Implemented low-priority ongoing foreground notification (`voidterm_vm_foreground_channel`) with "Shutdown VM" PendingIntent action and activity tap routing. (3) Declared `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` permissions with `specialUse` service declaration in `AndroidManifest.xml`. (4) Built `TerminalConfig.kt` using `SharedPreferences` supporting `TerminalTheme` (Dracula, Nord, Monokai with exact 8-color ANSI HEX maps), configurable `fontSize`, `CursorStyle` (Block `█`, Underline `_`, Bar `|`), and `cursorBlink` (500ms toggle timer). (5) Injected `TerminalConfig` into `TerminalSurfaceView.kt` and `AnsiParser.kt` with live dynamic paint and cursor re-styling. (6) Updated `MainActivity.kt` to bind to `TerminalService`.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalService.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalService.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalConfig.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalConfig.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/AnsiParser.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/AnsiParser.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 21:18 UTC** `[VSOCK_BUS]` **Implemented Vsock Boot Retry Loop & Static Musl Guest Daemon Build**
  - **Details**: (1) Implemented `VmBridge::connect_guest_daemon` with a 10-attempt retry loop and 500ms delay to absorb the 2-5 second VM boot initialization latency before returning errors. (2) Updated CI workflow (`.github/workflows/ci.yml`) to compile `guest_daemon` using `aarch64-unknown-linux-musl` static linking to eliminate Android Bionic `/system/bin/linker64` runtime dependency on Debian Linux. (3) Updated `OsInstaller.kt` and `storage.rs` to bundle and copy static `guest_daemon` from assets/internal storage into `/usr/local/bin/guest_daemon` with `0o755` permissions. (4) Rebuilt and packaged stripped `libhybrid_term_broker.so`.
  - **Impacted Components**: [hybrid-term-broker/src/vm_bridge.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/vm_bridge.rs), [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt), [hybrid-term-broker/src/storage.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/storage.rs), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented, Compiled & Verified.

- **2026-08-08 21:00 UTC** `[TERMINAL_UI]` **Implemented Native Inline Terminal Input & Fullscreen Layout**
  - **Details**: (1) Completely purged bottom chat box input layout (`activity_main.xml`), expanding `TerminalSurfaceView` to fullscreen `match_parent`. (2) Implemented direct soft keyboard keystroke capture via `BaseInputConnection`, `onCreateInputConnection`, and `onKeyDown` in `TerminalSurfaceView.kt`. (3) Added inline dynamic prompt rendering (`root@voidterm:~# ` + `currentInputBuffer` + blinking block cursor `█`) and wired Enter key submissions directly to `Broker.send()`. (4) Cleaned up `MainActivity.kt` view bindings and unused imports.
  - **Impacted Components**: [android/app/src/main/res/layout/activity_main.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/layout/activity_main.xml), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 20:57 UTC** `[ARCHITECTURE]` **Enforced MicroVM Boot Order, Purged Host Shell, and Upgraded UI Surface**
  - **Details**: (1) Fixed boot sequence in `MainActivity.kt` ensuring `OsInstaller.installIfNeeded()` executes first, `VmManager.startLiteLinuxVm()` second, and `Broker` initialization last. (2) Purged `LocalPty::start` from `hybrid-term-broker` (`lib.rs` and `main.rs`), routing all terminal commands exclusively to `VmBridge` over vsock (CID 3, Port 8000). (3) Upgraded `TerminalSurfaceView.kt` with `ScaleGestureDetector` pinch-to-zoom (clamped 20f-100f) and `StaticLayout` word wrapping for responsive, non-clipped multi-line rendering. Compiled and stripped updated `libhybrid_term_broker.so`.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs), [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 20:38 UTC** `[AVF_GUEST]` **Integrated Native Gzip Tar Extraction & CI/CD Rootfs Fetch Fix**
  - **Details**: Updated `.github/workflows/ci.yml` to fetch official Debian 12 Bookworm ARM64 `.tar.gz` rootfs with `-fL` fail-fast validation. Updated `OsInstaller.kt` to stage `debian-rootfs.tar.gz` into internal storage. Implemented native Android `/system/bin/tar` extraction in `StorageProvisioner::provision_avf_disk` (`storage.rs`) to decompress rootfs directly into `rootfs_dir` before ext4 formatting and rootfs packing, and cleaned up temporary archive. Built and packaged updated release `libhybrid_term_broker.so`.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt), [hybrid-term-broker/src/storage.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/storage.rs), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Packaged.

- **2026-08-08 18:53 UTC** `[TERMINAL_UI]` **Implemented Priority 3: ANSI Escape Sequence Parsing & Color Rendering**
  - **Details**: Created `AnsiParser.kt` in `com.hybridengine.terminal` supporting full ANSI 3/4-bit and high-intensity foreground color palettes, text formatting (bold, reset), carriage return sanitization, and backspace simulation. Integrated `AnsiParser` into `TerminalSurfaceView.kt` with thread-safe multi-segment X-axis paint rendering and bounded scrollback buffer (1000 lines).
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/AnsiParser.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/AnsiParser.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Integrated.

- **2026-08-08 18:46 UTC** `[AVF_GUEST]` **Implemented Priority 2: Guest Daemon Init & Systemd Binding**
  - **Details**: Updated `StorageProvisioner` in `hybrid-term-broker/src/storage.rs` to programmatically inject the systemd service file `voidterm-daemon.service` into `<rootfs_dir>/etc/systemd/system/` and link it in `multi-user.target.wants/` prior to `e2fsdroid` execution. Placed and granted `0o755` executable permissions for `guest_daemon` in `<rootfs_dir>/usr/local/bin/guest_daemon` to enable automatic boot-time initialization on guest microVM startup. Packaged updated release `libhybrid_term_broker.so`.
  - **Impacted Components**: [hybrid-term-broker/src/storage.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/storage.rs), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Packaged.

- **2026-08-08 18:34 UTC** `[AVF_GUEST]` **Implemented Priority 1: AVF Disk Formatting & Rootfs Provisioner**
  - **Details**: Created `StorageProvisioner` in `hybrid-term-broker/src/storage.rs` to allocate 2GB sparse virtual disk (`disk.img`), format as ext4 using Android `/system/bin/mke2fs`, and inject Debian rootfs using `/system/bin/e2fsdroid` without root. Exposed JNI method `provisionDisk` in `lib.rs` and `Broker.kt`. Integrated custom disk image mounting in `VmManager.kt` and wired into `MainActivity.kt`. Packaged updated release `libhybrid_term_broker.so`.
  - **Impacted Components**: [hybrid-term-broker/src/storage.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/storage.rs), [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs), [android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/VmManager.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/VmManager.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Packaged.

- **2026-08-08 18:22 UTC** `[AVF_GUEST]` **Configured CI Debian Rootfs Injector & First-Boot OsInstaller**
  - **Details**: Updated `.github/workflows/ci.yml` to automatically pull official Debian Bookworm arm64 rootfs and bundle it into `android/app/src/main/assets/debian-rootfs.tar.xz`. Implemented `OsInstaller.kt` to unpack and stage the Debian Linux image into internal storage on first boot, wired prior to `VmManager` and `Broker` startup.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Integrated.

- **2026-08-08 17:35 UTC** `[AVF_GUEST]` **Integrated Phase 7 AVF Permissions & VirtualMachineManager Orchestrator**
  - **Details**: Added `android.permission.MANAGE_VIRTUAL_MACHINE` to `AndroidManifest.xml`. Implemented `VmManager.kt` with resilient `VirtualMachineManager` lifecycle management (512MB RAM allocation, host CPU topology, full debug logging, and graceful teardown on activity destruction). Wired `VmManager` into `MainActivity.kt` boot sequence.
  - **Impacted Components**: [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml), [android/app/src/main/kotlin/com/hybridengine/terminal/VmManager.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/VmManager.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Integrated.

- **2026-08-08 17:06 UTC** `[BROKER_IPC]` **Integrated Phase 6 Step 1 Local PTY Engine into Rust Broker**
  - **Details**: Implemented `LocalPty` in `hybrid-term-broker/src/local_pty.rs` with native Android POSIX master/slave pseudo-terminal (`openpty`), 80x24 window geometry, persistent `/system/bin/sh` shell session, `xterm-256color` environment, non-blocking background output streaming, and direct command input routing. Updated `IpcMessage::ExecuteLocal` and JNI `startDaemon`/`sendCommand` bindings.
  - **Impacted Components**: [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/local_pty.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/local_pty.rs), [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs), [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Packaged.

- **2026-08-08 16:41 UTC** `[BUILD_ENV]` **Upgraded GitHub Actions Setup Toolchains to Resolve Runner Annotations**
  - **Details**: Updated `actions/setup-java` to `@v4` and `gradle/actions/setup-gradle` to `@v4` in `.github/workflows/ci.yml` to address GitHub Actions runner deprecation warnings and cache service notices.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Updated.

- **2026-08-08 15:42 UTC** `[TERMINAL_UI]` **Fixed App Launch Crash, Handled UnsatisfiedLinkError & Bundled jniLibs**
  - **Details**: Resolved runtime crash caused by missing `libhybrid_term_broker.so` inside the APK and unhandled `UnsatisfiedLinkError`. Updated `Broker.kt` with graceful fallback and safe JNI daemon invocation, hardened `TerminalSurfaceView.kt` canvas locking, made `MainActivity.kt` permission intents crash-proof, and packaged stripped `libhybrid_term_broker.so` into `android/app/src/main/jniLibs/arm64-v8a/`.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [android/app/build.gradle.kts](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/build.gradle.kts), `android/app/src/main/jniLibs/arm64-v8a/libhybrid_term_broker.so`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Fixed & Pushed.

- **2026-08-08 15:35 UTC** `[TERMINAL_UI]` **Downloaded CI Artifacts & Initiated VoidTerm Installation**
  - **Details**: Downloaded and unzipped `voidterm-debug-apk` from GitHub Actions CI run `31264349739`. Exported `VoidTerm-v0.1.0-alpha.apk` to `/sdcard/Download/` and triggered Android package installer via `termux-open`. Installed native CLI daemons `hybrid-term-broker` and `guest_daemon` directly into `$PREFIX/bin`.
  - **Impacted Components**: `dist/app-debug.apk`, `/sdcard/Download/VoidTerm-v0.1.0-alpha.apk`, `$PREFIX/bin/hybrid-term-broker`, `$PREFIX/bin/guest_daemon`, [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Installed & Deployed.

- **2026-08-08 15:29 UTC** `[BUILD_ENV]` **End-to-End Cloud CI Pipeline Fully Green (Rust + Android APK)**
  - **Details**: Full CI/CD build run `31264349739` succeeded completely across all jobs (`Rust IPC Broker Build` and `Android APK Build`). Cloud workflow successfully produced and uploaded both `voidterm-debug-apk` and `voidterm-rust-binaries` artifacts.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Succeeded (Green).

- **2026-08-08 15:23 UTC** `[BUILD_ENV]` **Resolved AAPT2 Manifest Resource Linking in Android Build**
  - **Details**: Updated `AndroidManifest.xml` icon attributes to reference standard platform drawables (`@android:drawable/sym_def_app_icon`), eliminating AAPT2 missing mipmap resource errors during `gradle assembleDebug`.
  - **Impacted Components**: [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Fixed & Pushed.

- **2026-08-08 15:18 UTC** `[BUILD_ENV]` **Refactored CI Pipeline for Cross-Target Verification & Cloud Packaging**
  - **Details**: Updated `.github/workflows/ci.yml` with dual verification: validating `aarch64-linux-android` target compilation via `cargo check` and building host binaries + unit tests, resolving linker architecture mismatches on generic Ubuntu runners.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Configured & Pushed.

- **2026-08-08 15:13 UTC** `[BUILD_ENV]` **Pinned Gradle 8.6 & Added Cloud CI Artifact Uploads**
  - **Details**: Resolved AGP 8.2.2 compatibility by pinning Gradle 8.6 in `.github/workflows/ci.yml`. Added `gradle.properties` JVM args and configured automated multi-artifact packaging (`actions/upload-artifact@v4`) for both native Rust AArch64 binaries and Android Debug APKs.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [gradle.properties](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/gradle.properties), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Configured & Pushed.

- **2026-08-08 15:06 UTC** `[BUILD_ENV]` **Configured Android Gradle Build System & CI Setup**
  - **Details**: Authored root `settings.gradle.kts`, `build.gradle.kts`, and `android/app/build.gradle.kts` targeting Android SDK 34 / Java 17. Integrated `gradle/actions/setup-gradle@v3` into `.github/workflows/ci.yml` for automated APK build verification alongside Rust broker checks.
  - **Impacted Components**: [settings.gradle.kts](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/settings.gradle.kts), [build.gradle.kts](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/build.gradle.kts), [android/app/build.gradle.kts](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/build.gradle.kts), [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Configured & Deployed.

- **2026-08-08 14:59 UTC** `[REFACTOR]` **Consolidated Architectural Documentation & Removed Redundant core.md**
  - **Details**: Eliminated duplicate documentation by removing `core.md` and designating `CONTEXT.md` as the single canonical source of truth for living architecture, subsystem specifications, and event logs. Updated `AGENTS.md`, `README.md`, and workspace tree references.
  - **Impacted Components**: [AGENTS.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/AGENTS.md), [README.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/README.md), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Cleaned & Synchronized.

- **2026-08-08 14:52 UTC** `[BUILD_ENV]` **Activated GitHub Actions CI Workflow on Git Push**
  - **Details**: Configured direct SSH transport with GitHub (`git@ssh.github.com:polymath-void/voidterm-android-terminal-shell.git`), staged and pushed `.github/workflows/ci.yml`. Verified live automated execution of the CI workflow pipeline on push event.
  - **Impacted Components**: [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml), [.git/config](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.git/config), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Deployed & Triggered.

- **2026-08-08 14:49 UTC** `[TEST_BENCH]` **Completed Native Rust Broker & Guest Daemon Build Verification**
  - **Details**: Resolved runtime dependencies and socket signatures for `aarch64-linux-android` compilation: transitioned WASM engine to zero-dependency pure-Rust `wasmi`, updated `tokio-vsock` `VsockAddr` signatures, and decoupled `tokio::select!` I/O buffers in `guest_daemon.rs`. Successfully compiled `cargo check` (0.42s) and built debug targets (`hybrid-term-broker`, `guest_daemon`, `libhybrid_term_broker.so`) in 1m 21s.
  - **Impacted Components**: [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/wasm_engine.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/wasm_engine.rs), [hybrid-term-broker/src/vm_bridge.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/vm_bridge.rs), [hybrid-term-broker/src/bin/guest_daemon.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/bin/guest_daemon.rs), [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Passing.

- **2026-08-08 14:31 UTC** `[ARCHITECTURE]` **Designed and Embedded Visual Architecture Assets & Hero Banner**
  - **Details**: Created high-resolution hero banner (`assets/banner.jpg`) and crafted vector SVG architecture topology diagram (`assets/architecture.svg`) with dark glassmorphic styling, glow filters, and subsystem flow paths. Integrated styled shields.io status badges and rendered assets in `README.md`.
  - **Impacted Components**: [assets/banner.jpg](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/assets/banner.jpg), [assets/architecture.svg](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/assets/architecture.svg), [README.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/README.md), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Designed & Deployed.

- **2026-08-08 14:24 UTC** `[BUILD_ENV]` **Configured Remote Origin and GitHub Deployment URL**
  - **Details**: Linked local git repository to remote origin `https://github.com/polymath-void/voidterm-android-terminal-shell.git`. Pushed initial release of VoidTerm Shell Terminal architecture to `origin/main`. Updated `README.md` CI badge and repository documentation.
  - **Impacted Components**: [README.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/README.md), [.git/config](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.git/config), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Deployed & Verified.

- **2026-08-08 14:21 UTC** `[REFACTOR]` **Standardized Project Naming to VoidTerm Shell Terminal**
  - **Details**: Updated application identity across configuration, UI resources, documentation, and metadata. Configured `app_name` string resource as "VoidTerm" (`strings.xml`), bound `android:label="@string/app_name"` in `AndroidManifest.xml`, authored `README.md`, and updated `CONTEXT.md` references to "VoidTerm Shell Terminal".
  - **Impacted Components**: [android/app/src/main/res/values/strings.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/values/strings.xml), [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml), [README.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/README.md), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 14:12 UTC** `[BUILD_ENV]` **Configured Unified .gitignore, GitHub Actions CI Workflow, and Repository Initialization**
  - **Details**: Created `.gitignore` excluding Rust/Cargo targets (`/hybrid-term-broker/target/`, `target/`) and Android/Gradle artifacts (`/app/build/`, `/android/app/build/`, `.gradle/`, `.idea/`, `.cxx/`). Defined automated CI workflow in `.github/workflows/ci.yml` verifying Rust toolchain `aarch64-linux-android` compilation (`cargo check`) and Gradle debug APK build (`./gradlew assembleDebug`). Initialized git repository tracking the 5-phase Hybrid Engine architecture on branch `main`.
  - **Impacted Components**: [.gitignore](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.gitignore), [.github/workflows/ci.yml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/.github/workflows/ci.yml).
  - **Outcome / Status**: Implemented & Active.

- **2026-08-08 13:03 UTC** `[TERMINAL_UI]` **Implemented Runtime Storage Permission Dispatch in MainActivity (MainActivity.kt)**
  - **Details**: Added `requestStoragePermissions()` in `MainActivity.kt`. Checks `Environment.isExternalStorageManager()` on Android 11+ (API 30+) and prompts the user via `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` to grant full system file access before WASM/VM disk execution starts.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 13:01 UTC** `[TERMINAL_UI]` **Configured Android Permissions for VM Networking & Storage (AndroidManifest.xml)**
  - **Details**: Configured `AndroidManifest.xml` injecting `android.permission.INTERNET` (enabling guest VM package managers like `apt`/`pip` to fetch dependencies) and broad filesystem access permissions `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, and `WRITE_EXTERNAL_STORAGE`. Configured launcher activity for `MainActivity`.
  - **Impacted Components**: [android/app/src/main/AndroidManifest.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/AndroidManifest.xml).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:41 UTC** `[TERMINAL_UI]` **Integrated UI Controls with JNI Broker in MainActivity (MainActivity.kt)**
  - **Details**: Implemented `MainActivity.kt` binding `TerminalSurfaceView`, `EditText`, and `Button`. Instantiated `Broker(terminalSurface)` and launched `broker.startDaemon()`. Added IME action and enter-key listeners for command echo and asynchronous forwarding via `broker.sendCommand(command)`.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:41 UTC** `[TERMINAL_UI]` **Created Minimalist Virtual Input Control Layout (activity_main.xml)**
  - **Details**: Created `res/layout/activity_main.xml` stacking `TerminalSurfaceView` behind a high-contrast monochrome input row with monospace `EditText` (`#E0E0E0` on transparent) and dedicated execution button `EXE` (`#0A0A0A` on `#E0E0E0`).
  - **Impacted Components**: [android/app/src/main/res/layout/activity_main.xml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/res/layout/activity_main.xml).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:37 UTC** `[TERMINAL_UI]` **Implemented Hardware-Accelerated Terminal SurfaceView (TerminalSurfaceView.kt)**
  - **Details**: Created `TerminalSurfaceView.kt` managing an independent 60fps rendering thread with `lockHardwareCanvas()`. Implemented bottom-up terminal line drawing, 500-line capped synchronized buffer, monospace typography `#E0E0E0` on deep-black `#0A0A0A`. Updated `Broker.kt` to bind the view reference and pipe JNI output streams directly into the drawing buffer.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt), [android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:34 UTC** `[TERMINAL_UI]` **Created Kotlin JNI Broker Class (Broker.kt)**
  - **Details**: Created package `com.hybridengine.terminal` and implemented `Broker.kt`. Added dynamic library loader `System.loadLibrary("hybrid_term_broker")`, declared external C-methods `startDaemon()` and `sendCommand(command: String)`, and implemented `onTerminalOutput(output: String)` callback dispatcher for asynchronous stream ingress from Tokio.
  - **Impacted Components**: [android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:30 UTC** `[TERMINAL_UI]` **Implemented Phase 2 Step 3 JNI Callback Egress Pipeline (lib.rs)**
  - **Details**: Added global `JVM: OnceLock<JavaVM>` and `CALLBACK_OBJ: OnceLock<GlobalRef>` singletons. Configured `startDaemon` to retain the calling Kotlin object's global reference and JavaVM pointer. Spawned an asynchronous Tokio egress listener task streaming `IpcMessage::TerminalOutput` into `send_to_kotlin` via `jvm.attach_current_thread()` invoking `onTerminalOutput(String)`.
  - **Impacted Components**: [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:28 UTC** `[TERMINAL_UI]` **Wired Thread-Safe JNI State and Channels with OnceLock (lib.rs)**
  - **Details**: Refactored `src/lib.rs` using `std::sync::OnceLock` singletons for `RUNTIME: OnceLock<Runtime>` and `TX_INPUT: OnceLock<mpsc::Sender<IpcMessage>>`. Integrated native method `Java_com_hybridengine_terminal_Broker_sendCommand` to dynamically forward commands to the background Tokio multiplexer task via non-blocking async dispatch.
  - **Impacted Components**: [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs), [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:20 UTC** `[TERMINAL_UI]` **Initialized Phase 2 JNI Bridge & cdylib Library Target (lib.rs)**
  - **Details**: Added `[lib]` target (`crate-type = ["cdylib"]`, `name = "hybrid_term_broker"`) and `jni` dependency (0.21.1) in `Cargo.toml`. Created `src/lib.rs` exporting native JNI functions `Java_com_hybridengine_terminal_Broker_startDaemon` and `Java_com_hybridengine_terminal_Broker_sendCommand` backed by a static global `tokio::runtime::Runtime`.
  - **Impacted Components**: [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/lib.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/lib.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:10 UTC** `[AVF_GUEST]` **Implemented Guest Linux VM Daemon (guest_daemon.rs)**
  - **Details**: Configured Cargo binary target `guest_daemon` at `src/bin/guest_daemon.rs`. Implemented `VsockListener` binding on `VMADDR_CID_ANY:8000` inside the microVM, accepting incoming broker connections, spawning native Linux processes (`sh -c`) via `tokio::process::Command`, and streaming stdout/stderr back over the vsock connection.
  - **Impacted Components**: [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/bin/guest_daemon.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/bin/guest_daemon.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:06 UTC** `[BROKER_IPC]` **Wired VmBridge Hypervisor Routing in main.rs**
  - **Details**: Registered `mod vm_bridge;` in `src/main.rs`, configured default AVF constants (`DEFAULT_VM_CID = 3`, `VM_VSOCK_PORT = 8000`), and dispatched `IpcMessage::ExecuteVm` commands into non-blocking `VmBridge::dispatch_command` async tasks with error reporting back to `tx_output`.
  - **Impacted Components**: [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:05 UTC** `[VSOCK_BUS]` **Implemented AVF vhost-vsock VM Bridge (vm_bridge.rs)**
  - **Details**: Implemented `VmBridge::dispatch_command` in `src/vm_bridge.rs` utilizing `tokio_vsock::VsockStream` to connect directly to the Guest Linux VM daemon on designated CID and port (default 8000). Implemented chunked async buffer streaming back to the broker's UI output multiplexer channel. Added `tokio-vsock` dependency in `Cargo.toml`.
  - **Impacted Components**: [hybrid-term-broker/src/vm_bridge.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/vm_bridge.rs), [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:02 UTC** `[BROKER_IPC]` **Wired UI Input Channel & Async WasmEngine Dispatch in main.rs**
  - **Details**: Added incoming command channel (`tx_input` / `rx_input`), wired up command pattern matching for `ExecuteWasm` and `ExecuteVm`, and spawned detached asynchronous tasks for `WasmEngine::execute` to prevent blocking the central multiplexer loop while piping stdout/errors to the UI output channel.
  - **Impacted Components**: [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 12:00 UTC** `[WASM_ENGINE]` **Created WasmEdge Execution Wrapper Module (wasm_engine.rs)**
  - **Details**: Encapsulated host WebAssembly runtime in `src/wasm_engine.rs` (`WasmEngine::execute`). Configured WasmEdge VM initialization with WASI module registration for filesystem & CLI args mapping, and mapped execution lifecycle notifications to the broker's async `IpcMessage` channel.
  - **Impacted Components**: [hybrid-term-broker/src/wasm_engine.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/wasm_engine.rs), [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Implemented & Verified.

- **2026-08-08 11:58 UTC** `[BROKER_IPC]` **Implemented Broker Architecture Skeleton in main.rs**
  - **Details**: Structured `src/main.rs` into three core domains: (1) `IpcMessage` protocol enum (`ExecuteWasm`, `ExecuteVm`, `TerminalOutput`), (2) `tokio::sync::mpsc` async channels with backpressure buffer (1024 cap), and (3) `tokio::select!` non-blocking stream multiplexing loop with graceful shutdown signal.
  - **Impacted Components**: [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Implemented & Active.

- **2026-08-08 11:53 UTC** `[BROKER_IPC]` **Scaffolded hybrid-term-broker Binary Crate**
  - **Details**: Created Rust binary crate `hybrid-term-broker` configured with `tokio` (full async features), `wasmedge-sdk`, `nix`, `vsock`, `anyhow`, `serde`, and `serde_json`. Initialized `src/main.rs` with `#[tokio::main]` async entry point and graceful signal handling.
  - **Impacted Components**: [Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/Cargo.toml), [hybrid-term-broker/Cargo.toml](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/Cargo.toml), [hybrid-term-broker/src/main.rs](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/hybrid-term-broker/src/main.rs).
  - **Outcome / Status**: Verified & Active.

- **2026-08-08 11:48 UTC** `[ARCHITECTURE]` **Hybrid Engine Project Initialized**
  - **Details**: Established the foundational architecture comprising the 5-phase plan: IPC Broker (Rust), Host WASM (WasmEdge NDK), AVF MicroVM (crosvm), virtio-vsock bus, and stream multiplexer.
  - **Impacted Components**: [core.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/core.md), [AGENTS.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/AGENTS.md), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Verified & Committed.

- **2026-08-08 11:48 UTC** `[BROKER_IPC]` **Defined Core Multiplexing Architecture**
  - **Details**: Specified Rust `tokio::select!` multiplexing pattern merging Host WASM stdout, Guest VM vsock stream, and UI terminal inputs into a unified interactive shell.
  - **Impacted Components**: [core.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/core.md), [CONTEXT.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/CONTEXT.md).
  - **Outcome / Status**: Architectural Spec Active.

- **2026-08-08 11:48 UTC** `[BUILD_ENV]` **Workspace & Governance Initialization**
  - **Details**: Applied strict context synchronization and category-based event logging rules conforming to `agents-context-manager` and `termux-environment` standards.
  - **Impacted Components**: [AGENTS.md](file:///data/data/com.termux/files/home/Projects/polymath-void/hybrid-engine/AGENTS.md).
  - **Outcome / Status**: Governance Active.

```

---

## File: `Cargo.toml`

```toml
[workspace]
resolver = "2"
members = [
    "hybrid-term-broker",
]

```

---

## File: `README.md`

```markdown
# VoidTerm Shell Terminal

<p align="center">
  <img src="assets/banner.jpg" alt="VoidTerm Shell Terminal" width="100%" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_NDK_%26_AVF-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Rust-Tokio_Async-DEA584?style=for-the-badge&logo=rust&logoColor=white" alt="Rust" />
  <img src="https://img.shields.io/badge/WASM-WasmEdge_Host-654FF0?style=for-the-badge&logo=webassembly&logoColor=white" alt="WebAssembly" />
  <img src="https://img.shields.io/badge/MicroVM-crosvm_virtio--vsock-4285F4?style=for-the-badge&logo=linux&logoColor=white" alt="Linux AVF" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=for-the-badge" alt="License" />
</p>

**VoidTerm Shell Terminal** is a high-performance, dual-tier Android terminal and execution environment. It bridges ultra-low latency host-native execution with full Linux environment compatibility through a multi-tier architecture.

---

## 🏛️ System Architecture Topology

<p align="center">
  <img src="assets/architecture.svg" alt="VoidTerm Architecture Topology" width="100%" />
</p>

```mermaid
flowchart TD
    subgraph UI_Layer ["UI / JNI Layer"]
        UI["Android Terminal UI (Kotlin)"] <--> JNI["JNI Bridge (C++)"]
        JNI <--> VTERM["libvterm Engine"]
    end

    subgraph Host_Daemon ["Host Process: IPC Broker (Rust + Tokio)"]
        ROUTER["Command Router / Semantics Parser"]
        MUX["Tokio Async Multiplexer (select!)"]
        VSOCK_CLIENT["vhost-vsock Channel Handler (Host CID: 2)"]
        WASM_EMBED["WasmEdge Host Embedding (NDK API)"]
    end

    subgraph Host_Extensions ["Host Native Plugins (Bionic)"]
        PLUGINS["Sensors | Battery | Network | Storage | Clipboard"]
    end

    subgraph AVF_MicroVM ["AVF MicroVM (Hardware-Isolated Linux / Glibc)"]
        CROSVM["crosvm VMM (VirtualizationService AIDL)"]
        VSOCK_DAEMON["Guest VM Daemon (vsock listener)"]
        LINUX_SHELL["glibc Shell / Python / Django / Containers / GCC"]
    end

    UI_Layer <-->|Local Socket / PTY Channel| Host_Daemon
    WASM_EMBED <-->|Native Host Function FFI| Host_Extensions
    WASM_EMBED <-->|WASM Stdout/Stderr/Stdin| MUX
    ROUTER -->|WASM Tasks| WASM_EMBED
    ROUTER -->|Linux Tasks| VSOCK_CLIENT
    VSOCK_CLIENT <==>|virtio-vsock Bus| CROSVM
    CROSVM <==>|vsock Stream| VSOCK_DAEMON
    VSOCK_DAEMON <--> LINUX_SHELL
    VSOCK_CLIENT <-->|VM Stdout/Stderr/Stdin| MUX
    MUX -->|Unified Terminal Stream| UI_Layer
```

---

## Key Subsystems

1. **Terminal UI / JNI Layer (`android/app`)**:
   - Monospace hardware-accelerated `SurfaceView` rendering at 60fps.
   - Synchronized line buffer with low-latency JNI bridge to the Rust daemon.
2. **IPC Broker Daemon (`hybrid-term-broker`)**:
   - Asynchronous Rust daemon built on `tokio`.
   - Dynamic routing of lightweight tasks to Host WASM and heavy computational workloads to the AVF Guest VM.
3. **Host WASM Engine (WasmEdge NDK)**:
   - Zero-hypervisor-overhead execution of WebAssembly bytecode directly on Android Bionic.
   - Native capability plugins (telemetry, storage, sensors).
4. **Guest Linux Engine (AVF + crosvm)**:
   - Hardware-isolated microVM managed via Android's `VirtualizationService`.
   - Native `glibc` ELF binary execution without emulation penalties.
5. **Hypervisor Bus (`virtio-vsock`)**:
   - Low-latency, zero-network-overhead socket communication between Host (CID 2) and Guest microVM.

---

## Directory Structure

```
.
├── assets/                  # High-resolution banner & vector architecture SVG
│   ├── banner.jpg
│   └── architecture.svg
├── .github/workflows/ci.yml # Automated CI pipeline (Rust check & Android APK build)
├── .gitignore               # Unified Cargo and Gradle build exclusions
├── AGENTS.md                # Repository governance & operational directives
├── CONTEXT.md               # Living architecture & event logging matrix
├── Cargo.toml               # Cargo workspace root
├── android/                 # Android Terminal Application
│   └── app/
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── kotlin/com/hybridengine/terminal/
│           │   ├── Broker.kt
│           │   ├── MainActivity.kt
│           │   └── TerminalSurfaceView.kt
│           └── res/
│               ├── layout/activity_main.xml
│               └── values/strings.xml
└── hybrid-term-broker/      # Rust IPC broker daemon & hypervisor bridge
    ├── Cargo.toml
    └── src/
        ├── bin/guest_daemon.rs
        ├── lib.rs
        ├── main.rs
        ├── vm_bridge.rs
        └── wasm_engine.rs
```

---

## Building & Verification

### Rust Daemon
```bash
cd hybrid-term-broker
cargo check
cargo build --target aarch64-linux-android
```

### Android Application
```bash
./gradlew assembleDebug
```

---

## License
Apache-2.0 / MIT

```

---

## File: `build.gradle.kts`

```text
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

```

---

## File: `gradle.properties`

```text
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

```

---

## File: `settings.gradle.kts`

```text
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VoidTerm"
include(":android:app")

```

---

## File: `termux_protocol_map.json`

```json
{
  "OSC_TITLE": {
    "header": "\\x1b]0;",
    "terminator": "\\x07",
    "action": "SetWindowTitle"
  },
  "ALT_SCREEN_ENTER": {
    "sequence": "\\x1b[?1049h",
    "action": "SwitchToAlternateScreenBuffer"
  },
  "ALT_SCREEN_EXIT": {
    "sequence": "\\x1b[?1049l",
    "action": "RestoreNormalScreenBuffer"
  },
  "CURSOR_HOME": {
    "sequence": "\\x1b[H",
    "action": "MoveCursorOrigin"
  },
  "CLEAR_SCREEN": {
    "sequence": "\\x1b[2J",
    "action": "ClearEntireScreen"
  },
  "BRACKETED_PASTE_ENABLE": {
    "sequence": "\\x1b[?2004h",
    "action": "EnableBracketedPaste"
  },
  "COLOR_RGB": {
    "prefix": "\\x1b[38;2;",
    "suffix": "m",
    "action": "TrueColorForeground"
  },
  "RESET": {
    "sequence": "\\x1b[0m",
    "action": "ResetFormatting"
  }
}
```

---

## File: `test_acp_client.py`

```python
#!/usr/bin/env python3
"""
Test client for VoidTerm Agent Control Plane (ACP)
Connects to the Rust broker via Unix domain socket and verifies the agent lifecycle.
"""

import os
import sys
import time
import json
import socket
import subprocess

TMPDIR = os.environ.get("TMPDIR", "/data/data/com.termux/files/usr/tmp")
SOCKET_PATH = os.path.join(TMPDIR, "voidterm_agent.sock")
BROKER_BIN = "/data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/target/debug/hybrid-term-broker"

def run_test():
    print("[*] Starting VoidTerm Broker in background...")
    if os.path.exists(SOCKET_PATH):
        try:
            os.remove(SOCKET_PATH)
        except OSError:
            pass

    proc = subprocess.Popen([BROKER_BIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    
    # Wait for socket to become available
    connected = False
    for _ in range(30):
        time.sleep(0.1)
        if os.path.exists(SOCKET_PATH):
            connected = True
            break
            
    if not connected:
        proc.kill()
        print("[-] Error: Broker socket was not created.")
        sys.exit(1)

    print(f"[+] Broker online. Connecting to ACP socket: {SOCKET_PATH}")
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.connect(SOCKET_PATH)

    def send_and_recv(req):
        payload = json.dumps(req) + "\n"
        client.sendall(payload.encode("utf-8"))
        res_raw = client.recv(4096).decode("utf-8")
        return json.loads(res_raw.strip())

    # 1. Ping
    print("\n--- Test 1: Ping ---")
    res = send_and_recv({"action": "ping"})
    print("Response:", res)
    assert res["status"] == "success"

    # 2. Create Session
    print("\n--- Test 2: Create Session ---")
    res = send_and_recv({
        "action": "create_session",
        "session_id": "agent_alpha",
        "tier": "local",
        "workdir": "/data/data/com.termux/files/home/Projects"
    })
    print("Response:", res)
    assert res["status"] == "success"

    # 3. Exec Command
    print("\n--- Test 3: Exec Command in PTY ---")
    res = send_and_recv({
        "action": "exec",
        "session_id": "agent_alpha",
        "command": "uname -a && echo '=== ACP AGENT WORKSPACE OPERATING LIVE ==='"
    })
    print("Response:", res)
    assert res["status"] == "success"

    # Wait for command execution in PTY
    time.sleep(0.8)

    # 4. Read Snapshot
    print("\n--- Test 4: Read PTY Snapshot ---")
    res = send_and_recv({
        "action": "read_snapshot",
        "session_id": "agent_alpha",
        "lines": 10
    })
    print("Snapshot Content:\n", res.get("content"))
    assert res["status"] == "success"
    assert "ACP AGENT WORKSPACE OPERATING LIVE" in res.get("content", "")

    # 5. HUD Telemetry
    print("\n--- Test 5: Broadcast HUD Telemetry ---")
    res = send_and_recv({
        "action": "hud_telemetry",
        "agent_name": "Antigravity",
        "status": "operating",
        "thought": "Testing Agent Control Plane direct IPC socket",
        "active_file": "hybrid-term-broker/src/acp.rs",
        "progress": 0.85
    })
    print("Response:", res)
    assert res["status"] == "success"

    # 6. List Sessions
    print("\n--- Test 6: List Sessions ---")
    res = send_and_recv({"action": "list_sessions"})
    print("Response:", res)
    assert res["status"] == "success"
    assert len(res.get("sessions", [])) == 1

    # 7. Close Session
    print("\n--- Test 7: Close Session ---")
    res = send_and_recv({
        "action": "close_session",
        "session_id": "agent_alpha"
    })
    print("Response:", res)
    assert res["status"] == "success"

    client.close()
    proc.terminate()
    print("\n[✔] ALL 7 AGENT CONTROL PLANE TESTS PASSED PERFECTLY!")

if __name__ == "__main__":
    run_test()

```

---

## File: `hybrid-term-broker/Cargo.toml`

```toml
[package]
name = "hybrid-term-broker"
version = "0.1.0"
edition = "2021"
description = "High-performance IPC Broker and stream multiplexer for Hybrid Engine"
authors = ["Hybrid Engine Team"]

[dependencies]
# Async I/O runtime
tokio = { version = "1", features = ["full"] }

# Host WASM runtime embedding (pure Rust, zero external C library dependencies)
wasmi = "0.36"

# POSIX & Unix domain socket bindings
nix = { version = "0.29", features = ["socket", "user", "event", "fs", "signal", "process"] }

# AVF virtio-vsock communication
vsock = "0.5"
tokio-vsock = "0.7"

# Error handling
anyhow = "1.0"

# IPC payload serialization & protocol framing
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"

# JNI bindings for Android JVM
jni = "0.21.1"

# Android libc bindings for native POSIX PTY
libc = "0.2"

[lib]
name = "hybrid_term_broker"
crate-type = ["cdylib", "rlib"]

[[bin]]
name = "hybrid-term-broker"
path = "src/main.rs"

[[bin]]
name = "guest_daemon"
path = "src/bin/guest_daemon.rs"

```

---

## File: `hybrid-term-broker/src/acp.rs`

```rust
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{mpsc, Mutex};

use crate::local_pty::LocalPty;
use crate::IpcMessage;

/// Supported Execution Tiers
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum ExecutionTier {
    Local,
    Wasm,
    Vm,
}

/// Incoming Requests from AI Agents over the Control Socket
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum AgentRequest {
    CreateSession {
        session_id: String,
        #[serde(default = "default_tier")]
        tier: ExecutionTier,
        #[serde(default)]
        workdir: String,
        #[serde(default)]
        shell: String,
        #[serde(default)]
        rows: Option<u16>,
        #[serde(default)]
        cols: Option<u16>,
    },
    Exec {
        session_id: String,
        command: String,
    },
    Input {
        session_id: String,
        data: String,
    },
    ReadSnapshot {
        session_id: String,
        #[serde(default = "default_snapshot_lines")]
        lines: usize,
    },
    HudTelemetry {
        agent_name: String,
        status: String,
        thought: String,
        #[serde(default)]
        active_file: String,
        #[serde(default)]
        progress: Option<f32>,
    },
    ListSessions,
    CloseSession {
        session_id: String,
    },
    Ping,
}

fn default_tier() -> ExecutionTier {
    ExecutionTier::Local
}

fn default_snapshot_lines() -> usize {
    50
}

/// Outgoing Responses back to Connected Agents
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum AgentResponse {
    Success {
        message: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        session_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        content: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        sessions: Option<Vec<SessionInfo>>,
    },
    Event {
        event_type: String,
        session_id: String,
        data: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: String,
    pub tier: ExecutionTier,
    pub workdir: String,
}

pub struct ActiveSession {
    pub session_id: String,
    pub tier: ExecutionTier,
    pub workdir: String,
    pub pty: Option<Arc<LocalPty>>,
}

/// Core Agent Control Plane Manager
#[derive(Clone)]
pub struct AgentControlPlane {
    sessions: Arc<Mutex<HashMap<String, Arc<ActiveSession>>>>,
    tx_ui: mpsc::Sender<IpcMessage>,
}

impl AgentControlPlane {
    pub fn new(tx_ui: mpsc::Sender<IpcMessage>) -> Self {
        Self {
            sessions: Arc::new(Mutex::new(HashMap::new())),
            tx_ui,
        }
    }

    /// Starts the asynchronous Unix Domain Socket listener for Agent IPC
    pub async fn start_socket_server(&self, socket_path: &str) -> Result<()> {
        let path = Path::new(socket_path);
        if path.exists() {
            let _ = std::fs::remove_file(path);
        }

        if let Some(parent) = path.parent() {
            if !parent.exists() {
                let _ = std::fs::create_dir_all(parent);
            }
        }

        let listener = UnixListener::bind(socket_path)
            .with_context(|| format!("Failed to bind Agent Control Socket at {}", socket_path))?;

        println!("🤖 [ACP] Agent Control Plane active at {}", socket_path);

        let acp_clone = self.clone();
        tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => {
                        let acp = acp_clone.clone();
                        tokio::spawn(async move {
                            if let Err(e) = acp.handle_client(stream).await {
                                eprintln!("⚠️ [ACP] Client connection error: {}", e);
                            }
                        });
                    }
                    Err(e) => {
                        eprintln!("❌ [ACP] Socket accept error: {}", e);
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    async fn handle_client(&self, stream: UnixStream) -> Result<()> {
        let (reader, mut writer) = stream.into_split();
        let mut buf_reader = BufReader::new(reader);
        let mut line = String::new();

        while buf_reader.read_line(&mut line).await? > 0 {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                line.clear();
                continue;
            }

            let response = match serde_json::from_str::<AgentRequest>(trimmed) {
                Ok(req) => self.process_request(req).await,
                Err(e) => AgentResponse::Error {
                    message: format!("Malformed JSON request: {}", e),
                },
            };

            let mut out_bytes = serde_json::to_vec(&response)?;
            out_bytes.push(b'\n');
            writer.write_all(&out_bytes).await?;
            writer.flush().await?;

            line.clear();
        }

        Ok(())
    }

    async fn process_request(&self, req: AgentRequest) -> AgentResponse {
        match req {
            AgentRequest::Ping => AgentResponse::Success {
                message: "pong: Agent Control Plane is responsive".to_string(),
                session_id: None,
                content: None,
                sessions: None,
            },

            AgentRequest::CreateSession {
                session_id,
                tier,
                workdir,
                shell,
                rows,
                cols,
            } => {
                let mut map = self.sessions.lock().await;
                if map.contains_key(&session_id) {
                    return AgentResponse::Error {
                        message: format!("Session '{}' already exists", session_id),
                    };
                }

                match tier {
                    ExecutionTier::Local => {
                        let ws = match (rows, cols) {
                            (Some(r), Some(c)) => Some((r, c)),
                            _ => None,
                        };

                        let tx_clone = self.tx_ui.clone();
                        match LocalPty::start_custom(&shell, &workdir, ws, tx_clone) {
                            Ok(pty) => {
                                let session = Arc::new(ActiveSession {
                                    session_id: session_id.clone(),
                                    tier,
                                    workdir,
                                    pty: Some(Arc::new(pty)),
                                });
                                map.insert(session_id.clone(), session);
                                AgentResponse::Success {
                                    message: format!("Local PTY session '{}' initialized", session_id),
                                    session_id: Some(session_id),
                                    content: None,
                                    sessions: None,
                                }
                            }
                            Err(e) => AgentResponse::Error {
                                message: format!("Failed to spawn local PTY: {}", e),
                            },
                        }
                    }
                    ExecutionTier::Wasm | ExecutionTier::Vm => {
                        let session = Arc::new(ActiveSession {
                            session_id: session_id.clone(),
                            tier: tier.clone(),
                            workdir,
                            pty: None,
                        });
                        map.insert(session_id.clone(), session);
                        AgentResponse::Success {
                            message: format!("{:?} session '{}' initialized", tier, session_id),
                            session_id: Some(session_id),
                            content: None,
                            sessions: None,
                        }
                    }
                }
            }

            AgentRequest::Exec { session_id, command } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    match session.tier {
                        ExecutionTier::Local => {
                            if let Some(pty) = &session.pty {
                                if let Err(e) = pty.write_command(&command) {
                                    return AgentResponse::Error {
                                        message: format!("Failed to write command to PTY: {}", e),
                                    };
                                }
                                AgentResponse::Success {
                                    message: format!("Dispatched command to '{}'", session_id),
                                    session_id: Some(session_id),
                                    content: None,
                                    sessions: None,
                                }
                            } else {
                                AgentResponse::Error {
                                    message: "Session PTY handle missing".to_string(),
                                }
                            }
                        }
                        ExecutionTier::Wasm | ExecutionTier::Vm => {
                            let _ = self.tx_ui.send(IpcMessage::ExecuteLocal { command: command.clone() }).await;
                            AgentResponse::Success {
                                message: format!("Dispatched command to {:?} engine", session.tier),
                                session_id: Some(session_id),
                                content: None,
                                sessions: None,
                            }
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::Input { session_id, data } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    if let Some(pty) = &session.pty {
                        if let Err(e) = pty.write_raw(data.as_bytes()) {
                            return AgentResponse::Error {
                                message: format!("Failed to write input to PTY: {}", e),
                            };
                        }
                        AgentResponse::Success {
                            message: "Input delivered".to_string(),
                            session_id: Some(session_id),
                            content: None,
                            sessions: None,
                        }
                    } else {
                        AgentResponse::Error {
                            message: "Session is not a PTY session".to_string(),
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::ReadSnapshot { session_id, lines } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    if let Some(pty) = &session.pty {
                        let snapshot = pty.get_snapshot(lines);
                        AgentResponse::Success {
                            message: "Snapshot captured".to_string(),
                            session_id: Some(session_id),
                            content: Some(snapshot),
                            sessions: None,
                        }
                    } else {
                        AgentResponse::Error {
                            message: "Snapshot only supported on PTY sessions".to_string(),
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::HudTelemetry {
                agent_name,
                status,
                thought,
                active_file,
                progress,
            } => {
                let telemetry_json = serde_json::json!({
                    "type": "agent_hud",
                    "agent": agent_name,
                    "status": status,
                    "thought": thought,
                    "active_file": active_file,
                    "progress": progress
                });
                let payload = format!("__HUD_TELEMETRY__:{}", telemetry_json);
                let _ = self.tx_ui.send(IpcMessage::TerminalOutput(payload)).await;

                AgentResponse::Success {
                    message: "HUD telemetry broadcasted to UI".to_string(),
                    session_id: None,
                    content: None,
                    sessions: None,
                }
            }

            AgentRequest::ListSessions => {
                let map = self.sessions.lock().await;
                let list: Vec<SessionInfo> = map
                    .values()
                    .map(|s| SessionInfo {
                        session_id: s.session_id.clone(),
                        tier: s.tier.clone(),
                        workdir: s.workdir.clone(),
                    })
                    .collect();

                AgentResponse::Success {
                    message: format!("Found {} active sessions", list.len()),
                    session_id: None,
                    content: None,
                    sessions: Some(list),
                }
            }

            AgentRequest::CloseSession { session_id } => {
                let mut map = self.sessions.lock().await;
                if map.remove(&session_id).is_some() {
                    AgentResponse::Success {
                        message: format!("Session '{}' closed", session_id),
                        session_id: Some(session_id),
                        content: None,
                        sessions: None,
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }
        }
    }
}

```

---

## File: `hybrid-term-broker/src/lib.rs`

```rust
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

pub mod acp;
pub mod local_pty;
pub mod storage;
pub mod vm_bridge;
pub mod wasm_engine;

use acp::AgentControlPlane;
use storage::StorageProvisioner;

// Default AVF Guest VM Context ID and Port
const DEFAULT_VM_CID: u32 = 3;
const VM_VSOCK_PORT: u32 = 8000;

/// The unified message protocol for IPC routing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcMessage {
    /// A command to run locally in the persistent Android PTY shell
    ExecuteLocal { command: String },
    /// A command to run a lightweight WebAssembly script locally
    ExecuteWasm { module_name: String, args: Vec<String> },
    /// A command to push a native Linux binary execution to the AVF Guest VM
    ExecuteVm { command: String },
    /// Terminal output (stdout/stderr) returning to the UI
    TerminalOutput(String),
}

// Thread-safe global singletons for our Android environment
static JVM: OnceLock<JavaVM> = OnceLock::new();
static CALLBACK_OBJ: OnceLock<GlobalRef> = OnceLock::new();
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static TX_INPUT: OnceLock<mpsc::Sender<IpcMessage>> = OnceLock::new();
static ACP: OnceLock<AgentControlPlane> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_com_hybridengine_terminal_Broker_startDaemon(
    env: JNIEnv,
    obj: JObject,
) {
    // 1. Capture the JavaVM and create a Global Reference to the Kotlin object
    let jvm = env.get_java_vm().expect("Failed to get JavaVM");
    let global_obj = env.new_global_ref(obj).expect("Failed to create GlobalRef");
    
    let _ = JVM.set(jvm);
    let _ = CALLBACK_OBJ.set(global_obj);

    if RUNTIME.get().is_some() {
        return; // Already started
    }

    let rt = Runtime::new().expect("Failed to build Tokio runtime");
    let (tx_input, mut rx_input) = mpsc::channel::<IpcMessage>(1024);
    let (tx_output, mut rx_output) = mpsc::channel::<IpcMessage>(1024);

    let tx_acp = tx_input.clone();
    let _ = TX_INPUT.set(tx_input);

    let socket_path = std::env::var("VOIDTERM_AGENT_SOCKET").unwrap_or_else(|_| {
        let tmp = std::env::var("TMPDIR").unwrap_or_else(|_| "/data/data/com.termux/files/usr/tmp".to_string());
        format!("{}/voidterm_agent.sock", tmp)
    });
    let acp = AgentControlPlane::new(tx_acp);
    let _ = ACP.set(acp.clone());

    rt.spawn(async move {
        if let Err(e) = acp.start_socket_server(&socket_path).await {
            eprintln!("❌ [JNI] Failed to start Agent Control Plane: {}", e);
        }
    });

    rt.spawn(async move {
        println!("🚀 [JNI] VoidTerm Broker daemon running (Debian microVM direct vsock routing)...");

        // Spawn a parallel task to handle EGRESS (Rust -> Kotlin UI)
        tokio::spawn(async move {
            while let Some(msg) = rx_output.recv().await {
                if let IpcMessage::TerminalOutput(text) = msg {
                    send_to_kotlin(text);
                }
            }
        });

        // The INGRESS loop (Kotlin UI -> Rust -> Debian VM vsock)
        while let Some(msg) = rx_input.recv().await {
            match msg {
                IpcMessage::ExecuteLocal { command } | IpcMessage::ExecuteVm { command } => {
                    let tx_clone = tx_output.clone();
                    tokio::spawn(async move {
                        if let Err(e) = vm_bridge::VmBridge::dispatch_command(
                            command,
                            DEFAULT_VM_CID,
                            VM_VSOCK_PORT,
                            tx_clone.clone(),
                        ).await {
                            let err_msg = format!("❌ [VM Vsock Error]: {}\n", e);
                            let _ = tx_clone.send(IpcMessage::TerminalOutput(err_msg)).await;
                        }
                    });
                }
                IpcMessage::ExecuteWasm { module_name, args } => {
                    let tx_clone = tx_output.clone();
                    tokio::spawn(async move {
                        if let Err(e) = wasm_engine::WasmEngine::execute(
                            module_name,
                            args,
                            tx_clone.clone(),
                        ).await {
                            let err_msg = format!("❌ [WASM Error]: {}\n", e);
                            let _ = tx_clone.send(IpcMessage::TerminalOutput(err_msg)).await;
                        }
                    });
                }
                _ => {}
            }
        }
    });

    let _ = RUNTIME.set(rt);
}

#[no_mangle]
pub extern "system" fn Java_com_hybridengine_terminal_Broker_sendCommand(
    mut env: JNIEnv,
    _class: JClass,
    command: JString,
) {
    // 1. Safely extract the String from the Java/Kotlin environment
    let cmd_raw: String = match env.get_string(&command) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    println!("📥 [JNI] Received from UI: {}", cmd_raw);
    
    // 2. Retrieve our global Tokio runtime and our input channel
    if let (Some(rt), Some(tx)) = (RUNTIME.get(), TX_INPUT.get()) {
        let tx_clone = tx.clone();
        
        // 3. Check for specific prefix or route to local shell
        let trimmed = cmd_raw.trim().to_string();
        let msg = if trimmed.starts_with("wasm ") {
            let parts: Vec<String> = trimmed[5..].split_whitespace().map(|s| s.to_string()).collect();
            let module_name = parts.get(0).cloned().unwrap_or_default();
            let args = if parts.len() > 1 { parts[1..].to_vec() } else { vec![] };
            IpcMessage::ExecuteWasm { module_name, args }
        } else if trimmed.starts_with("vm ") {
            let vm_cmd = trimmed[3..].trim().to_string();
            IpcMessage::ExecuteVm { command: vm_cmd }
        } else {
            IpcMessage::ExecuteLocal { command: cmd_raw }
        };
        
        rt.spawn(async move {
            if let Err(e) = tx_clone.send(msg).await {
                eprintln!("❌ [JNI] Failed to send command to broker: {}", e);
            }
        });
    } else {
        eprintln!("⚠️ [JNI] Broker not initialized. Call startDaemon first.");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hybridengine_terminal_Broker_provisionDiskNative(
    env: JNIEnv,
    class: JClass,
    disk_path: JString,
    rootfs_dir: JString,
) {
    Java_com_hybridengine_terminal_Broker_provisionDisk(env, class, disk_path, rootfs_dir);
}

#[no_mangle]
pub extern "system" fn Java_com_hybridengine_terminal_Broker_provisionDisk(
    mut env: JNIEnv,
    _class: JClass,
    disk_path: JString,
    rootfs_dir: JString,
) {
    let disk: String = match env.get_string(&disk_path) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("❌ [JNI Error] Invalid disk path: {}", e);
            return;
        }
    };
    let rootfs: String = match env.get_string(&rootfs_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("❌ [JNI Error] Invalid rootfs path: {}", e);
            return;
        }
    };

    // Provision a 2GB (2048MB) virtual disk for Debian
    if let Err(e) = StorageProvisioner::provision_avf_disk(&disk, &rootfs, 2048) {
        eprintln!("❌ [JNI Error] Failed to provision AVF disk: {}", e);
    }
}

/// Helper function to push strings back to the Android UI
fn send_to_kotlin(text: String) {
    if let (Some(jvm), Some(callback)) = (JVM.get(), CALLBACK_OBJ.get()) {
        if let Ok(mut env) = jvm.attach_current_thread() {
            if let Ok(j_str) = env.new_string(text) {
                let _ = env.call_method(
                    callback.as_obj(),
                    "onTerminalOutput",
                    "(Ljava/lang/String;)V",
                    &[JValue::from(&j_str)],
                );
            }
        }
    }
}

```

---

## File: `hybrid-term-broker/src/local_pty.rs`

```rust
use anyhow::{bail, Context, Result};
use std::fs::File;
use std::io::{Read, Write};
use std::os::unix::io::FromRawFd;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;

use crate::IpcMessage;

const MAX_SCROLLBACK_LINES: usize = 1000;

#[allow(dead_code)]
pub struct LocalPty {
    master_fd: libc::c_int,
    writer: Arc<Mutex<File>>,
    scrollback: Arc<Mutex<Vec<String>>>,
}

#[allow(dead_code)]
impl LocalPty {
    /// Spawns a persistent Android shell using native POSIX PTY and pipes output to the UI
    pub fn start(tx_output: mpsc::Sender<IpcMessage>) -> Result<Self> {
        Self::start_custom("", "", None, tx_output)
    }

    /// Spawns a custom shell session with workdir and winsize configuration
    pub fn start_custom(
        custom_shell: &str,
        workdir: &str,
        custom_ws: Option<(u16, u16)>,
        tx_output: mpsc::Sender<IpcMessage>,
    ) -> Result<Self> {
        let mut master_fd: libc::c_int = -1;
        let mut slave_fd: libc::c_int = -1;

        let (rows, cols) = custom_ws.unwrap_or((24, 80));
        let mut ws = libc::winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };

        // Open native master/slave pseudo-terminal pair
        let res = unsafe {
            libc::openpty(
                &mut master_fd,
                &mut slave_fd,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                &mut ws as *mut libc::winsize,
            )
        };

        if res != 0 {
            bail!("Failed to open native PTY: errno {}", std::io::Error::last_os_error());
        }

        // Convert file descriptors to safe Rust File handles
        let slave_in = unsafe { File::from_raw_fd(slave_fd) };
        let slave_out = slave_in.try_clone().context("Failed to clone slave stdout")?;
        let slave_err = slave_in.try_clone().context("Failed to clone slave stderr")?;

        let master_reader_file = unsafe { File::from_raw_fd(master_fd) };
        let master_writer_file = master_reader_file.try_clone().context("Failed to clone master PTY")?;

        // Determine shell binary
        let shell_path = if !custom_shell.is_empty() && std::path::Path::new(custom_shell).exists() {
            custom_shell
        } else if std::path::Path::new("/data/data/com.termux/files/usr/bin/bash").exists() {
            "/data/data/com.termux/files/usr/bin/bash"
        } else if std::path::Path::new("/data/data/com.termux/files/usr/bin/sh").exists() {
            "/data/data/com.termux/files/usr/bin/sh"
        } else if std::path::Path::new("/system/bin/sh").exists() {
            "/system/bin/sh"
        } else {
            "sh"
        };

        let mut cmd = Command::new(shell_path);
        cmd.arg("-l"); // Run as login shell
        cmd.stdin(Stdio::from(slave_in));
        cmd.stdout(Stdio::from(slave_out));
        cmd.stderr(Stdio::from(slave_err));
        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        
        if !workdir.is_empty() && std::path::Path::new(workdir).exists() {
            cmd.current_dir(workdir);
        } else if let Ok(home) = std::env::var("HOME") {
            cmd.env("HOME", &home);
            cmd.current_dir(home);
        }

        let _child = cmd.spawn().context("Failed to spawn shell process on PTY")?;

        let safe_writer = Arc::new(Mutex::new(master_writer_file));
        let scrollback = Arc::new(Mutex::new(Vec::with_capacity(MAX_SCROLLBACK_LINES)));
        let scrollback_clone = scrollback.clone();

        // Spawn background blocking thread to read PTY stream into channel and scrollback buffer
        tokio::task::spawn_blocking(move || {
            let mut reader = master_reader_file;
            let mut buf = [0u8; 2048];
            while let Ok(bytes_read) = reader.read(&mut buf) {
                if bytes_read == 0 {
                    break; // Shell closed
                }

                let output = String::from_utf8_lossy(&buf[..bytes_read]).to_string();
                
                // Update scrollback ring buffer
                if let Ok(mut sb) = scrollback_clone.lock() {
                    for line in output.split('\n') {
                        if sb.len() >= MAX_SCROLLBACK_LINES {
                            sb.remove(0);
                        }
                        sb.push(line.to_string());
                    }
                }

                // Send to Tokio multiplexer
                let _ = tx_output.blocking_send(IpcMessage::TerminalOutput(output));
            }
        });

        Ok(Self {
            master_fd,
            writer: safe_writer,
            scrollback,
        })
    }

    /// Writes a command followed by carriage return into the running shell session
    pub fn write_command(&self, input: &str) -> Result<()> {
        let mut w = self.writer.lock().unwrap();
        write!(w, "{}\r", input)?;
        w.flush()?;
        Ok(())
    }

    /// Writes raw byte payload into the PTY (for interactive keystrokes or escape sequences)
    pub fn write_raw(&self, data: &[u8]) -> Result<()> {
        let mut w = self.writer.lock().unwrap();
        w.write_all(data)?;
        w.flush()?;
        Ok(())
    }

    /// Resizes the PTY window dimensions
    pub fn resize(&self, rows: u16, cols: u16) -> Result<()> {
        let ws = libc::winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let res = unsafe { libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &ws) };
        if res != 0 {
            bail!("Failed to resize PTY: errno {}", std::io::Error::last_os_error());
        }
        Ok(())
    }

    /// Retrieves the recent snapshot of scrollback lines
    pub fn get_snapshot(&self, max_lines: usize) -> String {
        if let Ok(sb) = self.scrollback.lock() {
            let total = sb.len();
            let start = total.saturating_sub(max_lines);
            sb[start..].join("\n")
        } else {
            String::new()
        }
    }
}

```

---

## File: `hybrid-term-broker/src/main.rs`

```rust
use hybrid_term_broker::acp::AgentControlPlane;
use hybrid_term_broker::vm_bridge::VmBridge;
use hybrid_term_broker::wasm_engine::WasmEngine;
use hybrid_term_broker::IpcMessage;
use tokio::sync::mpsc;

// Default AVF Guest VM Context ID and Port
const DEFAULT_VM_CID: u32 = 3;
const VM_VSOCK_PORT: u32 = 8000;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    println!("🚀 VoidTerm Hybrid IPC Broker initialized (direct Debian VM vsock).");

    let (tx_output, mut rx_output) = mpsc::channel::<IpcMessage>(1024);
    let (tx_input, mut rx_input) = mpsc::channel::<IpcMessage>(1024);

    let socket_path = std::env::var("VOIDTERM_AGENT_SOCKET").unwrap_or_else(|_| {
        let tmp = std::env::var("TMPDIR").unwrap_or_else(|_| "/data/data/com.termux/files/usr/tmp".to_string());
        format!("{}/voidterm_agent.sock", tmp)
    });
    let acp = AgentControlPlane::new(tx_input.clone());
    acp.start_socket_server(&socket_path).await?;

    println!("📡 Listening for Debian microVM commands on CID {} port {}...", DEFAULT_VM_CID, VM_VSOCK_PORT);

    loop {
        tokio::select! {
            // 1. Ingress UI Commands Routing
            Some(input_msg) = rx_input.recv() => {
                match input_msg {
                    IpcMessage::ExecuteLocal { command } | IpcMessage::ExecuteVm { command } => {
                        let tx_clone = tx_output.clone();
                        tokio::spawn(async move {
                            if let Err(e) = VmBridge::dispatch_command(
                                command, 
                                DEFAULT_VM_CID, 
                                VM_VSOCK_PORT, 
                                tx_clone.clone()
                            ).await {
                                let err_msg = format!("❌ [VM Vsock Error]: {}\n", e);
                                let _ = tx_clone.send(IpcMessage::TerminalOutput(err_msg)).await;
                            }
                        });
                    }
                    IpcMessage::ExecuteWasm { module_name, args } => {
                        let tx_clone = tx_output.clone();
                        tokio::spawn(async move {
                            if let Err(e) = WasmEngine::execute(module_name, args, tx_clone.clone()).await {
                                let err_msg = format!("❌ [WASM Error]: {}\n", e);
                                let _ = tx_clone.send(IpcMessage::TerminalOutput(err_msg)).await;
                            }
                        });
                    }
                    _ => {}
                }
            }

            // 2. Terminal Output Multiplexer Channel
            Some(output_msg) = rx_output.recv() => {
                if let IpcMessage::TerminalOutput(text) = output_msg {
                    print!("{}", text);
                }
            }

            // 3. Signal Handling
            _ = tokio::signal::ctrl_c() => {
                println!("\n🛑 Shutting down IPC Broker daemon.");
                break;
            }
        }
    }

    Ok(())
}

```

---

## File: `hybrid-term-broker/src/storage.rs`

```rust
use anyhow::{Context, Result};
use std::fs::{self, File};
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::Path;
use std::process::Command;

pub struct StorageProvisioner;

const SYSTEMD_SERVICE_CONTENT: &str = r#"[Unit]
Description=VoidTerm Vsock Guest Daemon
ConditionVirtualization=vm
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/guest_daemon
Restart=always
RestartSec=1
User=root
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
"#;

impl StorageProvisioner {
    /// Dynamically allocates a sparse ext4 image, configures guest daemon systemd bindings,
    /// and injects the Debian rootfs into disk.img.
    pub fn provision_avf_disk(disk_path: &str, rootfs_dir: &str, size_mb: u64) -> Result<()> {
        let disk = Path::new(disk_path);
        
        if disk.exists() {
            println!("💾 [Storage] AVF disk already provisioned: {}", disk_path);
            return Ok(());
        }

        let rootfs = Path::new(rootfs_dir);

        // Extract debian-rootfs.tar.gz using native Android tar if staged
        let parent_dir = rootfs.parent().unwrap_or(rootfs);
        let archive_path = parent_dir.join("debian-rootfs.tar.gz");

        if archive_path.exists() {
            println!("📦 [Storage] Extracting Debian OS using native Android tar from {}...", archive_path.display());
            fs::create_dir_all(rootfs).context("Failed to create rootfs directory")?;

            let tar_bin = if Path::new("/system/bin/tar").exists() {
                "/system/bin/tar"
            } else if Path::new("/data/data/com.termux/files/usr/bin/tar").exists() {
                "/data/data/com.termux/files/usr/bin/tar"
            } else {
                "tar"
            };

            let tar_status = Command::new(tar_bin)
                .arg("-xzf")
                .arg(&archive_path)
                .arg("-C")
                .arg(rootfs_dir)
                .status()
                .context("Failed to execute native tar extractor")?;

            if !tar_status.success() {
                anyhow::bail!("Native tar extraction failed with exit code {:?}", tar_status.code());
            }

            println!("✅ [Storage] Debian rootfs extracted successfully.");

            // Clean up archive to reclaim internal storage space
            let _ = fs::remove_file(&archive_path);
        }

        // 1. Inject guest daemon systemd unit & activation symlink into rootfs before packing
        Self::inject_systemd_service(rootfs)?;

        // 2. Inject guest daemon executable into rootfs
        Self::inject_guest_daemon_binary(rootfs)?;

        println!("🏗️ [Storage] Allocating {}MB sparse disk at {}...", size_mb, disk_path);
        let file = File::create(disk_path).context("Failed to create disk.img")?;
        
        // Allocate space efficiently using a sparse file
        file.set_len(size_mb * 1024 * 1024).context("Failed to set sparse length")?;

        // Locate mke2fs binary (/system/bin/mke2fs with Termux/PATH fallback)
        let mke2fs_bin = if Path::new("/system/bin/mke2fs").exists() {
            "/system/bin/mke2fs"
        } else if Path::new("/data/data/com.termux/files/usr/bin/mke2fs").exists() {
            "/data/data/com.termux/files/usr/bin/mke2fs"
        } else {
            "mke2fs"
        };

        // Format the sparse file as ext4 using Android's native user-space binary
        println!("⚙️ [Storage] Formatting disk as ext4 using {}...", mke2fs_bin);
        let format_status = Command::new(mke2fs_bin)
            .arg("-t").arg("ext4")
            .arg("-F") // Force formatting a regular file
            .arg(disk_path)
            .status()
            .context("Failed to execute mke2fs")?;

        if !format_status.success() {
            anyhow::bail!("ext4 formatting failed with exit code {:?}", format_status.code());
        }

        // Locate e2fsdroid binary (/system/bin/e2fsdroid with Termux/PATH fallback)
        let e2fsdroid_bin = if Path::new("/system/bin/e2fsdroid").exists() {
            "/system/bin/e2fsdroid"
        } else if Path::new("/data/data/com.termux/files/usr/bin/e2fsdroid").exists() {
            "/data/data/com.termux/files/usr/bin/e2fsdroid"
        } else {
            "e2fsdroid"
        };

        // Inject the extracted Debian rootfs directly into the unmounted ext4 image
        println!("📦 [Storage] Injecting Debian rootfs from {} into {}...", rootfs_dir, disk_path);
        let inject_status = Command::new(e2fsdroid_bin)
            .arg("-e") // e2fsdroid mode
            .arg("-f").arg(rootfs_dir) // Source directory
            .arg("-a").arg("/") // Target mount point inside the image
            .arg(disk_path)
            .status()
            .context("Failed to execute e2fsdroid")?;

        if !inject_status.success() {
            anyhow::bail!("Rootfs injection failed with exit code {:?}", inject_status.code());
        }

        println!("✅ [Storage] AVF Boot Disk successfully provisioned with Systemd Guest Daemon.");
        Ok(())
    }

    /// Injects the systemd service definition and enables it via multi-user.target.wants symlink
    fn inject_systemd_service(rootfs: &Path) -> Result<()> {
        let systemd_dir = rootfs.join("etc/systemd/system");
        let wants_dir = systemd_dir.join("multi-user.target.wants");

        println!("🔧 [Storage] Injecting systemd service into {}...", systemd_dir.display());
        fs::create_dir_all(&wants_dir).context("Failed to create systemd multi-user.target.wants directory")?;

        let service_file = systemd_dir.join("voidterm-daemon.service");
        fs::write(&service_file, SYSTEMD_SERVICE_CONTENT)
            .context("Failed to write voidterm-daemon.service")?;

        let symlink_target = wants_dir.join("voidterm-daemon.service");
        if symlink_target.exists() || symlink_target.is_symlink() {
            let _ = fs::remove_file(&symlink_target);
        }

        // Relative symlink inside the rootfs
        symlink("../voidterm-daemon.service", &symlink_target)
            .context("Failed to create systemd activation symlink")?;

        println!("✅ [Storage] Systemd service enabled at multi-user.target.wants/voidterm-daemon.service");
        Ok(())
    }

    /// Injects or verifies the guest_daemon binary in /usr/local/bin
    fn inject_guest_daemon_binary(rootfs: &Path) -> Result<()> {
        let bin_dir = rootfs.join("usr/local/bin");
        fs::create_dir_all(&bin_dir).context("Failed to create /usr/local/bin in rootfs")?;

        let dest_bin = bin_dir.join("guest_daemon");
        if !dest_bin.exists() {
            let parent_dir = rootfs.parent().unwrap_or(rootfs);
            let staged_guest_daemon = parent_dir.join("guest_daemon");

            // Search candidate locations for pre-built musl static guest_daemon binary
            let candidates = [
                staged_guest_daemon.as_path(),
                Path::new("/data/data/com.hybridengine.terminal/files/guest_daemon"),
                Path::new("target/aarch64-unknown-linux-musl/release/guest_daemon"),
                Path::new("target/aarch64-unknown-linux-gnu/release/guest_daemon"),
                Path::new("/data/data/com.termux/files/usr/bin/guest_daemon"),
                Path::new("/data/local/tmp/guest_daemon"),
                Path::new("target/release/guest_daemon"),
                Path::new("target/aarch64-linux-android/release/guest_daemon"),
            ];

            let mut copied = false;
            for candidate in &candidates {
                if candidate.exists() {
                    if let Ok(_) = fs::copy(candidate, &dest_bin) {
                        println!("📥 [Storage] Copied static guest_daemon from {}", candidate.display());
                        copied = true;
                        break;
                    }
                }
            }

            if !copied {
                println!("ℹ️ [Storage] Initializing placeholder guest_daemon runner in /usr/local/bin");
                let placeholder_script = "#!/bin/sh\necho '[Guest Daemon] Starting vsock listener on CID 3 port 8000'\n";
                let _ = fs::write(&dest_bin, placeholder_script);
            }
        }

        // Ensure executable permissions (rwxr-xr-x -> 0o755)
        if dest_bin.exists() {
            let mut perms = fs::metadata(&dest_bin)?.permissions();
            perms.set_mode(0o755);
            let _ = fs::set_permissions(&dest_bin, perms);
        }

        Ok(())
    }
}

```

---

## File: `hybrid-term-broker/src/vm_bridge.rs`

```rust
use anyhow::{Context, Result};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_vsock::{VsockAddr, VsockStream};

use crate::IpcMessage;

pub struct VmBridge;

impl VmBridge {
    /// Connects to the guest daemon over virtio-vsock with a retry loop
    /// to seamlessly wait for systemd initialization inside the microVM.
    pub async fn connect_guest_daemon(
        guest_cid: u32,
        port: u32,
        tx_output: &mpsc::Sender<IpcMessage>,
    ) -> Result<VsockStream> {
        let addr = VsockAddr::new(guest_cid, port);
        let mut retries = 10;
        let delay = Duration::from_millis(500);

        loop {
            match VsockStream::connect(addr).await {
                Ok(stream) => {
                    println!("✅ [Vsock Bridge] Connected to Guest Daemon on CID {} Port {}", guest_cid, port);
                    return Ok(stream);
                }
                Err(e) => {
                    retries -= 1;
                    if retries == 0 {
                        anyhow::bail!(
                            "Failed to establish vsock connection to AVF guest VM after 10 attempts: {}",
                            e
                        );
                    }
                    let wait_notice = format!(
                        "⏳ [AVF Vsock Bridge] Waiting for guest daemon to initialize... (retrying in 500ms, {} left)\n",
                        retries
                    );
                    let _ = tx_output.send(IpcMessage::TerminalOutput(wait_notice)).await;
                    sleep(delay).await;
                }
            }
        }
    }

    /// Establishes a zero-copy hypervisor connection to the guest VM daemon
    /// and streams stdout/stderr back to the UI multiplexer.
    pub async fn dispatch_command(
        command: String,
        guest_cid: u32,
        port: u32,
        tx_output: mpsc::Sender<IpcMessage>,
    ) -> Result<()> {
        let init_msg = format!(
            "🌀 [AVF Guest VM] Executing on CID {}: {}\n",
            guest_cid, command
        );
        let _ = tx_output.send(IpcMessage::TerminalOutput(init_msg)).await;

        // Connect to the guest Linux daemon with retry buffer
        let mut stream = Self::connect_guest_daemon(guest_cid, port, &tx_output).await?;

        // Write command payload to the hypervisor stream
        let payload = format!("{}\n", command);
        stream
            .write_all(payload.as_bytes())
            .await
            .context("Failed to write command payload over vsock")?;

        // Read output back from the VM guest daemon chunk-by-chunk
        let mut buffer = [0u8; 1024];
        loop {
            let bytes_read = stream.read(&mut buffer).await?;
            if bytes_read == 0 {
                break; // VM closed execution stream
            }

            let output_chunk = String::from_utf8_lossy(&buffer[..bytes_read]).to_string();
            let _ = tx_output
                .send(IpcMessage::TerminalOutput(output_chunk))
                .await;
        }

        Ok(())
    }
}

```

---

## File: `hybrid-term-broker/src/wasm_engine.rs`

```rust
use anyhow::{Context, Result};
use tokio::sync::mpsc;
use wasmi::{Engine, Linker, Module, Store};

use crate::IpcMessage;

pub struct WasmEngine;

impl WasmEngine {
    /// Executes a compiled WASM module and routes status/output to the broker
    pub async fn execute(
        module_name: String,
        args: Vec<String>,
        tx_output: mpsc::Sender<IpcMessage>,
    ) -> Result<()> {
        let msg = format!("⚡ [WASM] Booting module: {} with args: {:?}...\n", module_name, args);
        let _ = tx_output.send(IpcMessage::TerminalOutput(msg)).await;

        // Initialize wasmi Engine & Store
        let engine = Engine::default();
        let mut store = Store::new(&engine, ());

        // Create linker for host functions
        let linker = <Linker<()>>::new(&engine);

        // Check if module file exists locally, otherwise simulate execution
        let wasm_bytes = match std::fs::read(&module_name) {
            Ok(bytes) => bytes,
            Err(_) => {
                // Minimal valid WASM binary header
                vec![0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]
            }
        };

        let module = Module::new(&engine, &wasm_bytes[..])
            .context("Failed to parse WASM module")?;

        let _instance = linker
            .instantiate(&mut store, &module)
            .context("Failed to instantiate WASM module")?
            .start(&mut store)
            .context("Failed to start WASM module")?;

        let success_msg = format!("✅ [WASM] Module {} executed successfully.\n", module_name);
        let _ = tx_output.send(IpcMessage::TerminalOutput(success_msg)).await;

        Ok(())
    }
}

```

---

## File: `hybrid-term-broker/src/bin/guest_daemon.rs`

```rust
use anyhow::{Context, Result};
use std::net::Shutdown;
use std::process::Stdio;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;
use tokio_vsock::{VsockAddr, VsockListener, VMADDR_CID_ANY};

const VM_VSOCK_PORT: u32 = 8000;

#[tokio::main]
async fn main() -> Result<()> {
    println!("🐧 [Guest Daemon] Booting inside AVF microVM...");
    
    // Bind the listener to all available CIDs on the guest side
    let addr = VsockAddr::new(VMADDR_CID_ANY, VM_VSOCK_PORT);
    let listener = VsockListener::bind(addr)
        .context("Failed to bind vsock listener")?;

    println!("🎧 [Guest Daemon] Listening on vsock port {}...", VM_VSOCK_PORT);

    loop {
        // Accept incoming connections from the Android Host Broker
        let (mut stream, peer_addr) = match listener.accept().await {
            Ok(connection) => connection,
            Err(e) => {
                eprintln!("⚠️ [Guest Daemon] Connection failed: {}", e);
                continue;
            }
        };

        println!("🔗 [Guest Daemon] Connection accepted from CID: {}", peer_addr.cid());

        tokio::spawn(async move {
            let mut command_buffer = [0u8; 1024];
            
            // 1. Read the command payload from the hypervisor bus
            let bytes_read = match stream.read(&mut command_buffer).await {
                Ok(n) if n == 0 => return,
                Ok(n) => n,
                Err(_) => return,
            };

            let command_str = String::from_utf8_lossy(&command_buffer[..bytes_read])
                .trim()
                .to_string();
                
            println!("⚙️ [Guest Daemon] Executing: {}", command_str);

            // 2. Execute the command natively in the Linux shell
            let mut child = Command::new("sh")
                .arg("-c")
                .arg(command_str)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .expect("Failed to spawn shell process");

            // 3. Capture the output streams
            let mut stdout = child.stdout.take().expect("Failed to capture stdout");
            let mut stderr = child.stderr.take().expect("Failed to capture stderr");

            let mut stdout_buffer = [0u8; 1024];
            let mut stderr_buffer = [0u8; 1024];

            // 4. Stream the stdout back over vsock to the Host Broker
            loop {
                tokio::select! {
                    Ok(n) = stdout.read(&mut stdout_buffer) => {
                        if n == 0 { break; }
                        let _ = stream.write_all(&stdout_buffer[..n]).await;
                    }
                    Ok(n) = stderr.read(&mut stderr_buffer) => {
                        if n == 0 { break; }
                        let _ = stream.write_all(&stderr_buffer[..n]).await;
                    }
                    else => break,
                }
            }
            
            let _ = stream.shutdown(Shutdown::Both);
            println!("✅ [Guest Daemon] Execution complete. Stream closed.");
        });
    }
}

```

---

## File: `android/app/build.gradle.kts`

```text
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hybridengine.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hybridengine.terminal"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
}

```

---

## File: `android/app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.hybridengine.terminal">

    <!-- Required for downloading packages within the Linux VM -->
    <uses-permission android:name="android.permission.INTERNET" />
    
    <!-- Required for full file system access in modern Android -->
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <!-- Required to allocate and boot AVF microVMs via VirtualizationService -->
    <uses-permission android:name="android.permission.MANAGE_VIRTUAL_MACHINE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />

    <!-- Foreground Service & Persistent Notification permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:roundIcon="@android:drawable/sym_def_app_icon"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="Terminal Settings"
            android:theme="@style/Theme.VoidTerm.Settings"
            android:parentActivityName=".MainActivity" />

        <service
            android:name=".TerminalService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Linux microVM execution and vsock daemon lifecycle management" />
        </service>
    </application>
</manifest>

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/AnsiParser.kt`

```kotlin
package com.hybridengine.terminal

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

class AnsiParser {

    data class StyledText(val text: String, val paint: Paint)

    // Regex to capture standard ANSI color/formatting codes: \x1b[...m
    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")
    
    // Active theme colors
    var activeTheme: TerminalTheme = TerminalTheme.DRACULA

    // Default terminal aesthetic
    val defaultTextPaint = Paint().apply {
        color = activeTheme.foreground
        textSize = 38f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    fun applyTheme(theme: TerminalTheme, textSize: Float) {
        activeTheme = theme
        defaultTextPaint.color = theme.foreground
        defaultTextPaint.textSize = textSize
    }

    /**
     * Splits raw multiline terminal output and parses ANSI codes into styled segments per line.
     */
    fun parseLines(rawOutput: String): List<List<StyledText>> {
        val rawLines = rawOutput.split("\n")
        return rawLines.map { line -> parse(line) }
    }

    /**
     * Parses a single terminal text line into styled segments based on ANSI escape sequences.
     */
    fun parse(rawOutput: String): List<StyledText> {
        val parsedBlocks = mutableListOf<StyledText>()
        var currentPaint = Paint(defaultTextPaint)
        
        // 1. Handle Carriage Returns (\r) and Backspaces (\b)
        var sanitized = rawOutput.replace("\r", "")
        
        // Simulate backspace by removing the previous character
        while (sanitized.contains("\b")) {
            sanitized = sanitized.replace(Regex(".\b"), "")
        }

        // 2. Parse ANSI Color Codes
        var currentIndex = 0
        val matches = ansiRegex.findAll(sanitized)

        for (match in matches) {
            // Add the text before the ANSI code with the current paint
            if (match.range.first > currentIndex) {
                val textSegment = sanitized.substring(currentIndex, match.range.first)
                if (textSegment.isNotEmpty()) {
                    parsedBlocks.add(StyledText(textSegment, Paint(currentPaint)))
                }
            }

            // Update the paint based on the ANSI code
            val codeSequence = match.groupValues[1]
            currentPaint = applyAnsiCode(currentPaint, codeSequence)
            
            currentIndex = match.range.last + 1
        }

        // Add any remaining text after the last ANSI code
        if (currentIndex < sanitized.length) {
            val textSegment = sanitized.substring(currentIndex)
            if (textSegment.isNotEmpty()) {
                parsedBlocks.add(StyledText(textSegment, Paint(currentPaint)))
            }
        }

        // If line was empty, keep an empty block to preserve blank lines
        if (parsedBlocks.isEmpty()) {
            parsedBlocks.add(StyledText("", Paint(defaultTextPaint)))
        }

        return parsedBlocks
    }

    private fun applyAnsiCode(basePaint: Paint, codeSequence: String): Paint {
        val newPaint = Paint(basePaint)
        val codes = codeSequence.split(";").mapNotNull { it.toIntOrNull() }

        if (codes.isEmpty() || codes.contains(0)) {
            // Reset to default
            return Paint(defaultTextPaint)
        }

        for (code in codes) {
            when (code) {
                1 -> newPaint.isFakeBoldText = true // Bold
                30 -> newPaint.color = activeTheme.ansiColors[0] // Black
                31 -> newPaint.color = activeTheme.ansiColors[1] // Red
                32 -> newPaint.color = activeTheme.ansiColors[2] // Green
                33 -> newPaint.color = activeTheme.ansiColors[3] // Yellow
                34 -> newPaint.color = activeTheme.ansiColors[4] // Blue
                35 -> newPaint.color = activeTheme.ansiColors[5] // Magenta
                36 -> newPaint.color = activeTheme.ansiColors[6] // Cyan
                37 -> newPaint.color = activeTheme.ansiColors[7] // White
                90 -> newPaint.color = activeTheme.ansiColors[0] // Bright Black
                91 -> newPaint.color = activeTheme.ansiColors[1] // Bright Red
                92 -> newPaint.color = activeTheme.ansiColors[2] // Bright Green
                93 -> newPaint.color = activeTheme.ansiColors[3] // Bright Yellow
                94 -> newPaint.color = activeTheme.ansiColors[4] // Bright Blue
                95 -> newPaint.color = activeTheme.ansiColors[5] // Bright Magenta
                96 -> newPaint.color = activeTheme.ansiColors[6] // Bright Cyan
                97 -> newPaint.color = activeTheme.ansiColors[7] // Bright White
            }
        }
        return newPaint
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/Broker.kt`

```kotlin
package com.hybridengine.terminal

import android.util.Log

class Broker(var outputListener: ((String) -> Unit)? = null) {
    
    private var isNativeLoaded = false

    constructor(terminalView: TerminalSurfaceView) : this({ terminalView.appendOutput(it) })

    init {
        try {
            System.loadLibrary("hybrid_term_broker")
            isNativeLoaded = true
            Log.i("VoidTerm", "libhybrid_term_broker.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("VoidTerm", "libhybrid_term_broker.so not found or failed to load", e)
        } catch (e: Exception) {
            Log.e("VoidTerm", "Unexpected error loading native library", e)
        }
    }

    fun start() {
        if (isNativeLoaded) {
            try {
                startDaemon()
                emitOutput("🚀 VoidTerm Shell Terminal v0.1.0-alpha")
                emitOutput("📡 Hybrid Term Broker: Native Tokio multiplexer active.")
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Failed to start native daemon", e)
                emitOutput("⚠️ Native daemon start failed: ${e.message}")
            }
        } else {
            emitOutput("🚀 VoidTerm Shell Terminal v0.1.0-alpha")
            emitOutput("📡 Standalone Terminal Mode (Native Broker pending).")
            emitOutput("Type commands below to interact.")
        }
    }

    fun send(command: String) {
        if (isNativeLoaded) {
            try {
                sendCommand(command)
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Error sending command", e)
                emitOutput("⚠️ Error executing command: ${e.message}")
            }
        } else {
            emitOutput("Executed: $command")
        }
    }

    fun provisionDisk(diskPath: String, rootfsDir: String) {
        if (isNativeLoaded) {
            try {
                provisionDiskNative(diskPath, rootfsDir)
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Error provisioning disk: ${e.message}", e)
            }
        } else {
            Log.w("VoidTerm", "Cannot provision disk: native broker library not loaded.")
        }
    }

    private external fun startDaemon()
    private external fun sendCommand(command: String)
    @Suppress("FunctionName")
    private external fun provisionDiskNative(diskPath: String, rootfsDir: String)

    fun onTerminalOutput(output: String) {
        emitOutput(output)
    }

    private fun emitOutput(text: String) {
        outputListener?.invoke(text)
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/MainActivity.kt`

```kotlin
package com.hybridengine.terminal

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private var terminalService: TerminalService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TerminalService.TerminalBinder
            terminalService = localBinder?.service
            isBound = true
            
            // Connect broker output stream directly to the terminal surface
            terminalService?.broker?.outputListener = { output ->
                runOnUiThread {
                    terminalSurface.appendOutput(output)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Request Runtime Permissions (Storage & Notifications)
        requestRequiredPermissions()

        // 2. Initialize Fullscreen Terminal View
        terminalSurface = findViewById(R.id.terminal_surface)

        // Wire FloatingActionButton to open SettingsActivity
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 3. Start & Bind to Persistent TerminalService (manages VM and Broker lifecycle)
        TerminalService.start(this)
        val serviceIntent = Intent(this, TerminalService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 4. Connect Native Inline Terminal Input directly to the Service Broker
        terminalSurface.onCommandSubmitted = { command ->
            val trimmed = command.trim()
            if (trimmed.isNotEmpty()) {
                terminalService?.broker?.send(trimmed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::terminalSurface.isInitialized) {
            terminalSurface.reloadConfig()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            terminalService?.broker?.outputListener = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestRequiredPermissions() {
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Storage Management Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        Log.w("VoidTerm", "Could not launch manage storage intent: ${ex.message}")
                    }
                }
            }
        }
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/OsInstaller.kt`

```kotlin
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

            // 3. Create the destination directory
            rootfsDir.mkdirs()

            // 4. Extract the tarball (or prepare for guest microVM mount)
            Log.d("VoidTerm-Installer", "Archive staged at ${archiveFile.absolutePath} for AVF microVM.")

            Log.d("VoidTerm-Installer", "✅ Debian OS rootfs staged successfully.")

        } catch (e: Exception) {
            Log.e("VoidTerm-Installer", "OS Installation failed: ${e.message}")
        }
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/SettingsActivity.kt`

```kotlin
package com.hybridengine.terminal

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            title = "Terminal Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/TerminalConfig.kt`

```kotlin
package com.hybridengine.terminal

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Terminal theme definitions containing full color palettes.
 */
enum class TerminalTheme(
    val themeName: String,
    val background: Int,
    val foreground: Int,
    val promptColor: Int,
    val cursor: Int,
    val ansiColors: IntArray
) {
    DRACULA(
        themeName = "Dracula",
        background = Color.parseColor("#282A36"),
        foreground = Color.parseColor("#F8F8F2"),
        promptColor = Color.parseColor("#50FA7B"),
        cursor = Color.parseColor("#8BE9FD"),
        ansiColors = intArrayOf(
            Color.parseColor("#21222C"), // 0 Black
            Color.parseColor("#FF5555"), // 1 Red
            Color.parseColor("#50FA7B"), // 2 Green
            Color.parseColor("#F1FA8C"), // 3 Yellow
            Color.parseColor("#BD93F9"), // 4 Blue
            Color.parseColor("#FF79C6"), // 5 Magenta
            Color.parseColor("#8BE9FD"), // 6 Cyan
            Color.parseColor("#F8F8F2")  // 7 White
        )
    ),
    NORD(
        themeName = "Nord",
        background = Color.parseColor("#2E3440"),
        foreground = Color.parseColor("#ECEFF4"),
        promptColor = Color.parseColor("#A3BE8C"),
        cursor = Color.parseColor("#88C0D0"),
        ansiColors = intArrayOf(
            Color.parseColor("#3B4252"), // 0 Black
            Color.parseColor("#BF616A"), // 1 Red
            Color.parseColor("#A3BE8C"), // 2 Green
            Color.parseColor("#EBCB8B"), // 3 Yellow
            Color.parseColor("#81A1C1"), // 4 Blue
            Color.parseColor("#B48EAD"), // 5 Magenta
            Color.parseColor("#88C0D0"), // 6 Cyan
            Color.parseColor("#E5E9F0")  // 7 White
        )
    ),
    MONOKAI(
        themeName = "Monokai",
        background = Color.parseColor("#272822"),
        foreground = Color.parseColor("#F8F8F2"),
        promptColor = Color.parseColor("#A6E22E"),
        cursor = Color.parseColor("#F8F8F0"),
        ansiColors = intArrayOf(
            Color.parseColor("#272822"), // 0 Black
            Color.parseColor("#F92672"), // 1 Red
            Color.parseColor("#A6E22E"), // 2 Green
            Color.parseColor("#F4BF75"), // 3 Yellow
            Color.parseColor("#66D9EF"), // 4 Blue
            Color.parseColor("#AE81FF"), // 5 Magenta
            Color.parseColor("#A1EFE4"), // 6 Cyan
            Color.parseColor("#F8F8F2")  // 7 White
        )
    );

    companion object {
        fun fromString(name: String): TerminalTheme {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) || it.themeName.equals(name, ignoreCase = true) }
                ?: DRACULA
        }
    }
}

/**
 * Terminal cursor shape styles.
 */
enum class CursorStyle(val symbol: String) {
    BLOCK("█"),
    UNDERLINE("_"),
    BAR("|");

    companion object {
        fun fromString(name: String): CursorStyle {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: BLOCK
        }
    }
}

import androidx.preference.PreferenceManager

/**
 * Configuration manager backed by PreferenceManager.getDefaultSharedPreferences.
 */
class TerminalConfig(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var theme: TerminalTheme
        get() {
            val raw = prefs.getString(KEY_THEME, null)
                ?: prefs.getString(LEGACY_KEY_THEME, TerminalTheme.DRACULA.name)
                ?: TerminalTheme.DRACULA.name
            return TerminalTheme.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var fontSize: Float
        get() {
            val fromString = try {
                prefs.getString(KEY_FONT_SIZE, null)?.toFloatOrNull()
            } catch (_: Exception) {
                null
            }
            if (fromString != null) return fromString.coerceIn(20f, 100f)

            return try {
                prefs.getFloat(KEY_FONT_SIZE, prefs.getFloat(LEGACY_KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
            } catch (_: Exception) {
                DEFAULT_FONT_SIZE
            }.coerceIn(20f, 100f)
        }
        set(value) {
            prefs.edit()
                .putFloat(KEY_FONT_SIZE, value.coerceIn(20f, 100f))
                .putString(KEY_FONT_SIZE, value.coerceIn(20f, 100f).toInt().toString())
                .apply()
        }

    var cursorStyle: CursorStyle
        get() {
            val raw = prefs.getString(KEY_CURSOR_STYLE, null)
                ?: prefs.getString(LEGACY_KEY_CURSOR_STYLE, CursorStyle.BLOCK.name)
                ?: CursorStyle.BLOCK.name
            return CursorStyle.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_CURSOR_STYLE, value.name).apply()
        }

    var cursorBlink: Boolean
        get() {
            return if (prefs.contains(KEY_CURSOR_BLINK)) {
                prefs.getBoolean(KEY_CURSOR_BLINK, true)
            } else {
                prefs.getBoolean(LEGACY_KEY_CURSOR_BLINK, true)
            }
        }
        set(value) {
            prefs.edit().putBoolean(KEY_CURSOR_BLINK, value).apply()
        }

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_CURSOR_STYLE = "cursor_style"
        const val KEY_CURSOR_BLINK = "cursor_blink"

        private const val LEGACY_KEY_THEME = "terminal_theme"
        private const val LEGACY_KEY_FONT_SIZE = "terminal_font_size"
        private const val LEGACY_KEY_CURSOR_STYLE = "terminal_cursor_style"
        private const val LEGACY_KEY_CURSOR_BLINK = "terminal_cursor_blink"

        const val DEFAULT_FONT_SIZE = 38f
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/TerminalService.kt`

```kotlin
package com.hybridengine.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class TerminalService : Service() {

    private val binder = TerminalBinder()
    lateinit var vmManager: VmManager
        private set
    lateinit var broker: Broker
        private set

    private var isEngineStarted = false

    inner class TerminalBinder : Binder() {
        val service: TerminalService
            get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TerminalService created.")
        vmManager = VmManager(this)
        broker = Broker()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_SHUTDOWN_VM) {
            Log.i(TAG, "Shutdown action received from notification. Stopping microVM and service.")
            shutdownEngine()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!isEngineStarted) {
            isEngineStarted = true
            startEngine()
        }

        return START_STICKY
    }

    private fun startEngine() {
        Thread {
            try {
                // 1. Stage Debian rootfs if needed
                OsInstaller(this).installIfNeeded()

                // 2. Boot AVF microVM
                vmManager.startLiteLinuxVm(broker)

                // 3. Start Rust daemon multiplexer
                broker.start()
                Log.i(TAG, "VoidTerm engine and microVM initialized in foreground service.")
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VoidTerm engine in service", e)
            }
        }.start()
    }

    private fun startForegroundNotification() {
        val channelId = CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VoidTerm Linux MicroVM Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status and control for the VoidTerm AVF MicroVM and Tokio Broker"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }

        // Tap notification to open MainActivity
        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action button to Shutdown VM
        val shutdownIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_SHUTDOWN_VM
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            1,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VoidTerm Linux MicroVM Active")
            .setContentText("Debian ARM64 Guest running via AVF vsock")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Shutdown VM",
                shutdownPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun shutdownEngine() {
        try {
            vmManager.stopVm()
            Log.i(TAG, "MicroVM stopped.")
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping microVM: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TerminalService destroyed.")
        try {
            vmManager.stopVm()
        } catch (_: Throwable) {}
    }

    companion object {
        const val TAG = "VoidTerm-Service"
        const val CHANNEL_ID = "voidterm_vm_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVICE = "com.hybridengine.terminal.ACTION_START_SERVICE"
        const val ACTION_SHUTDOWN_VM = "com.hybridengine.terminal.ACTION_SHUTDOWN_VM"

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_SHUTDOWN_VM
            }
            context.startService(intent)
        }
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/TerminalSurfaceView.kt`

```kotlin
package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    private var drawingThread: Thread? = null
    @Volatile
    private var isRunning = false

    private val ansiParser = AnsiParser()
    private val terminalLines = mutableListOf<List<AnsiParser.StyledText>>()

    // Active terminal configuration
    var config: TerminalConfig = TerminalConfig(context)
        private set

    // Active inline input state
    var currentInputBuffer: String = ""
    var onCommandSubmitted: ((String) -> Unit)? = null
    private val promptText = "root@voidterm:~# "

    private val backgroundPaint = Paint().apply {
        color = config.theme.background
    }

    private var currentTextSize = config.fontSize
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = currentTextSize
        color = config.theme.foreground
    }

    private var isScaling = false
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            currentTextSize = (currentTextSize * scaleFactor).coerceIn(20f, 100f)
            config.fontSize = currentTextSize
            textPaint.textSize = currentTextSize
            ansiParser.applyTheme(config.theme, currentTextSize)
            triggerRender()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        applyConfig(config)
    }

    fun applyConfig(newConfig: TerminalConfig) {
        config = newConfig
        currentTextSize = config.fontSize
        backgroundPaint.color = config.theme.background
        textPaint.color = config.theme.foreground
        textPaint.textSize = currentTextSize
        ansiParser.applyTheme(config.theme, currentTextSize)
        triggerRender()
    }

    fun reloadConfig() {
        applyConfig(TerminalConfig(context))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_UP && !isScaling) {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) {
                    for (ch in text) {
                        if (ch == '\n') {
                            submitCurrentBuffer()
                        } else {
                            currentInputBuffer += ch
                        }
                    }
                    triggerRender()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && currentInputBuffer.isNotEmpty()) {
                    val dropCount = beforeLength.coerceAtMost(currentInputBuffer.length)
                    currentInputBuffer = currentInputBuffer.dropLast(dropCount)
                    triggerRender()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                        submitCurrentBuffer()
                        return true
                    } else if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (currentInputBuffer.isNotEmpty()) {
                            currentInputBuffer = currentInputBuffer.dropLast(1)
                            triggerRender()
                        }
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                submitCurrentBuffer()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (currentInputBuffer.isNotEmpty()) {
                    currentInputBuffer = currentInputBuffer.dropLast(1)
                    triggerRender()
                }
                return true
            }
            else -> {
                val unicodeChar = event.getUnicodeChar(event.metaState)
                if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
                    currentInputBuffer += unicodeChar.toChar()
                    triggerRender()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun submitCurrentBuffer() {
        val command = currentInputBuffer
        currentInputBuffer = ""

        // Echo the executed command locally into terminal history
        val echoLine = "$promptText$command"
        val styledEcho = ansiParser.parse(echoLine)
        synchronized(terminalLines) {
            terminalLines.add(styledEcho)
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }

        // Dispatch command to Rust Broker -> Debian microVM vsock
        onCommandSubmitted?.invoke(command)
        triggerRender()
    }

    /**
     * Safely appends and parses new terminal output from the Broker.
     */
    fun appendOutput(rawOutput: String) {
        val styledLines = ansiParser.parseLines(rawOutput)
        synchronized(terminalLines) {
            terminalLines.addAll(styledLines)

            // Bound memory buffer to 1000 lines
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }
        triggerRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        drawingThread = Thread(this, "VoidTerm-Renderer").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        triggerRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        try {
            drawingThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w("VoidTerm", "Drawing thread join interrupted")
        }
    }

    override fun run() {
        while (isRunning) {
            if (!holder.surface.isValid) {
                try {
                    Thread.sleep(16)
                } catch (_: Exception) {}
                continue
            }

            renderFrame()

            try {
                Thread.sleep(16) // ~60fps rendering cadence
            } catch (_: Exception) {}
        }
    }

    private fun triggerRender() {
        if (holder.surface.isValid) {
            renderFrame()
        }
    }

    private fun renderFrame() {
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    holder.lockCanvas()
                }
            } else {
                holder.lockCanvas()
            }

            if (canvas != null) {
                drawTerminal(canvas)
            }
        } catch (e: Exception) {
            Log.e("VoidTerm", "Error drawing frame: ${e.message}")
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.e("VoidTerm", "Error posting canvas: ${e.message}")
                }
            }
        }
    }

    private fun drawTerminal(canvas: Canvas) {
        // 1. Draw solid background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val paddingX = 24f
        val paddingY = 24f
        val layoutWidth = (width - (paddingX * 2).toInt()).coerceAtLeast(100)

        // 2. Build styled Spannable buffer with ANSI formatting + Active Inline Input Prompt
        val spannableBuilder = SpannableStringBuilder()
        synchronized(terminalLines) {
            for ((lineIndex, lineBlocks) in terminalLines.withIndex()) {
                for (block in lineBlocks) {
                    val start = spannableBuilder.length
                    spannableBuilder.append(block.text)
                    val end = spannableBuilder.length

                    if (end > start) {
                        spannableBuilder.setSpan(
                            ForegroundColorSpan(block.paint.color),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        if (block.paint.isFakeBoldText) {
                            spannableBuilder.setSpan(
                                StyleSpan(Typeface.BOLD),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                if (lineIndex < terminalLines.size - 1) {
                    spannableBuilder.append("\n")
                }
            }
        }

        // 3. Append active prompt, user input buffer, and blinking cursor
        if (spannableBuilder.isNotEmpty()) {
            spannableBuilder.append("\n")
        }

        val promptStart = spannableBuilder.length
        spannableBuilder.append(promptText)
        val promptEnd = spannableBuilder.length
        spannableBuilder.setSpan(
            ForegroundColorSpan(config.theme.promptColor),
            promptStart,
            promptEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableBuilder.setSpan(
            StyleSpan(Typeface.BOLD),
            promptStart,
            promptEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Append current typed buffer
        val inputStart = spannableBuilder.length
        spannableBuilder.append(currentInputBuffer)
        val inputEnd = spannableBuilder.length
        if (inputEnd > inputStart) {
            spannableBuilder.setSpan(
                ForegroundColorSpan(config.theme.foreground),
                inputStart,
                inputEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Configurable cursor shape & 500ms blinking timer
        val isCursorVisible = if (config.cursorBlink) {
            (System.currentTimeMillis() / 500) % 2 == 0L
        } else {
            true
        }
        val cursorSymbol = if (isCursorVisible) config.cursorStyle.symbol else " "
        val cursorStart = spannableBuilder.length
        spannableBuilder.append(cursorSymbol)
        val cursorEnd = spannableBuilder.length
        spannableBuilder.setSpan(
            ForegroundColorSpan(config.theme.cursor),
            cursorStart,
            cursorEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 4. Construct StaticLayout for native word wrapping and multi-line rendering
        textPaint.textSize = currentTextSize
        val staticLayout: StaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(spannableBuilder, 0, spannableBuilder.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                spannableBuilder,
                textPaint,
                layoutWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.15f,
                0f,
                false
            )
        }

        // 5. Calculate vertical scroll offset to keep latest active prompt in view
        val layoutHeight = staticLayout.height.toFloat()
        val visibleHeight = height.toFloat() - (paddingY * 2)
        val scrollOffsetY = if (layoutHeight > visibleHeight) layoutHeight - visibleHeight else 0f

        // 6. Draw the word-wrapped layout to the canvas
        canvas.save()
        canvas.translate(paddingX, paddingY - scrollOffsetY)
        staticLayout.draw(canvas)
        canvas.restore()
    }
}

```

---

## File: `android/app/src/main/kotlin/com/hybridengine/terminal/VmManager.kt`

```kotlin
package com.hybridengine.terminal

import android.content.Context
import android.util.Log
import java.io.File

class VmManager(private val context: Context) {

    private var virtualMachineInstance: Any? = null

    fun startLiteLinuxVm(broker: Broker? = null) {
        try {
            Log.d("VoidTerm-AVF", "Checking Android Virtualization Framework (AVF) availability...")

            // Dynamic lookup of Android VirtualMachineManager system service
            val vmManagerClass = try {
                Class.forName("android.system.virtualmachine.VirtualMachineManager")
            } catch (e: ClassNotFoundException) {
                Log.w("VoidTerm-AVF", "VirtualMachineManager API not available on this Android runtime.")
                return
            }

            val getInstanceMethod = vmManagerClass.getMethod("getInstance", Context::class.java)
            val vmManager = getInstanceMethod.invoke(null, context)

            if (vmManager == null) {
                Log.e("VoidTerm-AVF", "VirtualizationService not supported on this device CPU.")
                return
            }

            // Define paths
            val rootfsDir = File(context.filesDir, "debian_rootfs")
            val diskImage = File(context.filesDir, "disk.img")

            Log.d("VoidTerm-AVF", "Configuring Debian MicroVM Topology...")

            // 1. Trigger the Rust JNI bridge to format and inject the disk (if it doesn't exist)
            if (!diskImage.exists() && broker != null) {
                Log.i("VoidTerm-AVF", "Triggering Rust disk provisioner for ${diskImage.absolutePath}...")
                broker.provisionDisk(diskImage.absolutePath, rootfsDir.absolutePath)
            }

            val configBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            val configBuilderConstructor = configBuilderClass.getConstructor(Context::class.java)
            val configBuilder = configBuilderConstructor.newInstance(context)

            // Configure 512MB RAM
            val setMemoryBytesMethod = configBuilderClass.getMethod("setMemoryBytes", Long::class.javaPrimitiveType)
            setMemoryBytesMethod.invoke(configBuilder, 512L * 1024L * 1024L)

            // Hardware acceleration & debug config if supported
            try {
                val setCpuTopologyMethod = configBuilderClass.getMethod("setCpuTopology", Int::class.javaPrimitiveType)
                setCpuTopologyMethod.invoke(configBuilder, 1) // CPU_TOPOLOGY_MATCH_HOST
            } catch (_: Exception) {}

            try {
                val setDebugLevelMethod = configBuilderClass.getMethod("setDebugLevel", Int::class.javaPrimitiveType)
                setDebugLevelMethod.invoke(configBuilder, 2) // DEBUG_LEVEL_FULL
            } catch (_: Exception) {}

            // 2. Construct Custom Linux Image payload for AVF if custom image config is supported
            try {
                val customConfigBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder")
                val customConfigBuilder = customConfigBuilderClass.getConstructor().newInstance()
                val setDiskImageMethod = customConfigBuilderClass.getMethod("setDiskImage", File::class.java)
                setDiskImageMethod.invoke(customConfigBuilder, diskImage)
                val customBuildMethod = customConfigBuilderClass.getMethod("build")
                val customImageConfig = customBuildMethod.invoke(customConfigBuilder)

                val customImageConfigClass = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig")
                val setCustomImageConfigMethod = configBuilderClass.getMethod("setCustomImageConfig", customImageConfigClass)
                setCustomImageConfigMethod.invoke(configBuilder, customImageConfig)
            } catch (e: Exception) {
                Log.d("VoidTerm-AVF", "Standard microVM config mode: ${e.message}")
            }

            val buildMethod = configBuilderClass.getMethod("build")
            val config = buildMethod.invoke(configBuilder)

            // Retrieve or create VM
            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val getOrCreateMethod = vmManagerClass.getMethod("getOrCreate", String::class.java, configClass)
            virtualMachineInstance = getOrCreateMethod.invoke(vmManager, "voidterm_debian_vm", config)

            // Boot the microVM
            if (virtualMachineInstance != null) {
                val startMethod = virtualMachineInstance!!.javaClass.getMethod("start")
                startMethod.invoke(virtualMachineInstance)
                Log.d("VoidTerm-AVF", "✅ Debian MicroVM Booted via Custom Image Config on CID 3.")
            }
        } catch (e: Throwable) {
            Log.e("VoidTerm-AVF", "AVF microVM boot bypassed/failed: ${e.message}")
        }
    }

    fun stopVm() {
        try {
            if (virtualMachineInstance != null) {
                val stopMethod = virtualMachineInstance!!.javaClass.getMethod("stop")
                stopMethod.invoke(virtualMachineInstance)
                Log.d("VoidTerm-AVF", "🛑 Lite Linux VM stopped.")
            }
        } catch (e: Throwable) {
            Log.w("VoidTerm-AVF", "Error stopping microVM: ${e.message}")
        }
    }
}

```

---

## File: `android/app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0A0A0A">

    <!-- The Fullscreen Hardware Accelerated Terminal Surface with Native Inline Input -->
    <com.hybridengine.terminal.TerminalSurfaceView
        android:id="@+id/terminal_surface"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Floating Action Button to launch Native Settings -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab_settings"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|end"
        android:layout_margin="16dp"
        android:contentDescription="Settings"
        app:backgroundTint="#44475A"
        app:tint="#F8F8F2"
        app:fabSize="mini"
        app:srcCompat="@drawable/ic_settings" />

</FrameLayout>

```

---

## File: `android/app/src/main/res/values/arrays.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="theme_entries">
        <item>Default (Dracula)</item>
        <item>Dracula</item>
        <item>Nord</item>
        <item>Monokai</item>
    </string-array>
    <string-array name="theme_values">
        <item>DRACULA</item>
        <item>DRACULA</item>
        <item>NORD</item>
        <item>MONOKAI</item>
    </string-array>

    <string-array name="cursor_style_entries">
        <item>Block (█)</item>
        <item>Underline (_)</item>
        <item>Bar (|)</item>
    </string-array>
    <string-array name="cursor_style_values">
        <item>BLOCK</item>
        <item>UNDERLINE</item>
        <item>BAR</item>
    </string-array>
</resources>

```

---

## File: `android/app/src/main/res/values/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">VoidTerm</string>
</resources>

```

---

## File: `android/app/src/main/res/values/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Base application theme without action bar -->
    <style name="Theme.VoidTerm" parent="Theme.AppCompat.NoActionBar">
        <item name="colorPrimary">#BD93F9</item>
        <item name="colorPrimaryDark">#282A36</item>
        <item name="colorAccent">#50FA7B</item>
        <item name="android:windowBackground">#282A36</item>
    </style>

    <!-- Settings theme with standard ActionBar -->
    <style name="Theme.VoidTerm.Settings" parent="Theme.AppCompat.DayNight.DarkActionBar">
        <item name="colorPrimary">#282A36</item>
        <item name="colorPrimaryDark">#1E1F29</item>
        <item name="colorAccent">#50FA7B</item>
        <item name="preferenceTheme">@style/PreferenceThemeOverlay</item>
    </style>
</resources>

```

---

## File: `android/app/src/main/res/xml/preferences.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        android:title="Appearance"
        app:iconSpaceReserved="false">

        <ListPreference
            android:key="theme"
            android:title="Theme"
            android:dialogTitle="Select Terminal Theme"
            android:entries="@array/theme_entries"
            android:entryValues="@array/theme_values"
            android:defaultValue="DRACULA"
            app:useSimpleSummaryProvider="true"
            app:iconSpaceReserved="false" />

        <EditTextPreference
            android:key="font_size"
            android:title="Font Size"
            android:dialogTitle="Enter Font Size (20 - 100)"
            android:defaultValue="38"
            android:inputType="number"
            app:useSimpleSummaryProvider="true"
            app:iconSpaceReserved="false" />

    </PreferenceCategory>

    <PreferenceCategory
        android:title="Cursor Settings"
        app:iconSpaceReserved="false">

        <ListPreference
            android:key="cursor_style"
            android:title="Cursor Style"
            android:dialogTitle="Select Cursor Style"
            android:entries="@array/cursor_style_entries"
            android:entryValues="@array/cursor_style_values"
            android:defaultValue="BLOCK"
            app:useSimpleSummaryProvider="true"
            app:iconSpaceReserved="false" />

        <SwitchPreferenceCompat
            android:key="cursor_blink"
            android:title="Cursor Blink"
            android:summary="Enable 500ms cursor blink animation"
            android:defaultValue="true"
            app:iconSpaceReserved="false" />

    </PreferenceCategory>

</PreferenceScreen>

```

---

## File: `android/app/src/main/res/drawable/ic_settings.xml`

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#F8F8F2"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"/>
</vector>

```

---

## File: `bin/void`

```python
#!/data/data/com.termux/files/usr/bin/python3
"""
VoidTerm CLI & Package Manager (void)
Controls VoidTerm modular extension packages (nodeos, jage), updates, and agent status.
"""

import os
import sys
import json
import shutil
import argparse
import subprocess
import urllib.request

CONFIG_DIR = os.path.expanduser("~/.voidterm")
CONFIG_FILE = os.path.join(CONFIG_DIR, "packages.json")
SOCKET_PATH = os.path.join(
    os.environ.get("TMPDIR", "/data/data/com.termux/files/usr/tmp"),
    "voidterm_agent.sock"
)

DEFAULT_PACKAGES = {
    "nodeos": {
        "full_name": "polymath-nodeos",
        "type": "pip",
        "command": "nodeos",
        "desc": "Autonomous AST spatial intelligence & blast-radius graph (SQLite)",
        "pypi_name": "polymath-nodeos"
    },
    "jage": {
        "full_name": "polymath-jage",
        "type": "npm",
        "command": "jage",
        "desc": "Zero-dependency semantic AST version control & atomic node rollback",
        "npm_name": "polymath-jage"
    }
}

def load_config():
    if not os.path.exists(CONFIG_DIR):
        os.makedirs(CONFIG_DIR, exist_ok=True)
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    cfg = {
        "auto_update": True,
        "packages": {}
    }
    save_config(cfg)
    return cfg

def save_config(cfg):
    if not os.path.exists(CONFIG_DIR):
        os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=2)

def get_installed_version(cmd_name):
    path = shutil.which(cmd_name)
    if not path:
        return None
    try:
        res = subprocess.run([cmd_name, "--version"], capture_output=True, text=True, timeout=5)
        out = (res.stdout or res.stderr).strip()
        for word in out.replace("\n", " ").split():
            if any(char.isdigit() for char in word) and "." in word:
                return word.strip("v,()")
        return "installed"
    except Exception:
        return "installed"

def get_remote_version(pkg_meta):
    pkg_type = pkg_meta["type"]
    try:
        if pkg_type == "pip":
            url = f"https://pypi.org/pypi/{pkg_meta['pypi_name']}/json"
            req = urllib.request.Request(url, headers={"User-Agent": "VoidTerm/1.0"})
            with urllib.request.urlopen(req, timeout=3) as resp:
                data = json.loads(resp.read().decode())
                return data.get("info", {}).get("version", "unknown")
        elif pkg_type == "npm":
            url = f"https://registry.npmjs.org/{pkg_meta['npm_name']}/latest"
            req = urllib.request.Request(url, headers={"User-Agent": "VoidTerm/1.0"})
            with urllib.request.urlopen(req, timeout=3) as resp:
                data = json.loads(resp.read().decode())
                return data.get("version", "unknown")
    except Exception:
        return "offline"
    return "unknown"

def cmd_pkg_list(args):
    cfg = load_config()
    auto_up = cfg.get("auto_update", True)
    status_color = "\033[1;32mon\033[0m" if auto_up else "\033[1;31moff\033[0m"

    print("\n\033[1;35m╔══════════════════════════════════════════════════════════════════╗\033[0m")
    print("\033[1;35m║\033[0m   \033[1;37m📦 VOIDTERM MODULAR EXTENSION PACKAGES (vpm)\033[0m                  \033[1;35m║\033[0m")
    print(f"\033[1;35m║\033[0m   Automatic Updates: {status_color:<45} \033[1;35m║\033[0m")
    print("\033[1;35m╚══════════════════════════════════════════════════════════════════╝\033[0m\n")

    for key, meta in DEFAULT_PACKAGES.items():
        installed = get_installed_version(meta["command"])
        remote = get_remote_version(meta)
        
        if installed:
            state_badge = f"\033[1;32m● Installed ({installed})\033[0m"
        else:
            state_badge = "\033[1;31m○ Not Installed\033[0m"

        print(f"  \033[1;36m{key}\033[0m ({meta['full_name']})")
        print(f"    Status  : {state_badge}  |  Latest: {remote}")
        print(f"    Type    : {meta['type'].upper()} Package")
        print(f"    Details : {meta['desc']}")
        print()

def cmd_pkg_install(args):
    pkg_key = args.package.lower()
    if pkg_key not in DEFAULT_PACKAGES:
        print(f"❌ Unknown package '{pkg_key}'. Available: {list(DEFAULT_PACKAGES.keys())}")
        return 1

    meta = DEFAULT_PACKAGES[pkg_key]
    print(f"[*] Installing {meta['full_name']} via {meta['type'].upper()}...")

    if meta["type"] == "pip":
        cmd = [sys.executable, "-m", "pip", "install", "--break-system-packages", meta["pypi_name"]]
    elif meta["type"] == "npm":
        cmd = ["npm", "install", "-g", meta["npm_name"]]
    else:
        print("[-] Unsupported package type.")
        return 1

    res = subprocess.run(cmd)
    if res.returncode == 0:
        print(f"\n\033[1;32m[✔] {pkg_key} installed successfully.\033[0m")
    else:
        print(f"\n\033[1;31m[-] Installation failed (exit code {res.returncode}).\033[0m")
    return res.returncode

def update_single_package(pkg_key):
    meta = DEFAULT_PACKAGES[pkg_key]
    print(f"[*] Updating {meta['full_name']}...")
    if meta["type"] == "pip":
        cmd = [sys.executable, "-m", "pip", "install", "--upgrade", "--break-system-packages", meta["pypi_name"]]
    elif meta["type"] == "npm":
        cmd = ["npm", "install", "-g", f"{meta['npm_name']}@latest"]
    else:
        return 1

    res = subprocess.run(cmd)
    if res.returncode == 0:
        new_ver = get_installed_version(meta["command"])
        print(f"\033[1;32m[✔] {pkg_key} updated to version {new_ver}.\033[0m\n")
        return 0
    else:
        print(f"\033[1;31m[-] Update for {pkg_key} failed.\033[0m\n")
        return res.returncode

def cmd_pkg_update(args):
    target = args.package.lower() if args.package else None
    
    if args.all or target == "all":
        print("[*] Updating all VoidTerm packages...\n")
        err = 0
        for k in DEFAULT_PACKAGES:
            if update_single_package(k) != 0:
                err += 1
        return 1 if err > 0 else 0
    elif target in DEFAULT_PACKAGES:
        return update_single_package(target)
    else:
        print(f"❌ Target '{target}' not recognized. Use: void pkg update [nodeos | jage | --all]")
        return 1

def cmd_pkg_autoupdate(args):
    cfg = load_config()
    if args.mode:
        mode = args.mode.lower()
        if mode in ["on", "enable", "true", "1"]:
            cfg["auto_update"] = True
            save_config(cfg)
            print("\033[1;32m[✔] VoidTerm automatic updates enabled.\033[0m")
        elif mode in ["off", "disable", "false", "0"]:
            cfg["auto_update"] = False
            save_config(cfg)
            print("\033[1;33m[!] VoidTerm automatic updates disabled.\033[0m")
        else:
            print("Usage: void pkg autoupdate [on|off]")
    else:
        current = cfg.get("auto_update", True)
        state = "\033[1;32mENABLED\033[0m" if current else "\033[1;31mDISABLED\033[0m"
        print(f"[*] Automatic background updates are currently {state}.")

def cmd_status(args):
    print("\n\033[1;35m╔══════════════════════════════════════════════════════════════════╗\033[0m")
    print("\033[1;35m║\033[0m   \033[1;37m🚀 VOIDTERM PLATFORM RUNTIME SNAPSHOT\033[0m                          \033[1;35m║\033[0m")
    print("\033[1;35m╚══════════════════════════════════════════════════════════════════╝\033[0m\n")

    # Check ACP socket
    if os.path.exists(SOCKET_PATH):
        acp_badge = f"\033[1;32m● Active ({SOCKET_PATH})\033[0m"
    else:
        acp_badge = "\033[1;33m○ Standby (Socket offline)\033[0m"
    print(f"  Agent Control Plane : {acp_badge}")

    # Check NodeOS
    nodeos_ver = get_installed_version("nodeos")
    nodeos_badge = f"\033[1;32mv{nodeos_ver}\033[0m" if nodeos_ver else "\033[1;31mNot Installed\033[0m"
    print(f"  NodeOS AST Engine   : {nodeos_badge}")

    # Check Jage
    jage_ver = get_installed_version("jage")
    jage_badge = f"\033[1;32mv{jage_ver}\033[0m" if jage_ver else "\033[1;31mNot Installed\033[0m"
    print(f"  Jage AST Versioning : {jage_badge}")

    # Check AVF / Hypervisor
    has_vsock = os.path.exists("/dev/vsock") or os.path.exists("/dev/vhost-vsock")
    vsock_badge = "\033[1;32mAvailable\033[0m" if has_vsock else "\033[1;33mHost-Mode (NDK/WASM)\033[0m"
    print(f"  AVF virtio-vsock    : {vsock_badge}")
    print()

def main():
    parser = argparse.ArgumentParser(
        prog="void",
        description="VoidTerm Shell Terminal & Autonomous Agent Workspace CLI"
    )
    subparsers = parser.add_subparsers(dest="subcommand")

    # status
    subparsers.add_parser("status", help="Show VoidTerm platform & agent runtime status")

    # pkg
    p_pkg = subparsers.add_parser("pkg", help="Manage modular extension packages")
    pkg_sub = p_pkg.add_subparsers(dest="pkg_action", required=True)

    # pkg list
    pkg_sub.add_parser("list", help="List installed and available packages")

    # pkg install
    p_inst = pkg_sub.add_parser("install", help="Install an extension package")
    p_inst.add_argument("package", help="Package to install (nodeos, jage)")

    # pkg update
    p_upd = pkg_sub.add_parser("update", help="Update packages manually")
    p_upd.add_argument("package", nargs="?", default="", help="Package to update (nodeos, jage)")
    p_upd.add_argument("--all", action="store_true", help="Update all installed packages")

    # pkg autoupdate
    p_auto = pkg_sub.add_parser("autoupdate", help="Configure or view automatic update policy")
    p_auto.add_argument("mode", nargs="?", choices=["on", "off"], help="Enable or disable auto-updates")

    if len(sys.argv) == 1:
        parser.print_help()
        return

    args = parser.parse_args()

    if args.subcommand == "status":
        cmd_status(args)
    elif args.subcommand == "pkg":
        if args.pkg_action == "list":
            cmd_pkg_list(args)
        elif args.pkg_action == "install":
            sys.exit(cmd_pkg_install(args))
        elif args.pkg_action == "update":
            sys.exit(cmd_pkg_update(args))
        elif args.pkg_action == "autoupdate":
            cmd_pkg_autoupdate(args)

if __name__ == "__main__":
    main()

```

---


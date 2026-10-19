# VoidTerm: Master Architectural Specification & System Blueprints
## Document Version: 2.0.0-PROD | Status: Approved & Active
### Target Platform: Android 13–16 (aarch64 / MediaTek Dimensity / Snapdragon) | Google Play Store Target SDK 34+

---

## 1. Architectural Philosophy & Core Directive

VoidTerm is engineered as a **dual-plane mobile execution platform**:
1. **The Human Visual Plane**: A 60 FPS hardware-accelerated terminal interface for mobile developers featuring custom gestures, on-screen extra keys, and an agent telemetry HUD.
2. **The Agent Autonomous Plane**: An embedded, non-blocking **Agent Control Plane (ACP)** operating over an isolated Unix domain socket (`voidterm_agent.sock`), granting local and remote AI swarms native subshell control, real-time screen buffer snapshots, and headless execution without hijacking the human user's screen focus.

Unlike Termux, which is permanently stalled on the Play Store due to Android 10+ W^X memory restrictions and dynamic binary loading policies, VoidTerm is built from the ground up to be **100% Google Play Store Compliant**.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        HUMAN VISUAL PLANE                              │
│         Android App (Kotlin / SurfaceView 60 FPS / Jetpack Compose)    │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │     Terminal Display Area     │  │   Agent Telemetry HUD Drawer  │  │
│  │   (VT100 / Xterm AnsiParser)  │  │   (Thought, File, Progress)   │  │
│  └───────────────▲───────────────┘  └───────────────▲───────────────┘  │
└──────────────────┼──────────────────────────────────┼──────────────────┘
                   │ JNI Bridge (libhybrid_term_broker.so)
┌──────────────────▼──────────────────────────────────▼──────────────────┐
│                     AGENT AUTONOMOUS PLANE                             │
│               Rust Async Core Daemon (hybrid-term-broker)              │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │        Agent Control Plane (ACP) Unix Domain Socket Listener     │  │
│  │              abstract://voidterm_agent.sock (JSON-RPC)           │  │
│  └───────────────┬──────────────────┬───────────────────┬───────────┘  │
│                  │                  │                   │              │
│                  ▼                  ▼                   ▼              │
│         ┌────────────────┐ ┌────────────────┐ ┌───────────────────┐    │
│         │ Multi-PTY Pool │ │ WasmEngine     │ │ VmBridge          │    │
│         │ (libc::openpty)│ │ (wasmi WASM)   │ │ (vhost-vsock)     │    │
│         └────────┬───────┘ └────────┬───────┘ └─────────┬─────────┘    │
└──────────────────┼──────────────────┼───────────────────┼──────────────┘
                   │                  │                   │
┌──────────────────▼──────────────────▼───────────────────▼──────────────┐
│                    EXECUTION TIERS (PLAY STORE COMPLIANT)              │
│  Tier 1: Bundled Native Libs   │ Tier 2: WASM Sandbox  │ Tier 3: AVF   │
│  • Read-only APK lib/arm64-v8a │ • In-process Wasmi    │ • Hardware VM │
│  • bash, busybox, coreutils    │ • 0ms cold boot       │ • crosvm      │
│  • 100% W^X Compliant          │ • Play Store Approved │ • Full glibc  │
└────────────────────────────────┴───────────────────────┴───────────────┘
```

---

## 2. Component Specifications & Codebase Topology

### 2.1 Android Application Frontend (`android/app`)
- **`TerminalSurfaceView.kt`**: High-throughput Android `SurfaceView` running a dedicated rendering thread at 60 FPS. Bypasses Android View hierarchy overhead for instant monospace terminal text drawing.
- **`AnsiParser.kt`**: State machine mapping ANSI color codes, TrueColor (24-bit RGB), cursor navigation, and screen resets to hardware `Paint` instances.
- **`Broker.kt`**: JNI gateway managing the lifecycle of the background Rust daemon and passing bi-directional text buffers.
- **`TerminalService.kt`**: Android Foreground Service maintaining process persistence under Android Low Memory Killer (LMK) with wake-lock management.
- **`VmManager.kt`**: Interacts with Android's `VirtualizationService` AIDL interfaces to provision and boot the guest AVF microVM (`crosvm`).

### 2.2 Core Rust Daemon (`hybrid-term-broker`)
- **`src/acp.rs` (Agent Control Plane)**: Multi-client async Unix domain socket server. Handles session creation, command dispatching, terminal buffer snapshots, and HUD telemetry broadcasting.
- **`src/local_pty.rs` (Native POSIX PTY Engine)**: Allocates real pseudo-terminal master/slave pairs via `libc::openpty`. Contains an in-memory 1,000-line circular scrollback buffer for instant agent screen reading.
- **`src/vm_bridge.rs` (AVF Linux Bridge)**: Connects to guest Linux microVM over `virtio-vsock` (Host CID 2 $\leftrightarrow$ Guest CID 3 on port 8000). Streams unconstrained `glibc` ELF binary execution without host emulation.
- **`src/wasm_engine.rs` (WebAssembly Host Runtime)**: In-process WebAssembly interpreter (`wasmi`) executing portable CLI utilities without hypervisor overhead.
- **`src/storage.rs` (Virtual Disk Provisioner)**: Creates and formats raw ext4 disk images for the Debian/Alpine microVM guest.

### 2.3 CLI & Modular Package Manager (`bin/void`)
- **`void status`**: Real-time diagnostic inspector for broker socket, PTY sessions, hypervisor availability, and extension packages.
- **`void pkg list`**: Live queries PyPI and NPM registries to display installed vs. latest remote package versions.
- **`void pkg update [nodeos|jage|--all]`**: Manually updates individual packages.
- **`void pkg autoupdate [on|off]`**: Configures headless background update checks.

---

## 3. The Agent Control Plane (ACP) Interface Protocol

The ACP protocol operates over line-delimited JSON messages via Unix Domain Socket (`$TMPDIR/voidterm_agent.sock`):

### Request Schema
```typescript
type AgentRequest = 
  | { action: "ping" }
  | { action: "create_session"; session_id: string; tier?: "local" | "wasm" | "vm"; workdir?: string; shell?: string; rows?: number; cols?: number }
  | { action: "exec"; session_id: string; command: string }
  | { action: "input"; session_id: string; data: string }
  | { action: "read_snapshot"; session_id: string; lines?: number }
  | { action: "hud_telemetry"; agent_name: string; status: string; thought: string; active_file?: string; progress?: number }
  | { action: "list_sessions" }
  | { action: "close_session"; session_id: string };
```

### Response Schema
```typescript
type AgentResponse = 
  | { status: "success"; message: string; session_id?: string; content?: string; sessions?: SessionInfo[] }
  | { status: "event"; event_type: "stdout" | "stderr" | "exit"; session_id: string; data: string }
  | { status: "error"; message: string };
```

---

## 4. Play Store Compliance & Hardware Virtualization Matrix

| Requirement | Host Execution (Tier 1) | WASM Execution (Tier 2) | AVF Linux VM (Tier 3) |
| :--- | :--- | :--- | :--- |
| **Execution Context** | Android Bionic Process | In-Memory Interpreter | Hardware Hypervisor (`crosvm`) |
| **Binary Location** | `lib/arm64-v8a/*.so` (Signed APK) | In-Memory Bytecode | Guest Virtual Disk (`voidterm_rootfs.img`) |
| **W^X Policy (SDK 34+)** | 100% Compliant (Read-only mapped) | 100% Compliant (Zero host execve) | 100% Compliant (Hardware Isolated) |
| **Dynamic Packages** | Static at install | Pure WebAssembly Modules | Full `apt`/`apk` (gcc, python, docker) |
| **Play Store Audit Status** | APPROVED | APPROVED | APPROVED (Android 13+ AVF Standard) |

---

## 5. Built-in Intelligence & Safety Hooks

- **NodeOS (`polymath-nodeos`)**: Intercepts file save notifications to maintain [`agy_nodeos.db`](file:///data/data/com.termux/files/home/Projects/polymath-nodeos/agy_nodeos.db). Generates QuadTree AST centroids and computes real-time blast-radius warnings before agent code commits.
- **Jage (`polymath-jage`)**: Acts as a continuous semantic code time-machine. Deconstructs files into content-addressable AST blocks. Enables atomic surgical rollbacks (`jage revert node <file>:<block> --version=<n>`) if an agent hallucinates, eliminating destructive Git resets.

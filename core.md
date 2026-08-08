# Hybrid Engine: Core Architectural Plan

## 1. Architectural Overview

The **Hybrid Engine** is a high-performance, dual-runtime execution environment designed for Android. It bridges ultra-low latency host-native execution with full Linux environment compatibility through a multi-tier architecture:

```mermaid
graph TD
    UI["Terminal UI / JNI Layer<br/>(Kotlin / C++ libvterm)"] <-->|IPC / PTY| Broker["IPC Broker Daemon<br/>(Rust + Tokio)"]
    
    subgraph Host["Android Host Process (Bionic)"]
        Broker -->|Lightweight / Fast I/O| WasmEngine["Host WASM Engine<br/>(WasmEdge via NDK)"]
        WasmEngine -->|Native Host Functions| HostPlugins["Android Host Plugins<br/>(Sensors, Net, Clipboard)"]
    end
    
    subgraph MicroVM["Guest Linux VM (Glibc / Isolated)"]
        Broker <-->|virtio-vsock (Hypervisor Bus)| VsockDaemon["Guest VM Daemon<br/>(vsock listener)"]
        VsockDaemon -->|POSIX Exec| LinuxEnv["Linux Binaries / Toolchains<br/>(Python, Django, Containers, C/C++)"]
    end
```

### Key Subsystems:
1. **The UI / JNI Layer (Kotlin/C++)**: Renders the terminal emulator using high-performance terminal emulation libraries like `libvterm`. Handles touch, input events, window resizing, and frame rendering.
2. **The IPC Broker (Rust)**: The central nervous system running as a host daemon. It intercepts terminal inputs, inspects command semantics, and asynchronously routes tasks to the appropriate execution engine.
3. **The Host WASM Engine**: Embedded directly into the host application process using the Android NDK for zero-latency execution, bypassing hypervisor and emulation layers.
4. **The Guest Linux Engine (AVF)**: A hardware-isolated microVM booted via the Android Virtualization Framework (AVF) running standard `glibc`-compiled Linux binaries.

---

## 2. Phased Implementation Strategy

### Phase 1: Initialize the IPC Broker Daemon
The core of this environment is the asynchronous message broker.
* **Technology**: Rust with `tokio` for memory safety, concurrency, and high-throughput async I/O.
* **Role**: Sits between the Terminal UI and execution engines.
* **Command Routing Logic**:
  * Lightweight commands, CI workflow tracking, system automations, and text transformations are instantly routed to the **Host WASM Runtime**.
  * Heavy computational environments, full Linux shell utilities, Python/Django stacks, package managers, and container workflows are routed over a hypervisor socket to the **Guest VM**.

### Phase 2: Embed the WASM Runtime via Android NDK
Avoid the "virtualization tax" of hypervisors by executing portable modules directly on the Android host.
* **Runtime**: WasmEdge embedded via C/Rust NDK APIs.
* **Native Host Functions**: Custom Android native host functions registered as WASM plugins (e.g., querying hardware sensors, battery telemetry, network state management, clipboard access) executing at native Bionic speed, providing an ultra-fast modern alternative to legacy Termux:API.

### Phase 3: Provision the Guest VM using AVF
Run standard, un-modified `glibc`-compiled Linux ELF binaries with hardware-assisted virtualization.
* **VM Lifecycle Management**: Interfacing with Android's `VirtualizationService` via AIDL APIs to start, monitor, configure, and teardown microVM instances.
* **Execution Backend**: `VirtualizationService` provisions an instance of `crosvm` (Virtual Machine Monitor) running a tailored Linux kernel and rootfs, isolated from host Bionic C library constraints.

### Phase 4: Establish vhost-vsock Communication
Bypass TCP/IP network stack overhead by utilizing direct hypervisor socket channels.
* **Transport**: `virtio-vsock` for ultra-low latency, zero-configuration host-to-guest transport.
* **Addressing & Topology**:
  * Android Host listens on Context ID (`CID`) `2`.
  * The provisioned Guest VM is assigned a unique guest `CID`.
* **Guest Daemon**: A lightweight listener inside the Linux guest awaits command payloads, executes them natively with POSIX PTY/process semantics, and streams `stdout`, `stderr`, and exit statuses back across vsock.

### Phase 5: Stream Multiplexing & Terminal Output
Unify runtime streams into a coherent terminal shell experience.
* **Multiplexing Engine**: Rust async channel multiplexing via Tokio event loops.

```rust
// Conceptual architecture for the Broker multiplexer
tokio::select! {
    wasm_output = wasm_runtime.read_stdout() => {
        send_to_ui_terminal(wasm_output);
    }
    vm_output = vsock_stream.read_data() => {
        send_to_ui_terminal(vm_output);
    }
    user_input = ui_terminal.read_input() => {
        route_command_to_engine(user_input);
    }
}
```

---

## 3. Guiding Principles
* **Zero Virtualization Tax on Host**: Run everything that can run in WASM on the host with native speed.
* **Full Linux Compatibility in Guest**: Seamless fallback to hardware-isolated AVF microVM for full ELF/glibc binaries.
* **Unified Single-Shell Experience**: Transparent multiplexing so the user interacts with one seamless terminal interface.

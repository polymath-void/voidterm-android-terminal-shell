use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

pub mod local_pty;
pub mod storage;
pub mod vm_bridge;
pub mod wasm_engine;

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

    let _ = TX_INPUT.set(tx_input);

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

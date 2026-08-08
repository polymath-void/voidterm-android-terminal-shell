use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

pub mod vm_bridge;
pub mod wasm_engine;

// Default AVF Guest VM Context ID and Port
const DEFAULT_VM_CID: u32 = 3;
const VM_VSOCK_PORT: u32 = 8000;

/// The unified message protocol for IPC routing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcMessage {
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
    mut env: JNIEnv,
    obj: JObject, // Capture the calling Kotlin object instance
) {
    // 1. Capture the JavaVM and create a Global Reference to the Kotlin object
    let jvm = env.get_java_vm().expect("Failed to get JavaVM");
    let global_obj = env.new_global_ref(obj).expect("Failed to create GlobalRef");
    
    JVM.set(jvm).expect("JVM already initialized");
    CALLBACK_OBJ.set(global_obj).expect("Callback already initialized");

    let rt = Runtime::new().expect("Failed to build Tokio runtime");
    let (tx_input, mut rx_input) = mpsc::channel::<IpcMessage>(1024);
    let (tx_output, mut rx_output) = mpsc::channel::<IpcMessage>(1024);

    TX_INPUT.set(tx_input).expect("Broker daemon already initialized!");

    rt.spawn(async move {
        println!("🚀 [JNI] Hybrid Term Broker daemon running...");
        
        // Spawn a parallel task to handle EGRESS (Rust -> Kotlin UI)
        tokio::spawn(async move {
            while let Some(msg) = rx_output.recv().await {
                if let IpcMessage::TerminalOutput(text) = msg {
                    send_to_kotlin(text);
                }
            }
        });

        // The INGRESS loop (Kotlin UI -> Rust)
        while let Some(msg) = rx_input.recv().await {
            match msg {
                IpcMessage::ExecuteVm { command } => {
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

    RUNTIME.set(rt).expect("Tokio runtime already initialized!");
}

#[no_mangle]
pub extern "system" fn Java_com_hybridengine_terminal_Broker_sendCommand(
    mut env: JNIEnv,
    _class: JClass,
    command: JString,
) {
    // 1. Safely extract the String from the Java/Kotlin environment
    let cmd_raw: String = env.get_string(&command).expect("Invalid JString").into();
    println!("📥 [JNI] Received from UI: {}", cmd_raw);
    
    // 2. Retrieve our global Tokio runtime and our input channel
    if let (Some(rt), Some(tx)) = (RUNTIME.get(), TX_INPUT.get()) {
        let tx_clone = tx.clone();
        
        // 3. Push the command into the asynchronous broker loop
        rt.spawn(async move {
            // For routing logic, you could implement a parser here to determine
            // if the command is a WASM script or a Linux binary.
            // For now, we wrap it in our ExecuteVm payload.
            let msg = IpcMessage::ExecuteVm { command: cmd_raw };
            
            if let Err(e) = tx_clone.send(msg).await {
                eprintln!("❌ [JNI] Failed to send command to broker: {}", e);
            }
        });
    } else {
        eprintln!("⚠️ [JNI] Broker not initialized. Call startDaemon first.");
    }
}

/// Helper function to push strings back to the Android UI
fn send_to_kotlin(text: String) {
    if let (Some(jvm), Some(callback)) = (JVM.get(), CALLBACK_OBJ.get()) {
        // Attach the background Tokio thread to the JVM
        let mut env = jvm
            .attach_current_thread()
            .expect("Failed to attach current thread to JVM");
            
        // Convert the Rust string to a Java string
        let j_str = env.new_string(text).expect("Failed to create JString");
        
        // Call the Kotlin method: fun onTerminalOutput(output: String)
        let _ = env.call_method(
            callback.as_obj(),
            "onTerminalOutput",
            "(Ljava/lang/String;)V",
            &[JValue::from(&j_str)],
        );
    }
}

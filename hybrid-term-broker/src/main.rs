use hybrid_term_broker::acp::AgentControlPlane;
use hybrid_term_broker::local_pty::LocalPty;
use hybrid_term_broker::vm_bridge::VmBridge;
use hybrid_term_broker::wasm_engine::WasmEngine;
use hybrid_term_broker::IpcMessage;
use std::sync::Arc;
use tokio::sync::mpsc;

// Default AVF Guest VM Context ID and Port
const DEFAULT_VM_CID: u32 = 3;
const VM_VSOCK_PORT: u32 = 8000;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    println!("🚀 VoidTerm Hybrid IPC Broker initialized.");

    let (tx_output, mut rx_output) = mpsc::channel::<IpcMessage>(1024);
    let (tx_input, mut rx_input) = mpsc::channel::<IpcMessage>(1024);

    let socket_path = std::env::var("VOIDTERM_AGENT_SOCKET").unwrap_or_else(|_| {
        let tmp = std::env::var("TMPDIR").unwrap_or_else(|_| {
            if std::path::Path::new("/data/data/com.hybridengine.terminal/cache").exists() {
                "/data/data/com.hybridengine.terminal/cache".to_string()
            } else if std::path::Path::new("/data/data/com.hybridengine.terminal/files").exists() {
                "/data/data/com.hybridengine.terminal/files".to_string()
            } else if std::path::Path::new("/data/data/com.termux/files/usr/tmp").exists() {
                "/data/data/com.termux/files/usr/tmp".to_string()
            } else {
                "/tmp".to_string()
            }
        });
        format!("{}/voidterm_agent.sock", tmp)
    });
    let acp = AgentControlPlane::new(tx_input.clone());
    acp.start_socket_server(&socket_path).await?;

    // Initialize local PTY for interactive shell sessions
    let local_pty: Option<Arc<LocalPty>> = match LocalPty::start(tx_output.clone()) {
        Ok(pty) => {
            println!("✅ [Local PTY] Interactive shell session spawned.");
            Some(Arc::new(pty))
        }
        Err(e) => {
            println!("⚠️ [Local PTY] Could not spawn local shell: {}", e);
            None
        }
    };

    if VmBridge::is_vsock_available() {
        println!("📡 [AVF] virtio-vsock available: Listening on CID {} port {}...", DEFAULT_VM_CID, VM_VSOCK_PORT);
    } else {
        println!("ℹ️ [AVF] virtio-vsock device inactive. Operating in NDK/PTY host execution mode.");
    }

    loop {
        tokio::select! {
            // 1. Ingress UI Commands Routing
            Some(input_msg) = rx_input.recv() => {
                match input_msg {
                    IpcMessage::ExecuteLocal { command } => {
                        if VmBridge::is_vsock_available() {
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
                        } else if let Some(ref pty) = local_pty {
                            if let Err(e) = pty.write_command(&command) {
                                let err_msg = format!("❌ [Local PTY Error]: {}\n", e);
                                let _ = tx_output.send(IpcMessage::TerminalOutput(err_msg)).await;
                            }
                        } else {
                            let err_msg = "⚠️ [Broker] Neither AVF MicroVM nor Local PTY is available.\n".to_string();
                            let _ = tx_output.send(IpcMessage::TerminalOutput(err_msg)).await;
                        }
                    }
                    IpcMessage::ExecuteVm { command } => {
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

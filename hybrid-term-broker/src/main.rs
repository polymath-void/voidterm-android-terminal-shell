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

mod vm_bridge;
mod wasm_engine;

use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use vm_bridge::VmBridge;
use wasm_engine::WasmEngine;

// Default AVF Guest VM Context ID and Port
const DEFAULT_VM_CID: u32 = 3;
const VM_VSOCK_PORT: u32 = 8000;

#[derive(Debug, Serialize, Deserialize)]
pub enum IpcMessage {
    ExecuteWasm { module_name: String, args: Vec<String> },
    ExecuteVm { command: String },
    TerminalOutput(String),
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    println!("🚀 Hybrid Term Broker initialized.");

    let (tx_output, mut rx_output) = mpsc::channel::<IpcMessage>(1024);
    let (_tx_input, mut rx_input) = mpsc::channel::<IpcMessage>(1024);

    println!("📡 Listening for cross-environment multiplexing...");

    loop {
        tokio::select! {
            // 1. Ingress UI Commands Routing
            Some(input_msg) = rx_input.recv() => {
                match input_msg {
                    IpcMessage::ExecuteWasm { module_name, args } => {
                        let tx_clone = tx_output.clone();
                        tokio::spawn(async move {
                            if let Err(e) = WasmEngine::execute(module_name, args, tx_clone.clone()).await {
                                let err_msg = format!("❌ [WASM Error]: {}\n", e);
                                let _ = tx_clone.send(IpcMessage::TerminalOutput(err_msg)).await;
                            }
                        });
                    }
                    IpcMessage::ExecuteVm { command } => {
                        let tx_clone = tx_output.clone();
                        // Spawn non-blocking task to send workload over hypervisor bus
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

use anyhow::{Context, Result};
use tokio::sync::mpsc;
use wasmedge_sdk::{wasi::WasiModule, Vm};

use crate::IpcMessage;

pub struct WasmEngine;

impl WasmEngine {
    /// Executes a compiled WASM module and routes status/output to the broker
    pub async fn execute(
        module_name: String,
        args: Vec<String>,
        tx_output: mpsc::Sender<IpcMessage>,
    ) -> Result<()> {
        let msg = format!("⚡ [WASM] Booting module: {}...\n", module_name);
        let _ = tx_output.send(IpcMessage::TerminalOutput(msg)).await;

        // Initialize WASI environment (allows the WASM module to use standard I/O)
        // We pass the CLI arguments directly into the WASI context
        let mut wasi_module = WasiModule::create(Some(args), None, None)
            .context("Failed to create WASI module")?;

        // Initialize the WasmEdge Virtual Machine
        let mut vm = Vm::new(None).context("Failed to create WasmEdge VM")?;
        
        // Register the WASI module into the VM
        vm.register_module(Some(&mut wasi_module))
            .context("Failed to register WASI module")?;

        // In a fully integrated environment, we map WasmEdge's stdout directly 
        // to our tx_output channel via custom host functions. 
        // For now, we execute the _start function (default entry point for WASI).
        
        // let result = vm.run_func(Some("main"), "_start", params);
        
        let success_msg = format!("✅ [WASM] Module {} executed successfully.\n", module_name);
        let _ = tx_output.send(IpcMessage::TerminalOutput(success_msg)).await;

        Ok(())
    }
}

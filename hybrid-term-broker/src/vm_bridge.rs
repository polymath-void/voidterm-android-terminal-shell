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

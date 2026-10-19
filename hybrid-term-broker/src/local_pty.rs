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

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{mpsc, Mutex};

use crate::local_pty::LocalPty;
use crate::IpcMessage;

/// Supported Execution Tiers
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum ExecutionTier {
    Local,
    Wasm,
    Vm,
}

/// Incoming Requests from AI Agents over the Control Socket
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum AgentRequest {
    CreateSession {
        session_id: String,
        #[serde(default = "default_tier")]
        tier: ExecutionTier,
        #[serde(default)]
        workdir: String,
        #[serde(default)]
        shell: String,
        #[serde(default)]
        rows: Option<u16>,
        #[serde(default)]
        cols: Option<u16>,
    },
    Exec {
        session_id: String,
        command: String,
    },
    Input {
        session_id: String,
        data: String,
    },
    ReadSnapshot {
        session_id: String,
        #[serde(default = "default_snapshot_lines")]
        lines: usize,
    },
    HudTelemetry {
        agent_name: String,
        status: String,
        thought: String,
        #[serde(default)]
        active_file: String,
        #[serde(default)]
        progress: Option<f32>,
    },
    ListSessions,
    CloseSession {
        session_id: String,
    },
    Ping,
}

fn default_tier() -> ExecutionTier {
    ExecutionTier::Local
}

fn default_snapshot_lines() -> usize {
    50
}

/// Outgoing Responses back to Connected Agents
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum AgentResponse {
    Success {
        message: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        session_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        content: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        sessions: Option<Vec<SessionInfo>>,
    },
    Event {
        event_type: String,
        session_id: String,
        data: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: String,
    pub tier: ExecutionTier,
    pub workdir: String,
}

pub struct ActiveSession {
    pub session_id: String,
    pub tier: ExecutionTier,
    pub workdir: String,
    pub pty: Option<Arc<LocalPty>>,
}

/// Core Agent Control Plane Manager
#[derive(Clone)]
pub struct AgentControlPlane {
    sessions: Arc<Mutex<HashMap<String, Arc<ActiveSession>>>>,
    tx_ui: mpsc::Sender<IpcMessage>,
}

impl AgentControlPlane {
    pub fn new(tx_ui: mpsc::Sender<IpcMessage>) -> Self {
        Self {
            sessions: Arc::new(Mutex::new(HashMap::new())),
            tx_ui,
        }
    }

    /// Starts the asynchronous Unix Domain Socket listener for Agent IPC
    pub async fn start_socket_server(&self, socket_path: &str) -> Result<()> {
        let path = Path::new(socket_path);
        if path.exists() {
            let _ = std::fs::remove_file(path);
        }

        if let Some(parent) = path.parent() {
            if !parent.exists() {
                let _ = std::fs::create_dir_all(parent);
            }
        }

        let listener = UnixListener::bind(socket_path)
            .with_context(|| format!("Failed to bind Agent Control Socket at {}", socket_path))?;

        println!("🤖 [ACP] Agent Control Plane active at {}", socket_path);

        let acp_clone = self.clone();
        tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => {
                        let acp = acp_clone.clone();
                        tokio::spawn(async move {
                            if let Err(e) = acp.handle_client(stream).await {
                                eprintln!("⚠️ [ACP] Client connection error: {}", e);
                            }
                        });
                    }
                    Err(e) => {
                        eprintln!("❌ [ACP] Socket accept error: {}", e);
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    async fn handle_client(&self, stream: UnixStream) -> Result<()> {
        let (reader, mut writer) = stream.into_split();
        let mut buf_reader = BufReader::new(reader);
        let mut line = String::new();

        while buf_reader.read_line(&mut line).await? > 0 {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                line.clear();
                continue;
            }

            let response = match serde_json::from_str::<AgentRequest>(trimmed) {
                Ok(req) => self.process_request(req).await,
                Err(e) => AgentResponse::Error {
                    message: format!("Malformed JSON request: {}", e),
                },
            };

            let mut out_bytes = serde_json::to_vec(&response)?;
            out_bytes.push(b'\n');
            writer.write_all(&out_bytes).await?;
            writer.flush().await?;

            line.clear();
        }

        Ok(())
    }

    async fn process_request(&self, req: AgentRequest) -> AgentResponse {
        match req {
            AgentRequest::Ping => AgentResponse::Success {
                message: "pong: Agent Control Plane is responsive".to_string(),
                session_id: None,
                content: None,
                sessions: None,
            },

            AgentRequest::CreateSession {
                session_id,
                tier,
                workdir,
                shell,
                rows,
                cols,
            } => {
                let mut map = self.sessions.lock().await;
                if map.contains_key(&session_id) {
                    return AgentResponse::Error {
                        message: format!("Session '{}' already exists", session_id),
                    };
                }

                match tier {
                    ExecutionTier::Local => {
                        let ws = match (rows, cols) {
                            (Some(r), Some(c)) => Some((r, c)),
                            _ => None,
                        };

                        let tx_clone = self.tx_ui.clone();
                        match LocalPty::start_custom(&shell, &workdir, ws, tx_clone) {
                            Ok(pty) => {
                                let session = Arc::new(ActiveSession {
                                    session_id: session_id.clone(),
                                    tier,
                                    workdir,
                                    pty: Some(Arc::new(pty)),
                                });
                                map.insert(session_id.clone(), session);
                                AgentResponse::Success {
                                    message: format!("Local PTY session '{}' initialized", session_id),
                                    session_id: Some(session_id),
                                    content: None,
                                    sessions: None,
                                }
                            }
                            Err(e) => AgentResponse::Error {
                                message: format!("Failed to spawn local PTY: {}", e),
                            },
                        }
                    }
                    ExecutionTier::Wasm | ExecutionTier::Vm => {
                        let session = Arc::new(ActiveSession {
                            session_id: session_id.clone(),
                            tier: tier.clone(),
                            workdir,
                            pty: None,
                        });
                        map.insert(session_id.clone(), session);
                        AgentResponse::Success {
                            message: format!("{:?} session '{}' initialized", tier, session_id),
                            session_id: Some(session_id),
                            content: None,
                            sessions: None,
                        }
                    }
                }
            }

            AgentRequest::Exec { session_id, command } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    match session.tier {
                        ExecutionTier::Local => {
                            if let Some(pty) = &session.pty {
                                if let Err(e) = pty.write_command(&command) {
                                    return AgentResponse::Error {
                                        message: format!("Failed to write command to PTY: {}", e),
                                    };
                                }
                                AgentResponse::Success {
                                    message: format!("Dispatched command to '{}'", session_id),
                                    session_id: Some(session_id),
                                    content: None,
                                    sessions: None,
                                }
                            } else {
                                AgentResponse::Error {
                                    message: "Session PTY handle missing".to_string(),
                                }
                            }
                        }
                        ExecutionTier::Wasm | ExecutionTier::Vm => {
                            let _ = self.tx_ui.send(IpcMessage::ExecuteLocal { command: command.clone() }).await;
                            AgentResponse::Success {
                                message: format!("Dispatched command to {:?} engine", session.tier),
                                session_id: Some(session_id),
                                content: None,
                                sessions: None,
                            }
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::Input { session_id, data } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    if let Some(pty) = &session.pty {
                        if let Err(e) = pty.write_raw(data.as_bytes()) {
                            return AgentResponse::Error {
                                message: format!("Failed to write input to PTY: {}", e),
                            };
                        }
                        AgentResponse::Success {
                            message: "Input delivered".to_string(),
                            session_id: Some(session_id),
                            content: None,
                            sessions: None,
                        }
                    } else {
                        AgentResponse::Error {
                            message: "Session is not a PTY session".to_string(),
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::ReadSnapshot { session_id, lines } => {
                let map = self.sessions.lock().await;
                if let Some(session) = map.get(&session_id) {
                    if let Some(pty) = &session.pty {
                        let snapshot = pty.get_snapshot(lines);
                        AgentResponse::Success {
                            message: "Snapshot captured".to_string(),
                            session_id: Some(session_id),
                            content: Some(snapshot),
                            sessions: None,
                        }
                    } else {
                        AgentResponse::Error {
                            message: "Snapshot only supported on PTY sessions".to_string(),
                        }
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }

            AgentRequest::HudTelemetry {
                agent_name,
                status,
                thought,
                active_file,
                progress,
            } => {
                let telemetry_json = serde_json::json!({
                    "type": "agent_hud",
                    "agent": agent_name,
                    "status": status,
                    "thought": thought,
                    "active_file": active_file,
                    "progress": progress
                });
                let payload = format!("__HUD_TELEMETRY__:{}", telemetry_json);
                let _ = self.tx_ui.send(IpcMessage::TerminalOutput(payload)).await;

                AgentResponse::Success {
                    message: "HUD telemetry broadcasted to UI".to_string(),
                    session_id: None,
                    content: None,
                    sessions: None,
                }
            }

            AgentRequest::ListSessions => {
                let map = self.sessions.lock().await;
                let list: Vec<SessionInfo> = map
                    .values()
                    .map(|s| SessionInfo {
                        session_id: s.session_id.clone(),
                        tier: s.tier.clone(),
                        workdir: s.workdir.clone(),
                    })
                    .collect();

                AgentResponse::Success {
                    message: format!("Found {} active sessions", list.len()),
                    session_id: None,
                    content: None,
                    sessions: Some(list),
                }
            }

            AgentRequest::CloseSession { session_id } => {
                let mut map = self.sessions.lock().await;
                if map.remove(&session_id).is_some() {
                    AgentResponse::Success {
                        message: format!("Session '{}' closed", session_id),
                        session_id: Some(session_id),
                        content: None,
                        sessions: None,
                    }
                } else {
                    AgentResponse::Error {
                        message: format!("Session '{}' not found", session_id),
                    }
                }
            }
        }
    }
}

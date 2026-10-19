#!/usr/bin/env python3
"""
Test client for VoidTerm Agent Control Plane (ACP)
Connects to the Rust broker via Unix domain socket and verifies the agent lifecycle.
"""

import os
import sys
import time
import json
import socket
import subprocess

TMPDIR = os.environ.get("TMPDIR", "/data/data/com.termux/files/usr/tmp")
SOCKET_PATH = os.path.join(TMPDIR, "voidterm_agent.sock")
BROKER_BIN = "/data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/target/debug/hybrid-term-broker"

def run_test():
    print("[*] Starting VoidTerm Broker in background...")
    if os.path.exists(SOCKET_PATH):
        try:
            os.remove(SOCKET_PATH)
        except OSError:
            pass

    proc = subprocess.Popen([BROKER_BIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    
    # Wait for socket to become available
    connected = False
    for _ in range(30):
        time.sleep(0.1)
        if os.path.exists(SOCKET_PATH):
            connected = True
            break
            
    if not connected:
        proc.kill()
        print("[-] Error: Broker socket was not created.")
        sys.exit(1)

    print(f"[+] Broker online. Connecting to ACP socket: {SOCKET_PATH}")
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.connect(SOCKET_PATH)

    def send_and_recv(req):
        payload = json.dumps(req) + "\n"
        client.sendall(payload.encode("utf-8"))
        res_raw = client.recv(4096).decode("utf-8")
        return json.loads(res_raw.strip())

    # 1. Ping
    print("\n--- Test 1: Ping ---")
    res = send_and_recv({"action": "ping"})
    print("Response:", res)
    assert res["status"] == "success"

    # 2. Create Session
    print("\n--- Test 2: Create Session ---")
    res = send_and_recv({
        "action": "create_session",
        "session_id": "agent_alpha",
        "tier": "local",
        "workdir": "/data/data/com.termux/files/home/Projects"
    })
    print("Response:", res)
    assert res["status"] == "success"

    # 3. Exec Command
    print("\n--- Test 3: Exec Command in PTY ---")
    res = send_and_recv({
        "action": "exec",
        "session_id": "agent_alpha",
        "command": "uname -a && echo '=== ACP AGENT WORKSPACE OPERATING LIVE ==='"
    })
    print("Response:", res)
    assert res["status"] == "success"

    # Wait for command execution in PTY
    time.sleep(0.8)

    # 4. Read Snapshot
    print("\n--- Test 4: Read PTY Snapshot ---")
    res = send_and_recv({
        "action": "read_snapshot",
        "session_id": "agent_alpha",
        "lines": 10
    })
    print("Snapshot Content:\n", res.get("content"))
    assert res["status"] == "success"
    assert "ACP AGENT WORKSPACE OPERATING LIVE" in res.get("content", "")

    # 5. HUD Telemetry
    print("\n--- Test 5: Broadcast HUD Telemetry ---")
    res = send_and_recv({
        "action": "hud_telemetry",
        "agent_name": "Antigravity",
        "status": "operating",
        "thought": "Testing Agent Control Plane direct IPC socket",
        "active_file": "hybrid-term-broker/src/acp.rs",
        "progress": 0.85
    })
    print("Response:", res)
    assert res["status"] == "success"

    # 6. List Sessions
    print("\n--- Test 6: List Sessions ---")
    res = send_and_recv({"action": "list_sessions"})
    print("Response:", res)
    assert res["status"] == "success"
    assert len(res.get("sessions", [])) == 1

    # 7. Close Session
    print("\n--- Test 7: Close Session ---")
    res = send_and_recv({
        "action": "close_session",
        "session_id": "agent_alpha"
    })
    print("Response:", res)
    assert res["status"] == "success"

    client.close()
    proc.terminate()
    print("\n[✔] ALL 7 AGENT CONTROL PLANE TESTS PASSED PERFECTLY!")

if __name__ == "__main__":
    run_test()

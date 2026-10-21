"""
VoidTerm Polyglot Extension SDK (Python)
Provides zero-dependency clients for the Embedded OS API Gateway and NodeOS State Graph.
"""

import os
import sys
import json
import socket
import urllib.request
import sqlite3
import time

PREFIX = os.environ.get("PREFIX", "/data/user/0/com.hybridengine.terminal/files/usr")
SOCK_PATH = os.path.join(PREFIX, "var", "run", "void_api.sock")
HTTP_URL = "http://127.0.0.1:45671"

class VoidAPI:
    """Client for the VoidTerm Embedded OS API Gateway."""

    def __init__(self, timeout=3.0):
        self.timeout = timeout

    def call(self, method, params=None):
        payload = json.dumps({
            "method": method,
            "params": params or {},
            "id": int(time.time() * 1000)
        })

        # Try Unix Domain Socket first
        if os.path.exists(SOCK_PATH):
            try:
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.settimeout(self.timeout)
                s.connect(SOCK_PATH)
                s.sendall((payload + "\n").encode("utf-8"))
                resp_data = s.recv(4096).decode("utf-8").strip()
                s.close()
                if resp_data:
                    res = json.loads(resp_data)
                    return res.get("result", res)
            except Exception:
                pass

        # Fallback to Localhost HTTP
        try:
            req = urllib.request.Request(
                HTTP_URL,
                data=payload.encode("utf-8"),
                headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                return res.get("result", res)
        except Exception as e:
            return {"error": f"API Gateway connection error: {e}"}

    def toast(self, message, long=False):
        return self.call("toast.show", {"text": str(message), "long": bool(long)})

    def clipboard_get(self):
        res = self.call("clipboard.get")
        return res.get("text", "")

    def clipboard_set(self, text):
        return self.call("clipboard.set", {"text": str(text)})

    def notify(self, title, text, id=1002):
        return self.call("notification.post", {"title": str(title), "text": str(text), "id": int(id)})

    def vibrate(self, duration_ms=200):
        return self.call("vibrator.vibrate", {"duration": int(duration_ms)})

    def battery(self):
        return self.call("battery.status")

    def wakelock_acquire(self, tag="VoidTerm:Lock", timeout=300):
        return self.call("power.wakelock_acquire", {"tag": str(tag), "timeout": int(timeout)})

    def wakelock_release(self, tag="VoidTerm:Lock"):
        return self.call("power.wakelock_release", {"tag": str(tag)})

    def kill(self, ext_id):
        return self.call("process.kill", {"ext_id": str(ext_id)})

    def kill_all(self):
        return self.call("process.kill_all")

class NodeOS:
    """Client for querying and updating agy_nodeos.db telemetry and state."""

    @staticmethod
    def get_db_path():
        workspace_db = "/data/data/com.termux/files/home/Projects/agy_nodeos.db"
        if os.path.exists(workspace_db):
            return workspace_db
        internal_db = os.path.join(PREFIX, "agy_nodeos.db")
        return internal_db

    @classmethod
    def get_connection(cls):
        db_path = cls.get_db_path()
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        conn = sqlite3.connect(db_path, timeout=5.0)
        conn.execute("PRAGMA journal_mode=WAL;")
        return conn

    @classmethod
    def register(cls, ext_id, name, runtime="python", permissions=None, metadata=None):
        conn = cls.get_connection()
        try:
            with conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS extension_runtime (
                        ext_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        runtime TEXT DEFAULT 'python',
                        active_pid INTEGER DEFAULT 0,
                        status TEXT DEFAULT 'idle',
                        last_started INTEGER DEFAULT 0,
                        total_runs INTEGER DEFAULT 0,
                        total_duration_sec REAL DEFAULT 0.0,
                        permissions TEXT DEFAULT '[]',
                        metadata TEXT DEFAULT '{}',
                        updated_at INTEGER DEFAULT 0
                    );
                """)
                now = int(time.time() * 1000)
                conn.execute("""
                    INSERT INTO extension_runtime (ext_id, name, runtime, permissions, metadata, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(ext_id) DO UPDATE SET
                        name = excluded.name,
                        runtime = excluded.runtime,
                        permissions = excluded.permissions,
                        metadata = excluded.metadata,
                        updated_at = excluded.updated_at;
                """, (
                    ext_id,
                    name,
                    runtime,
                    json.dumps(permissions or []),
                    json.dumps(metadata or {}),
                    now
                ))
                return True
        finally:
            conn.close()

    @classmethod
    def set_running(cls, ext_id, pid):
        conn = cls.get_connection()
        try:
            with conn:
                now = int(time.time() * 1000)
                conn.execute("""
                    UPDATE extension_runtime
                    SET active_pid = ?, status = 'running', last_started = ?, total_runs = total_runs + 1, updated_at = ?
                    WHERE ext_id = ?;
                """, (pid, now, now, ext_id))
        finally:
            conn.close()

    @classmethod
    def set_idle(cls, ext_id, duration_sec, status="idle"):
        conn = cls.get_connection()
        try:
            with conn:
                now = int(time.time() * 1000)
                conn.execute("""
                    UPDATE extension_runtime
                    SET active_pid = 0, status = ?, total_duration_sec = total_duration_sec + ?, updated_at = ?
                    WHERE ext_id = ?;
                """, (status, duration_sec, now, ext_id))
        finally:
            conn.close()

    @classmethod
    def is_running(cls, ext_id):
        conn = cls.get_connection()
        try:
            cur = conn.cursor()
            cur.execute("SELECT active_pid FROM extension_runtime WHERE ext_id = ?;", (ext_id,))
            row = cur.fetchone()
            if not row or not row[0]:
                return False, 0
            pid = row[0]
            if os.path.exists(f"/proc/{pid}"):
                return True, pid
            else:
                # Auto-heal zombie PID
                cls.set_idle(ext_id, 0.0, "idle")
                return False, 0
        finally:
            conn.close()

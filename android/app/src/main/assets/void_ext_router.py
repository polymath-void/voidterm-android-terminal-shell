#!/data/user/0/com.hybridengine.terminal/files/usr/bin/python3
"""
VoidTerm Extension CLI Router (void-ext / ext)
Universal polyglot extension runner, manager, lifecycle gatekeeper, and safety breaker.
"""

import os
import sys
import json
import time
import shutil
import sqlite3
import subprocess

PREFIX = os.environ.get("PREFIX", "/data/user/0/com.hybridengine.terminal/files/usr")
EXT_BASE = os.path.join(PREFIX, "etc", "extensions")

# ANSI Colors
CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_MAGENTA = "\033[35m"
CLR_GRAY = "\033[90m"

def get_db_path():
    workspace_db = "/data/data/com.termux/files/home/Projects/agy_nodeos.db"
    if os.path.exists(workspace_db):
        return workspace_db
    return os.path.join(PREFIX, "agy_nodeos.db")

def get_db():
    p = get_db_path()
    os.makedirs(os.path.dirname(p), exist_ok=True)
    conn = sqlite3.connect(p, timeout=5.0)
    conn.execute("PRAGMA journal_mode=WAL;")
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
    return conn

def is_pid_alive(pid):
    if pid <= 0:
        return False
    return os.path.exists(f"/proc/{pid}")

def cmd_list():
    os.makedirs(EXT_BASE, exist_ok=True)
    dirs = [d for d in os.listdir(EXT_BASE) if os.path.isdir(os.path.join(EXT_BASE, d))]

    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT ext_id, active_pid, status, total_runs FROM extension_runtime;")
    db_states = {row[0]: {"pid": row[1], "status": row[2], "runs": row[3]} for row in cur.fetchall()}
    conn.close()

    print(f"{CLR_MAGENTA}{CLR_BOLD}🧩 VoidTerm Installed Extensions ({len(dirs)}){CLR_RESET}")
    print(f"{CLR_GRAY}───────────────────────────────────────────────────────────────────{CLR_RESET}")
    print(f"{'ID':<12} {'NAME':<20} {'RUNTIME':<10} {'STATUS':<15} {'RUNS':<6}")
    print(f"{CLR_GRAY}───────────────────────────────────────────────────────────────────{CLR_RESET}")

    for d in sorted(dirs):
        meta_file = os.path.join(EXT_BASE, d, "metadata.json")
        name = d
        runtime = "python"
        version = "1.0.0"
        if os.path.exists(meta_file):
            try:
                with open(meta_file, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                    name = meta.get("name", d)
                    runtime = meta.get("runtime", "python")
                    version = meta.get("version", "1.0.0")
            except Exception:
                pass

        st = db_states.get(d, {"pid": 0, "status": "idle", "runs": 0})
        pid = st["pid"]
        status_str = f"{CLR_GRAY}IDLE{CLR_RESET}"
        if pid > 0 and is_pid_alive(pid):
            status_str = f"{CLR_GREEN}RUNNING ({pid}){CLR_RESET}"
        elif pid > 0:
            # Zombie clean
            status_str = f"{CLR_GRAY}IDLE{CLR_RESET}"

        lang_badge = f"{CLR_GREEN}Python{CLR_RESET}" if runtime == "python" else f"{CLR_YELLOW}Node.js{CLR_RESET}"
        print(f"{d:<12} {name[:19]:<20} {lang_badge:<19} {status_str:<24} {st['runs']:<6}")

    print(f"{CLR_GRAY}───────────────────────────────────────────────────────────────────{CLR_RESET}")

def cmd_create(name, lang="python"):
    ext_dir = os.path.join(EXT_BASE, name)
    if os.path.exists(ext_dir):
        print(f"{CLR_RED}Error: Extension '{name}' already exists at {ext_dir}{CLR_RESET}")
        return 1

    scripts_dir = os.path.join(ext_dir, "scripts")
    env_dir = os.path.join(ext_dir, "environments")
    os.makedirs(scripts_dir, exist_ok=True)
    os.makedirs(env_dir, exist_ok=True)

    runtime_type = "javascript" if lang in ("js", "javascript", "node") else "python"
    entry_file = "scripts/main.js" if runtime_type == "javascript" else "scripts/main.py"

    metadata = {
        "id": name,
        "name": name.replace("_", " ").title(),
        "version": "1.0.0",
        "author": os.environ.get("USER", "Developer"),
        "description": f"Custom {runtime_type.title()} Extension for VoidTerm",
        "runtime": runtime_type,
        "entry": entry_file,
        "singleton": True,
        "permissions": ["terminal", "storage"],
        "grant_type": "always"
    }

    with open(os.path.join(ext_dir, "metadata.json"), "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    if runtime_type == "python":
        main_code = f"""#!/data/user/0/com.hybridengine.terminal/files/usr/bin/python3
import sys
from voidterm import VoidAPI

api = VoidAPI()
print("Hello from VoidTerm Python Extension: {name}")
api.toast("Extension {name} active!")
"""
        with open(os.path.join(ext_dir, entry_file), "w", encoding="utf-8") as f:
            f.write(main_code)
        os.chmod(os.path.join(ext_dir, entry_file), 0o755)
    else:
        main_code = f"""const {{ VoidAPI }} = require('voidterm');
const api = new VoidAPI();

console.log("Hello from VoidTerm JavaScript Extension: {name}");
api.toast("Extension {name} active!");
"""
        with open(os.path.join(ext_dir, entry_file), "w", encoding="utf-8") as f:
            f.write(main_code)

    print(f"{CLR_GREEN}✔ Created {runtime_type.title()} extension template at: {ext_dir}{CLR_RESET}")
    print(f"To run: void ext run {name}")
    return 0

def cmd_run(ext_id, extra_args):
    ext_dir = os.path.join(EXT_BASE, ext_id)
    meta_file = os.path.join(ext_dir, "metadata.json")
    if not os.path.exists(meta_file):
        print(f"{CLR_RED}Error: Extension '{ext_id}' not found in {EXT_BASE}{CLR_RESET}")
        return 1

    with open(meta_file, "r", encoding="utf-8") as f:
        meta = json.load(f)

    singleton = meta.get("singleton", True)
    runtime = meta.get("runtime", "python").lower()
    entry = meta.get("entry", "scripts/main.py")
    entry_path = os.path.join(ext_dir, entry)

    if not os.path.exists(entry_path):
        print(f"{CLR_RED}Error: Entry point {entry_path} not found{CLR_RESET}")
        return 1

    # Concurrency Gate
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT active_pid FROM extension_runtime WHERE ext_id = ?;", (ext_id,))
    row = cur.fetchone()
    if row and row[0] > 0 and singleton:
        old_pid = row[0]
        if is_pid_alive(old_pid):
            conn.close()
            print(f"{CLR_RED}{CLR_BOLD}[VoidExt] Blocked: Instance '{ext_id}' is already running with PID {old_pid}.{CLR_RESET}")
            print(f"{CLR_YELLOW}Use 'void ext kill {ext_id}' or tap [KILL] in Extensions Hub to stop it.{CLR_RESET}")
            return 126

    # Resolve interpreter
    if runtime in ("js", "javascript", "node") or entry.endswith(".js"):
        runner = shutil.which("node") or os.path.join(PREFIX, "bin", "node")
    else:
        runner = shutil.which("python3") or os.path.join(PREFIX, "bin", "python3")

    cmd = [runner, entry_path] + extra_args

    # Spawn process
    start_time = time.time()
    proc = subprocess.Popen(cmd)
    new_pid = proc.pid

    # Record PID in NodeOS DB
    now_ms = int(start_time * 1000)
    cur.execute("""
        INSERT INTO extension_runtime (ext_id, name, runtime, active_pid, status, last_started, total_runs, updated_at)
        VALUES (?, ?, ?, ?, 'running', ?, 1, ?)
        ON CONFLICT(ext_id) DO UPDATE SET
            active_pid = excluded.active_pid,
            status = 'running',
            last_started = excluded.last_started,
            total_runs = total_runs + 1,
            updated_at = excluded.updated_at;
    """, (ext_id, meta.get("name", ext_id), runtime, new_pid, now_ms, now_ms))
    conn.commit()
    conn.close()

    try:
        ret = proc.wait()
    except KeyboardInterrupt:
        proc.terminate()
        ret = 130

    duration = time.time() - start_time

    # Cleanup DB state
    conn = get_db()
    with conn:
        conn.execute("""
            UPDATE extension_runtime
            SET active_pid = 0, status = 'idle', total_duration_sec = total_duration_sec + ?, updated_at = ?
            WHERE ext_id = ?;
        """, (duration, int(time.time() * 1000), ext_id))
    conn.close()

    return ret

def cmd_kill(ext_id):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT active_pid FROM extension_runtime WHERE ext_id = ?;", (ext_id,))
    row = cur.fetchone()
    if not row or row[0] <= 0:
        print(f"{CLR_GRAY}Extension '{ext_id}' is not currently running.{CLR_RESET}")
        conn.close()
        return 0

    pid = row[0]
    if is_pid_alive(pid):
        try:
            os.system(f"kill -9 -{pid} 2>/dev/null")
            os.system(f"kill -9 {pid} 2>/dev/null")
            print(f"{CLR_GREEN}✔ Sent SIGKILL to extension '{ext_id}' (PID {pid}){CLR_RESET}")
        except Exception as e:
            print(f"{CLR_RED}Error sending SIGKILL to PID {pid}: {e}{CLR_RESET}")
    else:
        print(f"{CLR_YELLOW}PID {pid} for extension '{ext_id}' had already terminated. Cleaned up state.{CLR_RESET}")

    with conn:
        conn.execute("UPDATE extension_runtime SET active_pid = 0, status = 'killed' WHERE ext_id = ?;", (ext_id,))
    conn.close()
    return 0

def cmd_kill_all():
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT ext_id, active_pid FROM extension_runtime WHERE active_pid > 0;")
    rows = cur.fetchall()

    killed_count = 0
    for ext_id, pid in rows:
        if is_pid_alive(pid):
            os.system(f"kill -9 -{pid} 2>/dev/null")
            os.system(f"kill -9 {pid} 2>/dev/null")
            killed_count += 1
            print(f"{CLR_RED}⚡ Force killed '{ext_id}' (PID {pid}){CLR_RESET}")

    with conn:
        conn.execute("UPDATE extension_runtime SET active_pid = 0, status = 'killed' WHERE active_pid > 0;")
    conn.close()

    print(f"{CLR_GREEN}✔ Safety Breaker: Terminated {killed_count} active extension process(es).{CLR_RESET}")
    return 0

def main():
    if len(sys.argv) < 2:
        print(f"{CLR_CYAN}{CLR_BOLD}VoidTerm Universal Extension CLI (void-ext / ext){CLR_RESET}")
        print("Usage:")
        print("  void ext list                   List all installed extensions & live PIDs")
        print("  void ext run <id> [args...]     Run extension (with singleton concurrency checks)")
        print("  void ext kill <id>              Instant force kill single extension")
        print("  void ext kill-all               Master safety breaker (kill all running extensions)")
        print("  void ext create <name> [--py|--js] Scaffold new polyglot extension template")
        return

    cmd = sys.argv[1]
    if cmd == "list":
        cmd_list()
    elif cmd == "run":
        if len(sys.argv) < 3:
            print("Error: Missing extension ID. Usage: void ext run <id>")
            sys.exit(1)
        sys.exit(cmd_run(sys.argv[2], sys.argv[3:]))
    elif cmd in ("kill", "stop"):
        if len(sys.argv) < 3:
            print("Error: Missing extension ID. Usage: void ext kill <id>")
            sys.exit(1)
        if sys.argv[2] in ("--all", "-all", "all"):
            sys.exit(cmd_kill_all())
        else:
            sys.exit(cmd_kill(sys.argv[2]))
    elif cmd in ("kill-all", "killall"):
        sys.exit(cmd_kill_all())
    elif cmd in ("create", "new"):
        if len(sys.argv) < 3:
            print("Error: Missing extension name. Usage: void ext create <name> [--py|--js]")
            sys.exit(1)
        lang = "javascript" if "--js" in sys.argv else "python"
        sys.exit(cmd_create(sys.argv[2], lang))
    else:
        print(f"Unknown command: {cmd}. Run 'void ext' for help.")

if __name__ == "__main__":
    main()

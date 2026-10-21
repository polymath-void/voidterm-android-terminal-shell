#!/usr/bin/env python3
"""
VoidTerm Extension Hub TUI (/hub)
Interactive, dual-panel terminal dashboard to inspect, launch,
and terminate polyglot extensions in real time.
"""

import os
import sys
import json
import termios
import tty
import subprocess

PREFIX = os.environ.get("PREFIX", "/data/data/com.hybridengine.terminal/files/usr")
EXT_DIR = os.path.join(PREFIX, "etc", "extensions")

CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_MAGENTA = "\033[35m"
CLR_GRAY = "\033[90m"
CLR_BG_SEL = "\033[44;37m"

def get_char():
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
        if ch == "\x1b":
            ch2 = sys.stdin.read(1)
            if ch2 == "[":
                ch3 = sys.stdin.read(1)
                return f"\x1b[{ch3}"
            return "\x1b"
        return ch
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)

def load_extensions():
    extensions = []
    if not os.path.exists(EXT_DIR):
        return extensions

    for d in sorted(os.listdir(EXT_DIR)):
        meta_path = os.path.join(EXT_DIR, d, "metadata.json")
        if os.path.exists(meta_path):
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    entry_path = os.path.join(EXT_DIR, d, data.get("entry", ""))
                    data["directory"] = os.path.join(EXT_DIR, d)
                    data["entry_full"] = entry_path
                    data["is_running"] = False
                    extensions.append(data)
            except Exception:
                continue
    return extensions

def render(extensions, selected_idx, log_msg=""):
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  🧩 VoidTerm Sovereign Extension Hub (TUI Console)          ║{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n")

    if not extensions:
        sys.stdout.write(f"\r\n  {CLR_GRAY}No extensions installed in {EXT_DIR}{CLR_RESET}\r\n")
    else:
        for idx, ext in enumerate(extensions):
            is_sel = (idx == selected_idx)
            name = ext.get("name", ext.get("id", "Unknown")).ljust(22)
            slash = f"/{ext.get('id', '')}".ljust(10)
            runtime = "🐍 Py" if ext.get("runtime") == "python" else "⚡ JS"

            if is_sel:
                line = f"{CLR_BG_SEL} > {name} {slash} [{runtime}] {CLR_RESET}"
            else:
                line = f"   {CLR_BOLD}{name}{CLR_RESET} {CLR_CYAN}{slash}{CLR_RESET} {CLR_GRAY}[{runtime}]{CLR_RESET}"
            sys.stdout.write(line + "\r\n")

    sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    if extensions and 0 <= selected_idx < len(extensions):
        curr = extensions[selected_idx]
        desc = curr.get("description", "No description available.")
        sys.stdout.write(f"{CLR_MAGENTA}Info:{CLR_RESET} {desc}\r\n")
        sys.stdout.write(f"{CLR_GRAY}Entry: {curr.get('entry', '')}{CLR_RESET}\r\n")

    if log_msg:
        sys.stdout.write(f"\r\n{CLR_YELLOW}Status:{CLR_RESET} {log_msg}\r\n")

    sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_BOLD}[ENTER/l]{CLR_RESET} Launch   {CLR_BOLD}[q]{CLR_RESET} Exit Hub   {CLR_BOLD}[r]{CLR_RESET} Reload\r\n")
    sys.stdout.flush()

def launch_extension(ext):
    entry = ext.get("entry_full")
    if not entry or not os.path.exists(entry):
        return f"Entry file not found: {entry}"

    sys.stdout.write("\033[2J\033[H")
    sys.stdout.flush()
    try:
        if ext.get("runtime") == "python":
            subprocess.run(["python3", entry])
        else:
            subprocess.run(["node", entry])
        return f"Completed execution of {ext.get('name')}"
    except Exception as e:
        return f"Error launching {ext.get('name')}: {e}"

def main():
    extensions = load_extensions()
    selected_idx = 0
    status_msg = ""

    while True:
        render(extensions, selected_idx, status_msg)
        ch = get_char()

        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
            status_msg = ""
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(len(extensions) - 1, selected_idx + 1)
            status_msg = ""
        elif ch in ("\r", "\n", "l", "L"):
            if extensions and 0 <= selected_idx < len(extensions):
                status_msg = launch_extension(extensions[selected_idx])
                extensions = load_extensions()
        elif ch in ("r", "R"):
            extensions = load_extensions()
            status_msg = "Reloaded extension catalog."

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
VoidTerm Directory Bookmarks (/bookmark)
Save, list, and jump to frequent directories.
"""

import os
import sys
import json
import termios
import tty

BOOKMARKS_FILE = os.path.expanduser("~/.voidbookmarks.json")

CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
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

def load_bookmarks():
    if os.path.exists(BOOKMARKS_FILE):
        try:
            with open(BOOKMARKS_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    # Defaults
    home = os.path.expanduser("~")
    return [
        {"name": "Home", "path": home},
        {"name": "Shared Storage", "path": os.path.join(home, "storage", "shared")},
        {"name": "Downloads", "path": os.path.join(home, "storage", "downloads")},
        {"name": "Projects", "path": os.path.join(home, "Projects")}
    ]

def save_bookmarks(bms):
    with open(BOOKMARKS_FILE, "w") as f:
        json.dump(bms, f, indent=2)

def main():
    selected_idx = 0
    status = ""
    while True:
        bms = load_bookmarks()
        if selected_idx >= len(bms):
            selected_idx = max(0, len(bms) - 1)

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  🔖 VoidTerm Directory Bookmarks (/bookmark)                 ║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

        for idx, bm in enumerate(bms):
            is_sel = (idx == selected_idx)
            name = bm.get("name", "").ljust(18)
            path = bm.get("path", "")
            exists = "📁" if os.path.exists(path) else "❌"

            if is_sel:
                sys.stdout.write(f"{CLR_BG_SEL} > {name} {exists} {path:<34} {CLR_RESET}\r\n")
            else:
                sys.stdout.write(f"   {CLR_BOLD}{name}{CLR_RESET} {exists} {CLR_GRAY}{path}{CLR_RESET}\r\n")

        sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        if status:
            sys.stdout.write(f"{CLR_YELLOW}{status}{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_BOLD}[a]{CLR_RESET} Add CWD   {CLR_BOLD}[d]{CLR_RESET} Delete   {CLR_BOLD}[q]{CLR_RESET} Quit\r\n")
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(bms) - 1), selected_idx + 1)
        elif ch in ("a", "A"):
            cwd = os.getcwd()
            sys.stdout.write(f"\r\n\033[KBookmark name for '{cwd}': ")
            sys.stdout.flush()
            bname = sys.stdin.readline().strip()
            if bname:
                bms.append({"name": bname, "path": cwd})
                save_bookmarks(bms)
                status = f"Saved bookmark '{bname}'"
        elif ch in ("d", "D"):
            if bms and 0 <= selected_idx < len(bms):
                removed = bms.pop(selected_idx)
                save_bookmarks(bms)
                status = f"Removed bookmark '{removed.get('name')}'"

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
VoidTerm Alias Manager (/alias)
Inspect and manage bash aliases in ~/.bashrc
"""

import os
import sys
import re
import termios
import tty

BASHRC = os.path.expanduser("~/.bashrc")

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

def load_aliases():
    aliases = []
    if os.path.exists(BASHRC):
        with open(BASHRC, "r", encoding="utf-8") as f:
            for line in f:
                m = re.match(r"^alias\s+([^=]+)=['\"]?(.*?)['\"]?$", line.strip())
                if m:
                    aliases.append((m.group(1).strip(), m.group(2).strip()))
    return aliases

def main():
    selected_idx = 0
    status = ""
    while True:
        aliases = load_aliases()
        if selected_idx >= len(aliases):
            selected_idx = max(0, len(aliases) - 1)

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  ⚡ VoidTerm Shell Alias Manager (/alias)                    ║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

        if not aliases:
            sys.stdout.write(f"  {CLR_GRAY}No aliases defined in ~/.bashrc. (Press [a] to add one){CLR_RESET}\r\n")
        else:
            for idx, (name, cmd) in enumerate(aliases):
                is_sel = (idx == selected_idx)
                if is_sel:
                    sys.stdout.write(f"{CLR_BG_SEL} > {name:<15} = {cmd:<38} {CLR_RESET}\r\n")
                else:
                    sys.stdout.write(f"   {CLR_BOLD}{name:<15}{CLR_RESET} = {CLR_CYAN}{cmd}{CLR_RESET}\r\n")

        sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        if status:
            sys.stdout.write(f"{CLR_YELLOW}{status}{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_BOLD}[a]{CLR_RESET} Add Alias   {CLR_BOLD}[r]{CLR_RESET} Reload   {CLR_BOLD}[q]{CLR_RESET} Quit\r\n")
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(aliases) - 1), selected_idx + 1)
        elif ch in ("a", "A"):
            sys.stdout.write("\r\n\033[KAlias Name (e.g. ll): ")
            sys.stdout.flush()
            aname = sys.stdin.readline().strip()
            if aname:
                sys.stdout.write(f"Command for '{aname}': ")
                sys.stdout.flush()
                acmd = sys.stdin.readline().strip()
                if acmd:
                    with open(BASHRC, "a", encoding="utf-8") as f:
                        f.write(f"\nalias {aname}='{acmd}'\n")
                    status = f"Added alias {aname}='{acmd}'"
        elif ch in ("r", "R"):
            status = "Reloaded aliases from ~/.bashrc"

if __name__ == "__main__":
    main()

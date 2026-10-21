#!/usr/bin/env python3
"""
VoidTerm History Search (/history)
Interactive fuzzy search and rerun for bash command history.
"""

import os
import sys
import termios
import tty
import subprocess

HISTORY_FILE = os.path.expanduser("~/.bash_history")

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

def load_history():
    cmds = []
    if os.path.exists(HISTORY_FILE):
        with open(HISTORY_FILE, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                c = line.strip()
                if c and (not cmds or cmds[-1] != c):
                    cmds.append(c)
    return list(reversed(cmds))

def main():
    filter_text = ""
    selected_idx = 0
    page_size = 14
    top_offset = 0

    while True:
        all_cmds = load_history()
        filtered = [c for c in all_cmds if filter_text.lower() in c.lower()]

        if selected_idx >= len(filtered):
            selected_idx = max(0, len(filtered) - 1)

        if selected_idx < top_offset:
            top_offset = selected_idx
        elif selected_idx >= top_offset + page_size:
            top_offset = selected_idx - page_size + 1

        visible = filtered[top_offset:top_offset + page_size]

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  📜 VoidTerm Command History Search (/history)               ║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n")
        if filter_text:
            sys.stdout.write(f"Search: {CLR_YELLOW}{filter_text}{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")

        if not filtered:
            sys.stdout.write(f"  {CLR_GRAY}No matching history entries.{CLR_RESET}\r\n")
        else:
            for idx, c in enumerate(visible):
                is_sel = (top_offset + idx == selected_idx)
                c_disp = (c[:52] + "...") if len(c) > 52 else c
                if is_sel:
                    sys.stdout.write(f"{CLR_BG_SEL} > {c_disp:<55} {CLR_RESET}\r\n")
                else:
                    sys.stdout.write(f"   {CLR_BOLD}{c_disp}{CLR_RESET}\r\n")

        for _ in range(page_size - len(visible)):
            sys.stdout.write("\r\n")

        sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_BOLD}[ENTER]{CLR_RESET} Execute   {CLR_BOLD}[/]{CLR_RESET} Filter   {CLR_BOLD}[c]{CLR_RESET} Clear Filter   {CLR_BOLD}[q]{CLR_RESET} Quit\r\n")
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(filtered) - 1), selected_idx + 1)
        elif ch == "/":
            sys.stdout.write("\r\n\033[KSearch history: ")
            sys.stdout.flush()
            filter_text = sys.stdin.readline().strip()
            selected_idx = 0
            top_offset = 0
        elif ch in ("c", "C"):
            filter_text = ""
            selected_idx = 0
            top_offset = 0
        elif ch in ("\r", "\n"):
            if filtered and 0 <= selected_idx < len(filtered):
                chosen = filtered[selected_idx]
                sys.stdout.write("\033[2J\033[H")
                sys.stdout.write(f"{CLR_CYAN}>>> Executing: {chosen}{CLR_RESET}\r\n\r\n")
                sys.stdout.flush()
                subprocess.run(chosen, shell=True)
                sys.stdout.write(f"\r\n{CLR_GRAY}Press any key to return...{CLR_RESET}")
                sys.stdout.flush()
                get_char()

if __name__ == "__main__":
    main()

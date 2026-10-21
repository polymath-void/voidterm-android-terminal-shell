#!/usr/bin/env python3
"""
VoidTerm Environment Editor (/env)
Interactive TUI for viewing and searching environment variables.
"""

import os
import sys
import termios
import tty

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

def main():
    filter_text = ""
    selected_idx = 0
    page_size = 14
    top_offset = 0

    while True:
        items = sorted([(k, v) for k, v in os.environ.items() if filter_text.lower() in k.lower() or filter_text.lower() in v.lower()])
        if selected_idx >= len(items):
            selected_idx = max(0, len(items) - 1)

        if selected_idx < top_offset:
            top_offset = selected_idx
        elif selected_idx >= top_offset + page_size:
            top_offset = selected_idx - page_size + 1

        visible = items[top_offset:top_offset + page_size]

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  ⚙️  Shell Environment Inspector (/env)                      ║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n")
        if filter_text:
            sys.stdout.write(f"Filter: {CLR_YELLOW}{filter_text}{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")

        for idx, (k, v) in enumerate(visible):
            real_idx = top_offset + idx
            is_sel = (real_idx == selected_idx)
            val_display = (v[:35] + "...") if len(v) > 35 else v

            if is_sel:
                sys.stdout.write(f"{CLR_BG_SEL} > {k:<20} = {val_display:<35} {CLR_RESET}\r\n")
            else:
                sys.stdout.write(f"   {CLR_BOLD}{k:<20}{CLR_RESET} = {CLR_GRAY}{val_display}{CLR_RESET}\r\n")

        for _ in range(page_size - len(visible)):
            sys.stdout.write("\r\n")

        sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        if items and 0 <= selected_idx < len(items):
            sel_k, sel_v = items[selected_idx]
            sys.stdout.write(f"{CLR_GREEN}{sel_k}:{CLR_RESET} {sel_v}\r\n")

        sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_BOLD}[/]{CLR_RESET} Filter   {CLR_BOLD}[c]{CLR_RESET} Clear Filter   {CLR_BOLD}[q]{CLR_RESET} Quit\r\n")
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(items) - 1), selected_idx + 1)
        elif ch == "/":
            sys.stdout.write("\r\n\033[KEnter filter term: ")
            sys.stdout.flush()
            filter_text = sys.stdin.readline().strip()
            selected_idx = 0
            top_offset = 0
        elif ch in ("c", "C"):
            filter_text = ""
            selected_idx = 0
            top_offset = 0

if __name__ == "__main__":
    main()

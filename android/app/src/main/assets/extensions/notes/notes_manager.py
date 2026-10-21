#!/usr/bin/env python3
"""
VoidTerm Workspace Notes (/notes)
Quick terminal scratchpad for viewing and capturing developer notes.
"""

import os
import sys
import termios
import tty
import datetime

NOTES_DIR = os.path.expanduser("~/.voidnotes")
os.makedirs(NOTES_DIR, exist_ok=True)

CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
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
    selected_idx = 0
    while True:
        notes = sorted([f for f in os.listdir(NOTES_DIR) if f.endswith(".txt") or f.endswith(".md")])
        if selected_idx >= len(notes):
            selected_idx = max(0, len(notes) - 1)

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  📝 VoidTerm Workspace Scratchpad (/notes)                   ║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

        if not notes:
            sys.stdout.write(f"  {CLR_GRAY}No notes yet in ~/.voidnotes/  (Press [n] to create one){CLR_RESET}\r\n")
        else:
            for idx, note in enumerate(notes):
                is_sel = (idx == selected_idx)
                if is_sel:
                    sys.stdout.write(f"{CLR_BG_SEL} > {note:<50} {CLR_RESET}\r\n")
                else:
                    sys.stdout.write(f"   {CLR_BOLD}{note}{CLR_RESET}\r\n")

        sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        # Preview note content
        if notes and 0 <= selected_idx < len(notes):
            preview_path = os.path.join(NOTES_DIR, notes[selected_idx])
            try:
                with open(preview_path, "r", encoding="utf-8") as f:
                    content_lines = f.readlines()[:6]
                sys.stdout.write(f"{CLR_GREEN}Content Preview:{CLR_RESET}\r\n")
                for cl in content_lines:
                    sys.stdout.write(f"  {CLR_GRAY}{cl.rstrip()}{CLR_RESET}\r\n")
            except Exception:
                pass

        sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_BOLD}[n]{CLR_RESET} New Note   {CLR_BOLD}[d]{CLR_RESET} Delete   {CLR_BOLD}[v]{CLR_RESET} View Full   {CLR_BOLD}[q]{CLR_RESET} Quit\r\n")
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(notes) - 1), selected_idx + 1)
        elif ch in ("n", "N"):
            sys.stdout.write("\r\n\033[KNote Title (or press enter for timestamp): ")
            sys.stdout.flush()
            title = sys.stdin.readline().strip()
            if not title:
                title = datetime.datetime.now().strftime("note_%Y%m%d_%H%M%S")
            if not title.endswith(".md") and not title.endswith(".txt"):
                title += ".md"

            sys.stdout.write("Enter note text: ")
            sys.stdout.flush()
            body = sys.stdin.readline().strip()
            note_file = os.path.join(NOTES_DIR, title)
            with open(note_file, "w", encoding="utf-8") as f:
                f.write(f"# {title}\n\n{body}\n")
        elif ch in ("d", "D"):
            if notes and 0 <= selected_idx < len(notes):
                del_file = os.path.join(NOTES_DIR, notes[selected_idx])
                try:
                    os.remove(del_file)
                except Exception:
                    pass
        elif ch in ("v", "V", "\r", "\n"):
            if notes and 0 <= selected_idx < len(notes):
                view_file = os.path.join(NOTES_DIR, notes[selected_idx])
                sys.stdout.write("\033[2J\033[H")
                try:
                    with open(view_file, "r", encoding="utf-8") as f:
                        print(f.read())
                except Exception as e:
                    print(e)
                sys.stdout.write(f"\r\n{CLR_GRAY}Press any key to return...{CLR_RESET}")
                sys.stdout.flush()
                get_char()

if __name__ == "__main__":
    main()

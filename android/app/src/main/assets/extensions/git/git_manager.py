#!/usr/bin/env python3
"""
VoidTerm Git Workspace Manager (/git)
Interactive TUI for git status, staging, committing, and viewing commit history.
"""

import os
import sys
import subprocess
import termios
import tty

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

def run_git(args):
    try:
        res = subprocess.run(["git"] + args, capture_output=True, text=True)
        return res.stdout.strip(), res.stderr.strip(), res.returncode
    except Exception as e:
        return "", str(e), 1

def main():
    out, err, code = run_git(["status", "--porcelain"])
    if code != 0 and "not a git repository" in err.lower():
        print(f"{CLR_RED}Not a git repository (or any of the parent directories).{CLR_RESET}")
        return

    selected_idx = 0
    status_msg = ""

    while True:
        out, _, _ = run_git(["status", "--porcelain"])
        lines = [line for line in out.split("\n") if line.strip()]
        branch, _, _ = run_git(["branch", "--show-current"])
        if not branch:
            branch = "HEAD (detached)"

        sys.stdout.write("\033[2J\033[H")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  🌿 Git Workspace Manager  | Branch: {branch:<23}║{CLR_RESET}\r\n")
        sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

        if not lines:
            sys.stdout.write(f"  {CLR_GREEN}✔ Working tree clean — nothing to commit{CLR_RESET}\r\n")
        else:
            for idx, item in enumerate(lines):
                is_sel = (idx == selected_idx)
                status_code = item[:2]
                filepath = item[3:]

                color = CLR_GREEN if status_code[0] in ("M", "A") else CLR_RED
                if is_sel:
                    sys.stdout.write(f"{CLR_BG_SEL} > [{status_code}] {filepath:<45} {CLR_RESET}\r\n")
                else:
                    sys.stdout.write(f"   {color}[{status_code}]{CLR_RESET} {filepath}\r\n")

        sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
        if status_msg:
            sys.stdout.write(f"{CLR_YELLOW}{status_msg}{CLR_RESET}\r\n")
        sys.stdout.write(
            f"{CLR_BOLD}[s]{CLR_RESET} Stage All  "
            f"{CLR_BOLD}[c]{CLR_RESET} Commit  "
            f"{CLR_BOLD}[p]{CLR_RESET} Push  "
            f"{CLR_BOLD}[d]{CLR_RESET} Diff  "
            f"{CLR_BOLD}[l]{CLR_RESET} Log  "
            f"{CLR_BOLD}[q]{CLR_RESET} Quit\r\n"
        )
        sys.stdout.flush()

        ch = get_char()
        if ch in ("q", "Q", "\x03"):
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"):
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"):
            selected_idx = min(max(0, len(lines) - 1), selected_idx + 1)
        elif ch in ("s", "S"):
            _, err, c = run_git(["add", "."])
            status_msg = "✔ Staged all changes." if c == 0 else f"✘ Stage error: {err}"
        elif ch in ("c", "C"):
            sys.stdout.write("\r\n\033[KEnter commit message: ")
            sys.stdout.flush()
            msg = sys.stdin.readline().strip()
            if msg:
                _, err, c = run_git(["commit", "-m", msg])
                status_msg = "✔ Committed." if c == 0 else f"✘ Commit error: {err}"
        elif ch in ("p", "P"):
            sys.stdout.write("\r\n\033[KPushing to origin...\r\n")
            sys.stdout.flush()
            _, err, c = run_git(["push"])
            status_msg = "✔ Pushed." if c == 0 else f"✘ Push error: {err}"
        elif ch in ("d", "D"):
            sys.stdout.write("\033[2J\033[H")
            subprocess.run(["git", "diff"])
            sys.stdout.write(f"\r\n{CLR_GRAY}Press any key to return...{CLR_RESET}")
            sys.stdout.flush()
            get_char()
        elif ch in ("l", "L"):
            sys.stdout.write("\033[2J\033[H")
            subprocess.run(["git", "log", "--oneline", "-n", "15"])
            sys.stdout.write(f"\r\n{CLR_GRAY}Press any key to return...{CLR_RESET}")
            sys.stdout.flush()
            get_char()

if __name__ == "__main__":
    main()

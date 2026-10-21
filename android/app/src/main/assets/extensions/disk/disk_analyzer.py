#!/usr/bin/env python3
"""
VoidTerm Disk Analyzer (/disk)
Visual ASCII bar chart of disk capacity, internal storage, and package dirs.
"""

import os
import sys
import shutil
import termios
import tty

CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_GRAY = "\033[90m"

def get_char():
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
        return ch
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)

def format_size(bytes_val):
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if bytes_val < 1024.0:
            return f"{bytes_val:.1f} {unit}"
        bytes_val /= 1024.0
    return f"{bytes_val:.1f} PB"

def draw_bar(pct, width=28):
    filled = int(width * (pct / 100))
    bar = "█" * filled + "░" * (width - filled)
    color = CLR_GREEN if pct < 70 else (CLR_YELLOW if pct < 85 else CLR_RED)
    return f"{color}{bar}{CLR_RESET} {pct:.1f}%"

def main():
    home = os.path.expanduser("~")
    dirs_to_check = [
        ("Home ($HOME)", home),
        ("Shared Storage", os.path.join(home, "storage", "shared")),
        ("Data Root (/data)", "/data"),
    ]

    sys.stdout.write("\033[2J\033[H")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  💾 VoidTerm Storage & Disk Analyzer (/disk)                 ║{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

    for label, path in dirs_to_check:
        if os.path.exists(path):
            try:
                usage = shutil.disk_usage(path)
                pct = (usage.used / usage.total) * 100
                sys.stdout.write(f"  {CLR_BOLD}{label}:{CLR_RESET}\r\n")
                sys.stdout.write(f"    Path:  {CLR_GRAY}{path}{CLR_RESET}\r\n")
                sys.stdout.write(f"    Used:  {format_size(usage.used)} / {format_size(usage.total)} (Free: {format_size(usage.free)})\r\n")
                sys.stdout.write(f"    Usage: {draw_bar(pct)}\r\n\r\n")
            except Exception as e:
                sys.stdout.write(f"  {label}: Error checking ({e})\r\n\r\n")

    sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_GRAY}Press any key to exit...{CLR_RESET}\r\n")
    sys.stdout.flush()
    get_char()
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.flush()

if __name__ == "__main__":
    main()

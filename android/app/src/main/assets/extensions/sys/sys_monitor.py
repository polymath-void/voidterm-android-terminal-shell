#!/usr/bin/env python3
"""
VoidTerm System Monitor (/sys)
Live CPU, RAM, and system process telemetry display.
"""

import os
import sys
import time
import termios
import tty
import select

CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_GRAY = "\033[90m"

def get_mem_info():
    total, free, available = 0, 0, 0
    try:
        with open("/proc/meminfo", "r") as f:
            for line in f:
                parts = line.split()
                if parts[0] == "MemTotal:":
                    total = int(parts[1])
                elif parts[0] == "MemFree:":
                    free = int(parts[1])
                elif parts[0] == "MemAvailable:":
                    available = int(parts[1])
    except Exception:
        pass
    used = total - (available if available else free)
    pct = (used / total * 100) if total else 0
    return total // 1024, used // 1024, pct

def get_load_avg():
    try:
        with open("/proc/loadavg", "r") as f:
            parts = f.read().split()
            return f"{parts[0]} {parts[1]} {parts[2]}"
    except Exception:
        return "N/A"

def draw_bar(pct, width=24):
    filled = int(width * (pct / 100))
    bar = "█" * filled + "░" * (width - filled)
    color = CLR_GREEN if pct < 70 else (CLR_YELLOW if pct < 85 else CLR_RED)
    return f"{color}{bar}{CLR_RESET} {pct:.1f}%"

def main():
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        while True:
            total_mb, used_mb, mem_pct = get_mem_info()
            load = get_load_avg()

            sys.stdout.write("\033[2J\033[H")
            sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╔══════════════════════════════════════════════════════════════╗{CLR_RESET}\r\n")
            sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}║  ⚡ VoidTerm System & Resource Monitor                      ║{CLR_RESET}\r\n")
            sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}╚══════════════════════════════════════════════════════════════╝{CLR_RESET}\r\n\r\n")

            sys.stdout.write(f"  {CLR_BOLD}CPU Load Avg (1/5/15m):{CLR_RESET}  {load}\r\n")
            sys.stdout.write(f"  {CLR_BOLD}Memory Usage:{CLR_RESET}            {used_mb}MB / {total_mb}MB\r\n")
            sys.stdout.write(f"  {CLR_BOLD}Memory Bar:{CLR_RESET}              {draw_bar(mem_pct)}\r\n\r\n")

            sys.stdout.write(f"{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
            sys.stdout.write(f"{CLR_BOLD}Top Active User Processes:{CLR_RESET}\r\n\r\n")

            try:
                pids = [p for p in os.listdir("/proc") if p.isdigit()]
                count = 0
                for pid in pids[:10]:
                    try:
                        with open(f"/proc/{pid}/comm", "r") as f:
                            comm = f.read().strip()
                        sys.stdout.write(f"  PID {pid.rjust(6)}: {CLR_CYAN}{comm}{CLR_RESET}\r\n")
                        count += 1
                        if count >= 8:
                            break
                    except Exception:
                        continue
            except Exception:
                pass

            sys.stdout.write(f"\r\n{CLR_GRAY}──────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
            sys.stdout.write(f"{CLR_GRAY}Refreshing every 2s. Press [q] to exit.{CLR_RESET}\r\n")
            sys.stdout.flush()

            # Wait 2 seconds or check for key press
            r, _, _ = select.select([sys.stdin], [], [], 2.0)
            if r:
                ch = sys.stdin.read(1)
                if ch in ("q", "Q", "\x03"):
                    break
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
        sys.stdout.write("\033[2J\033[H")
        sys.stdout.flush()

if __name__ == "__main__":
    main()

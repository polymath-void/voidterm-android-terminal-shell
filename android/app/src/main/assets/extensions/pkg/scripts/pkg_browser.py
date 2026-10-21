#!/data/user/0/com.hybridengine.terminal/files/usr/bin/python3
"""
VoidTerm Package Manager TUI (/pkg)
Interactive ANSI terminal package browser with live search, install, update, and upgrade.
"""

import os
import sys
import json
import tty
import termios
import subprocess

PREFIX = os.environ.get("PREFIX", "/data/user/0/com.hybridengine.terminal/files/usr")
VOIDPKG = os.path.join(PREFIX, "bin", "voidpkg.py")

# Dracula ANSI Palette
CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_MAGENTA = "\033[35m"
CLR_GRAY = "\033[90m"
CLR_BG_SEL = "\033[44;37m"

def get_package_list():
    """Reads available and installed packages from voidpkg catalog."""
    installed = set()
    usr_bin = os.path.join(PREFIX, "bin")
    if os.path.exists(usr_bin):
        installed = set(os.listdir(usr_bin))

    catalog_path = os.path.join(PREFIX, "var", "lib", "voidpkg", "catalog.json")
    packages = []

    # Built-in core catalog defaults
    core_pkgs = [
        {"name": "bash", "version": "5.2.15", "desc": "GNU Bourne Again Shell"},
        {"name": "python3", "version": "3.14.0", "desc": "Python 3 core interpreter"},
        {"name": "git", "version": "2.42.0", "desc": "Fast distributed revision control"},
        {"name": "nano", "version": "7.2", "desc": "Small, friendly text editor"},
        {"name": "vim", "version": "9.0", "desc": "Vi IMproved, a powerful text editor"},
        {"name": "curl", "version": "8.4.0", "desc": "Command line tool for transferring data"},
        {"name": "wget", "version": "1.21.4", "desc": "Utility for retrieving files using HTTP/HTTPS"},
        {"name": "tar", "version": "1.35", "desc": "GNU tar archiving utility"},
        {"name": "gzip", "version": "1.13", "desc": "GNU compression utility"},
        {"name": "sqlite3", "version": "3.43.2", "desc": "Command-line interface for SQLite"},
        {"name": "openssl", "version": "3.1.4", "desc": "Cryptography and SSL/TLS toolkit"},
        {"name": "tree", "version": "2.1.1", "desc": "Recursive directory listing program"},
        {"name": "htop", "version": "3.2.2", "desc": "Interactive process viewer"},
        {"name": "nodejs", "version": "20.9.0", "desc": "JavaScript runtime built on Chrome V8"},
        {"name": "neofetch", "version": "7.1.0", "desc": "CLI system information tool"},
        {"name": "jq", "version": "1.7", "desc": "Command-line JSON processor"},
    ]

    if os.path.exists(catalog_path):
        try:
            with open(catalog_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    for k, v in data.items():
                        packages.append({
                            "name": k,
                            "version": v.get("version", "latest"),
                            "desc": v.get("description", "VoidTerm Package")
                        })
        except Exception:
            packages = core_pkgs
    else:
        packages = core_pkgs

    # Deduplicate and sort
    seen = set()
    result = []
    for p in packages:
        if p["name"] not in seen:
            seen.add(p["name"])
            p["installed"] = p["name"] in installed
            result.append(p)

    return sorted(result, key=lambda x: (not x["installed"], x["name"]))

def get_char():
    """Reads a single keypress without echoing in raw terminal mode."""
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
        if ch == "\x1b":
            # Handle ANSI escape sequence (arrows)
            seq = sys.stdin.read(2)
            return "\x1b" + seq
        return ch
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

def render(packages, selected_idx, filter_str, top_offset, page_size):
    """Renders the ANSI TUI frame."""
    filtered = [p for p in packages if filter_str.lower() in p["name"].lower() or filter_str.lower() in p["desc"].lower()]
    total = len(filtered)
    if total == 0:
        filtered = []
        selected_idx = 0
    else:
        selected_idx = max(0, min(selected_idx, total - 1))

    # Clear screen and move cursor to top
    sys.stdout.write("\033[2J\033[H")

    # Header
    sys.stdout.write(f"{CLR_MAGENTA}{CLR_BOLD}📦 VoidTerm Package Manager [/pkg]{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_GRAY}────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    search_badge = f"{CLR_CYAN}Search:{CLR_RESET} {filter_str}_" if filter_str else f"{CLR_GRAY}Search: (Press '/' to filter){CLR_RESET}"
    sys.stdout.write(f"{search_badge}\r\n")
    sys.stdout.write(f"{CLR_GRAY}────────────────────────────────────────────────────────────{CLR_RESET}\r\n")

    # Visible slice
    if selected_idx < top_offset:
        top_offset = selected_idx
    elif selected_idx >= top_offset + page_size:
        top_offset = selected_idx - page_size + 1

    visible = filtered[top_offset:top_offset + page_size]
    for idx, p in enumerate(visible):
        real_idx = top_offset + idx
        is_sel = (real_idx == selected_idx)

        status_badge = f"{CLR_GREEN}[INSTALLED]{CLR_RESET}" if p.get("installed") else f"{CLR_CYAN}[AVAILABLE]{CLR_RESET}"
        name = p["name"].ljust(14)
        ver = p.get("version", "").ljust(10)
        desc = p.get("desc", "")[:35]

        if is_sel:
            line = f"{CLR_BG_SEL} > {name} {ver} {desc:<35} {CLR_RESET} {status_badge}"
        else:
            line = f"   {CLR_BOLD}{name}{CLR_RESET} {CLR_GRAY}{ver}{CLR_RESET} {desc} {status_badge}"

        sys.stdout.write(line + "\r\n")

    # Fill empty lines if any
    for _ in range(page_size - len(visible)):
        sys.stdout.write("\r\n")

    # Footer Controls
    sys.stdout.write(f"{CLR_GRAY}────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    sys.stdout.write(
        f"{CLR_BOLD}[i]{CLR_RESET} Install  "
        f"{CLR_BOLD}[u]{CLR_RESET} Update  "
        f"{CLR_BOLD}[r]{CLR_RESET} Remove  "
        f"{CLR_BOLD}[U]{CLR_RESET} Pkg Update  "
        f"{CLR_BOLD}[G]{CLR_RESET} Pkg Upgrade  "
        f"{CLR_BOLD}[q]{CLR_RESET} Quit\r\n"
    )
    sys.stdout.flush()
    return filtered, selected_idx, top_offset

def run_command(cmd_args):
    """Restores cooked mode, runs command with live terminal stream, then prompts to continue."""
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}>>> Executing: {' '.join(cmd_args)}{CLR_RESET}\r\n\r\n")
    sys.stdout.flush()

    try:
        proc = subprocess.run(cmd_args)
        status_msg = f"{CLR_GREEN}✔ Done (exit code {proc.returncode}){CLR_RESET}"
    except Exception as e:
        status_msg = f"{CLR_RED}✘ Execution error: {e}{CLR_RESET}"

    sys.stdout.write(f"\r\n{status_msg}\r\n{CLR_GRAY}Press any key to return to package browser...{CLR_RESET}")
    sys.stdout.flush()
    get_char()

def main():
    if not sys.stdin.isatty():
        # Fallback for non-interactive execution
        print("VoidTerm Package Manager (/pkg): Standard CLI Mode")
        subprocess.run(["python3", VOIDPKG, "list"])
        return

    packages = get_package_list()
    selected_idx = 0
    top_offset = 0
    filter_str = ""
    page_size = 14

    while True:
        filtered, selected_idx, top_offset = render(
            packages, selected_idx, filter_str, top_offset, page_size
        )

        ch = get_char()

        if ch in ("q", "Q", "\x03"): # Quit or Ctrl+C
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"): # Arrow Up or k
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"): # Arrow Down or j
            selected_idx = min(len(filtered) - 1, selected_idx + 1)
        elif ch in ("/", "s"): # Enter search mode
            sys.stdout.write("\r\n\033[KFilter packages: ")
            sys.stdout.flush()
            # Temporary echo
            filter_str = sys.stdin.readline().strip()
            selected_idx = 0
            top_offset = 0
        elif ch == "\x7f" or ch == "\b": # Backspace cleans filter
            if filter_str:
                filter_str = filter_str[:-1]
                selected_idx = 0
        elif ch in ("i", "I"): # Install selected
            if filtered and selected_idx < len(filtered):
                pkg_name = filtered[selected_idx]["name"]
                run_command(["python3", VOIDPKG, "install", pkg_name])
                packages = get_package_list()
        elif ch in ("u", "u"): # Update selected
            if filtered and selected_idx < len(filtered):
                pkg_name = filtered[selected_idx]["name"]
                run_command(["python3", VOIDPKG, "install", pkg_name])
                packages = get_package_list()
        elif ch in ("r", "R"): # Remove selected
            if filtered and selected_idx < len(filtered):
                pkg_name = filtered[selected_idx]["name"]
                run_command(["python3", VOIDPKG, "remove", pkg_name])
                packages = get_package_list()
        elif ch == "U": # Pkg Update
            run_command(["python3", VOIDPKG, "update"])
            packages = get_package_list()
        elif ch == "G": # Pkg Upgrade
            run_command(["python3", VOIDPKG, "upgrade"])
            packages = get_package_list()

if __name__ == "__main__":
    main()

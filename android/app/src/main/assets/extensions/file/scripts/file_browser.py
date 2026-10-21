#!/data/user/0/com.hybridengine.terminal/files/usr/bin/python3
"""
VoidTerm VFS File Browser (/file)
Interactive ANSI terminal file manager with Unicode glyphs, directory navigation,
file operations (copy, move, delete, create), and editor launching.
"""

import os
import sys
import shutil
import tty
import termios
import subprocess

PREFIX = os.environ.get("PREFIX", "/data/user/0/com.hybridengine.terminal/files/usr")
HOME = os.environ.get("HOME", "/data/user/0/com.hybridengine.terminal/files/home")

# Colors
CLR_RESET = "\033[0m"
CLR_BOLD = "\033[1m"
CLR_CYAN = "\033[36m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_RED = "\033[31m"
CLR_MAGENTA = "\033[35m"
CLR_GRAY = "\033[90m"
CLR_BG_SEL = "\033[44;37m"

def get_glyph(path, name):
    if os.path.islink(path):
        return "🔗", CLR_MAGENTA
    elif os.path.isdir(path):
        return "📁", CLR_CYAN
    elif os.access(path, os.X_OK) and not os.path.isdir(path):
        return "⚡", CLR_GREEN
    elif any(name.endswith(ext) for ext in [".tar", ".gz", ".zip", ".xz", ".bz2", ".7z", ".apk"]):
        return "📦", CLR_YELLOW
    return "📄", CLR_RESET

def format_size(bytes_val):
    if bytes_val < 1024:
        return f"{bytes_val} B"
    elif bytes_val < 1024 * 1024:
        return f"{bytes_val / 1024:.1f} KB"
    elif bytes_val < 1024 * 1024 * 1024:
        return f"{bytes_val / (1024 * 1024):.1f} MB"
    return f"{bytes_val / (1024 * 1024 * 1024):.2f} GB"

def get_entries(current_dir):
    try:
        items = os.listdir(current_dir)
    except Exception as e:
        return []

    dirs = []
    files = []
    for item in items:
        full = os.path.join(current_dir, item)
        glyph, color = get_glyph(full, item)
        is_d = os.path.isdir(full) and not os.path.islink(full)
        size_str = "-"
        if not is_d:
            try:
                size_str = format_size(os.path.getsize(full))
            except Exception:
                size_str = "?"
        entry = {
            "name": item,
            "path": full,
            "is_dir": is_d,
            "glyph": glyph,
            "color": color,
            "size": size_str
        }
        if is_d:
            dirs.append(entry)
        else:
            files.append(entry)

    return sorted(dirs, key=lambda x: x["name"].lower()) + sorted(files, key=lambda x: x["name"].lower())

def get_char():
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
        if ch == "\x1b":
            seq = sys.stdin.read(2)
            return "\x1b" + seq
        return ch
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

def render(current_dir, entries, selected_idx, top_offset, page_size):
    sys.stdout.write("\033[2J\033[H")

    # Header
    sys.stdout.write(f"{CLR_CYAN}{CLR_BOLD}📁 VoidTerm VFS File Manager [/file]{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_GRAY}Path: {current_dir}{CLR_RESET}\r\n")
    sys.stdout.write(f"{CLR_GRAY}────────────────────────────────────────────────────────────{CLR_RESET}\r\n")

    total = len(entries)
    if total == 0:
        sys.stdout.write(f"{CLR_GRAY}  (Empty directory){CLR_RESET}\r\n")
    else:
        selected_idx = max(0, min(selected_idx, total - 1))

    if selected_idx < top_offset:
        top_offset = selected_idx
    elif selected_idx >= top_offset + page_size:
        top_offset = selected_idx - page_size + 1

    visible = entries[top_offset:top_offset + page_size]
    for idx, e in enumerate(visible):
        real_idx = top_offset + idx
        is_sel = (real_idx == selected_idx)

        glyph = e["glyph"]
        name = e["name"][:38].ljust(40)
        sz = e["size"].rjust(10)

        if is_sel:
            sys.stdout.write(f"{CLR_BG_SEL} > {glyph} {name} {sz} {CLR_RESET}\r\n")
        else:
            sys.stdout.write(f"   {glyph} {e['color']}{name}{CLR_RESET} {CLR_GRAY}{sz}{CLR_RESET}\r\n")

    for _ in range(page_size - len(visible)):
        sys.stdout.write("\r\n")

    sys.stdout.write(f"{CLR_GRAY}────────────────────────────────────────────────────────────{CLR_RESET}\r\n")
    sys.stdout.write(
        f"{CLR_BOLD}[Enter]{CLR_RESET} Open  "
        f"{CLR_BOLD}[c]{CLR_RESET} Copy  "
        f"{CLR_BOLD}[m]{CLR_RESET} Move  "
        f"{CLR_BOLD}[d]{CLR_RESET} Delete  "
        f"{CLR_BOLD}[n]{CLR_RESET} New File  "
        f"{CLR_BOLD}[f]{CLR_RESET} New Folder  "
        f"{CLR_BOLD}[q]{CLR_RESET} Quit\r\n"
    )
    sys.stdout.flush()
    return selected_idx, top_offset

def prompt_input(msg):
    sys.stdout.write(f"\r\n\033[K{CLR_YELLOW}{msg}: {CLR_RESET}")
    sys.stdout.flush()
    return sys.stdin.readline().strip()

def main():
    current_dir = os.getcwd()
    selected_idx = 0
    top_offset = 0
    page_size = 14

    entries = get_entries(current_dir)

    while True:
        selected_idx, top_offset = render(current_dir, entries, selected_idx, top_offset, page_size)
        ch = get_char()

        if ch in ("q", "Q", "\x03"): # Quit or Ctrl+C
            sys.stdout.write("\033[2J\033[H")
            sys.stdout.flush()
            break
        elif ch in ("\x1b[A", "k"): # Arrow Up
            selected_idx = max(0, selected_idx - 1)
        elif ch in ("\x1b[B", "j"): # Arrow Down
            selected_idx = min(len(entries) - 1, selected_idx + 1)
        elif ch in ("h", "\x7f", "\b"): # Parent dir
            parent = os.path.dirname(current_dir)
            if parent and parent != current_dir:
                current_dir = parent
                entries = get_entries(current_dir)
                selected_idx = 0
                top_offset = 0
        elif ch in ("\r", "\n", "l"): # Open / Enter
            if entries and selected_idx < len(entries):
                target = entries[selected_idx]
                if target["is_dir"]:
                    current_dir = target["path"]
                    entries = get_entries(current_dir)
                    selected_idx = 0
                    top_offset = 0
                else:
                    # Open editor
                    editor = os.environ.get("EDITOR", "nano")
                    editor_path = shutil.which(editor) or shutil.which("nano") or shutil.which("vim")
                    if editor_path:
                        sys.stdout.write("\033[2J\033[H")
                        sys.stdout.flush()
                        subprocess.run([editor_path, target["path"]])
                        entries = get_entries(current_dir)
        elif ch in ("d", "D"): # Delete
            if entries and selected_idx < len(entries):
                target = entries[selected_idx]
                confirm = prompt_input(f"Delete '{target['name']}'? [y/N]")
                if confirm.lower() == "y":
                    try:
                        if target["is_dir"]:
                            shutil.rmtree(target["path"])
                        else:
                            os.remove(target["path"])
                    except Exception as e:
                        prompt_input(f"Error: {e} (press Enter)")
                    entries = get_entries(current_dir)
        elif ch in ("n", "N"): # New file
            name = prompt_input("New file name")
            if name:
                full = os.path.join(current_dir, name)
                try:
                    with open(full, "w") as f:
                        pass
                except Exception as e:
                    prompt_input(f"Error: {e} (press Enter)")
                entries = get_entries(current_dir)
        elif ch in ("f", "F"): # New folder
            name = prompt_input("New folder name")
            if name:
                full = os.path.join(current_dir, name)
                try:
                    os.makedirs(full, exist_ok=True)
                except Exception as e:
                    prompt_input(f"Error: {e} (press Enter)")
                entries = get_entries(current_dir)
        elif ch in ("m", "M"): # Rename / move
            if entries and selected_idx < len(entries):
                target = entries[selected_idx]
                new_name = prompt_input(f"Rename '{target['name']}' to")
                if new_name:
                    dest = os.path.join(current_dir, new_name)
                    try:
                        os.rename(target["path"], dest)
                    except Exception as e:
                        prompt_input(f"Error: {e} (press Enter)")
                    entries = get_entries(current_dir)

if __name__ == "__main__":
    main()

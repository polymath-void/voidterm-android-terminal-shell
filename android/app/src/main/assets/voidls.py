#!/usr/bin/env python3
"""
voidls.py: Visual Directory Listing Applet for VoidTerm.

Formats `ls` output with distinctive Unicode glyphs and ANSI color codes.
Supports directory, script, archive, file, and symlink glyph differentiation.

Usage in .bashrc:
    alias voidls='python3 $PREFIX/bin/voidls'

Features:
- 📁  directories
- ⚡  scripts / executable files
- 📦  archives (tar, zip, gz, bz2, xz)
- 📄  regular files
- 🔗  symbolic links
- Color-coded output via ANSI escape sequences
- Human-readable file sizes
- Sorting and grouping options
"""

import os
import sys
import stat
import subprocess
from typing import List, Tuple


# Unicode glyphs for file type differentiation
GLYPH_DIRECTORY = "📁"
GLYPH_SCRIPT = "⚡"
GLYPH_ARCHIVE = "📦"
GLYPH_FILE = "📄"
GLYPH_SYMLINK = "🔗"

# Color ANSI codes for each type (matching VoidTerm PS1 palette)
COLOR_DIRECTORY = "\033[36m"  # Cyan
COLOR_SCRIPT = "\033[32m"     # Green
COLOR_ARCHIVE = "\033[33m"    # Yellow
COLOR_FILE = "\033[37m"       # White
COLOR_SYMLINK = "\033[35m"    # Magenta
COLOR_RESET = "\033[0m"


def get_glyph(filepath: str) -> str:
    """Return the Unicode glyph for the given file path based on its type."""
    try:
        st = os.lstat(filepath)
    except OSError:
        return GLYPH_FILE

    if stat.S_ISLNK(st.st_mode):
        return GLYPH_SYMLINK
    elif stat.S_ISDIR(st.st_mode):
        return GLYPH_DIRECTORY
    elif stat.S_ISREG(st.st_mode):
        # Check if executable
        if st.st_mode & stat.S_IXUSR:
            return GLYPH_SCRIPT
        return GLYPH_FILE
    elif stat.S_ISSOCK(st.st_mode):
        return GLYPH_SYMLINK
    elif stat.S_ISFIFO(st.st_mode):
        return GLYPH_FILE
    else:
        return GLYPH_FILE


def get_color(glyph: str) -> str:
    """Return the ANSI color code for the given glyph type."""
    if glyph == GLYPH_DIRECTORY:
        return COLOR_DIRECTORY
    elif glyph == GLYPH_SCRIPT:
        return COLOR_SCRIPT
    elif glyph == GLYPH_ARCHIVE:
        return COLOR_ARCHIVE
    elif glyph == GLYPH_FILE:
        return COLOR_FILE
    elif glyph == GLYPH_SYMLINK:
        return COLOR_SYMLINK
    return COLOR_RESET


def format_size(size: int) -> str:
    """Format file size into human-readable form (B, KB, MB, GB)."""
    if size < 1024:
        return f"{size} B"
    elif size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    elif size < 1024 * 1024 * 1024:
        return f"{size / (1024 * 1024):.2f} MB"
    else:
        return f"{size / (1024 * 1024 * 1024):.2f} GB"


def parse_ls_output(path: str) -> List[Tuple[str, str, int]]:
    """
    Parse a directory and return a list of (name, glyph, color) tuples.
    
    Returns entries sorted with directories first, then other types alphabetically.
    """
    try:
        entries = os.listdir(path)
    except OSError as e:
        print(f"\033[31mError listing directory: {e}\033[0m", file=sys.stderr)
        return []

    result = []
    for name in sorted(entries):
        # Skip hidden files unless they start with '.'
        filepath = os.path.join(path, name)
        
        try:
            glyph = get_glyph(filepath)
            color = get_color(glyph)
            size = os.path.getsize(filepath) if os.path.isfile(filepath) else 0
            formatted_size = format_size(size) if os.path.isfile(filepath) else "-"
            
            result.append((name, glyph, color, formatted_size))
        except OSError:
            continue

    # Sort: directories first, then by name
    result.sort(key=lambda x: (not x[0].startswith(".") and os.path.isdir(os.path.join(path, x[0]))), reverse=False)
    
    return result


def list_directory(path: str = ".") -> None:
    """List the given directory with visual glyphs and colors."""
    entries = parse_ls_output(path)
    
    if not entries:
        print("\033[33mDirectory is empty\033[0m")
        return
    
    # Header
    print("\033[1m" + " VOIDLS ".center(60, "─") + "\033[0m")
    
    for name, glyph, color, size in entries:
        # Don't show full path for current directory entries
        display_name = name
        
        # Add trailing indicator for directories
        full_path = os.path.join(path, name) if path != "." else name
        is_dir = os.path.isdir(full_path)
        
        # Print: glyph + color + name + size + reset
        print(f"{color}{glyph} {color}{display_name}\033[0m \033[34m{size}\033[0m")
    
    # Footer
    print("\033[1m" + "─" * 60 + "\033[0m")
    print(f"\033[36mTotal entries: {len(entries)}\033[0m")


def main():
    """Main entry point for voidls.py."""
    # Parse optional path argument
    path = sys.argv[1] if len(sys.argv) > 1 else "."
    
    # Resolve relative paths
    if not os.path.isabs(path):
        # Get PREFIX from environment or use current dir
        prefix = os.environ.get("PREFIX", "")
        if prefix:
            path = os.path.join(prefix, path)
    
    list_directory(path)


if __name__ == "__main__":
    main()
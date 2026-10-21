"""
VoidTerm Package Manager Extension - Integrations & Hooks
"""

import os
import sys

def on_register():
    print("[VoidExt:pkg] Registered into NodeOS runtime.")

def on_cron():
    # Periodic check for package updates
    prefix = os.environ.get("PREFIX", "/data/user/0/com.hybridengine.terminal/files/usr")
    voidpkg = os.path.join(prefix, "bin", "voidpkg.py")
    if os.path.exists(voidpkg):
        os.system(f"python3 {voidpkg} update > /dev/null 2>&1")

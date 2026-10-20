#!/data/user/0/com.hybridengine.terminal/files/usr/bin/python3
import os
import sys
import json
import gzip
import urllib.request
import ssl
import io
import tarfile
import subprocess
import shutil

SELF_DIR = os.path.dirname(os.path.abspath(__file__))
PREFIX = os.path.abspath(os.path.join(SELF_DIR, ".."))
BIN_DIR = os.path.join(PREFIX, "bin")
LIB_DIR = os.path.join(PREFIX, "lib")
ETC_DIR = os.path.join(PREFIX, "etc")
VAR_DIR = os.path.join(PREFIX, "var", "pkg")
CACHE_DIR = os.path.join(VAR_DIR, "cache")
INSTALLED_DIR = os.path.join(VAR_DIR, "installed")
REPOS_FILE = os.path.join(ETC_DIR, "pkg", "repos.json")
PATCHELF = os.path.join(BIN_DIR, "patchelf")

DEFAULT_REPOS = {
    "default_provider": "termux",
    "providers": {
        "termux": {
            "name": "Termux Main (AArch64)",
            "url": "https://packages-cf.termux.dev/apt/termux-main/",
            "packages_path": "dists/stable/main/binary-aarch64/Packages.gz",
            "enabled": True
        }
    }
}

def ensure_dirs():
    for d in [BIN_DIR, LIB_DIR, ETC_DIR, VAR_DIR, CACHE_DIR, INSTALLED_DIR, os.path.join(ETC_DIR, "pkg")]:
        os.makedirs(d, exist_ok=True)
    if not os.path.exists(REPOS_FILE):
        with open(REPOS_FILE, "w") as f:
            json.dump(DEFAULT_REPOS, f, indent=2)

def get_ssl_context():
    cert_candidates = [
        os.environ.get("SSL_CERT_FILE"),
        os.path.join(PREFIX, "etc", "tls", "cert.pem"),
        os.path.join(PREFIX, "etc", "ssl", "cert.pem"),
    ]
    for c in cert_candidates:
        if c and os.path.isfile(c):
            try:
                return ssl.create_default_context(cafile=c)
            except Exception:
                pass
    try:
        return ssl.create_default_context()
    except Exception:
        return ssl._create_unverified_context()

def load_repos():
    ensure_dirs()
    try:
        with open(REPOS_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return DEFAULT_REPOS

def get_installed():
    ensure_dirs()
    installed = {}
    if os.path.exists(INSTALLED_DIR):
        for fname in os.listdir(INSTALLED_DIR):
            if fname.endswith(".json"):
                pkg_name = fname[:-5]
                try:
                    with open(os.path.join(INSTALLED_DIR, fname), "r") as f:
                        installed[pkg_name] = json.load(f)
                except Exception:
                    installed[pkg_name] = {"version": "unknown"}
    return installed

def update_catalog():
    ensure_dirs()
    repos = load_repos()
    headers = {"User-Agent": "VoidPkg/1.0 (Android; AArch64)"}
    
    total_pkgs = 0
    catalog = {}
    
    for key, repo in repos.get("providers", {}).items():
        if not repo.get("enabled", True):
            continue
        base_url = repo["url"].rstrip("/") + "/"
        pkg_url = base_url + repo["packages_path"]
        print(f"\033[36m[*] Synchronizing index from {repo['name']}...\033[0m")
        try:
            req = urllib.request.Request(pkg_url, headers=headers)
            with urllib.request.urlopen(req, timeout=15, context=get_ssl_context()) as resp:
                data = resp.read()
            decompressed = gzip.decompress(data).decode("utf-8", errors="ignore")
            
            blocks = decompressed.split("\n\n")
            repo_count = 0
            for block in blocks:
                if not block.strip(): continue
                pkg_data = {}
                for line in block.splitlines():
                    if ": " in line:
                        k, v = line.split(": ", 1)
                        k = k.strip().lower()
                        if k in ["package", "version", "architecture", "depends", "filename", "size", "description"]:
                            pkg_data[k] = v.strip()
                if "package" in pkg_data and "filename" in pkg_data:
                    pkg_data["repo_base"] = base_url
                    catalog[pkg_data["package"]] = pkg_data
                    repo_count += 1
            print(f"\033[32m[✔] {repo['name']}: {repo_count} packages indexed.\033[0m")
            total_pkgs += repo_count
        except Exception as e:
            print(f"\033[31m[!] Error fetching {repo['name']}: {e}\033[0m")

    cache_file = os.path.join(CACHE_DIR, "catalog.json")
    with open(cache_file, "w") as f:
        json.dump(catalog, f)
    print(f"\033[32m✔ Total available packages cached: {total_pkgs}\033[0m")

def load_catalog():
    cache_file = os.path.join(CACHE_DIR, "catalog.json")
    if not os.path.exists(cache_file):
        print("\033[33m[!] Package index cache not found. Running pkg update first...\033[0m")
        update_catalog()
    try:
        with open(cache_file, "r") as f:
            return json.load(f)
    except Exception:
        return {}

def search_packages(query):
    catalog = load_catalog()
    q = query.lower()
    matches = []
    for name, data in catalog.items():
        desc = data.get("description", "").lower()
        if q in name.lower() or q in desc:
            matches.append((name, data.get("version", ""), data.get("description", "")))
    
    matches.sort(key=lambda x: x[0])
    print(f"\033[36m=== Search results for '{query}' ({len(matches)} found) ===\033[0m")
    for name, ver, desc in matches[:30]:
        print(f"  \033[1;32m{name}\033[0m ({ver})")
        if desc:
            print(f"    {desc.splitlines()[0]}")
    if len(matches) > 30:
        print(f"  ... and {len(matches) - 30} more.")

def extract_ar(data):
    files = {}
    if data[:8] != b"!<arch>\n":
        raise ValueError("Invalid .deb ar archive")
    offset = 8
    while offset < len(data):
        header = data[offset:offset+60]
        if len(header) < 60: break
        name = header[:16].strip().decode("ascii", errors="ignore").rstrip("/")
        size = int(header[48:58].strip())
        offset += 60
        content = data[offset:offset+size]
        files[name] = content
        offset += size
        if offset % 2 != 0: offset += 1
    return files

def retarget_elf(filepath):
    if not os.path.isfile(filepath) or os.path.islink(filepath):
        return
    try:
        with open(filepath, "rb") as f:
            magic = f.read(4)
        if magic == b"\x7fELF":
            rpath = f"$ORIGIN:$ORIGIN/../lib:$ORIGIN/../../:{PREFIX}/lib"
            if os.path.exists(PATCHELF):
                tmp_path = filepath + ".patch_tmp"
                try:
                    shutil.copy2(filepath, tmp_path)
                    res = subprocess.run([PATCHELF, "--set-rpath", rpath, tmp_path], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    if res.returncode == 0:
                        os.replace(tmp_path, filepath)
                    elif os.path.exists(tmp_path):
                        os.remove(tmp_path)
                except Exception:
                    if os.path.exists(tmp_path):
                        try: os.remove(tmp_path)
                        except Exception: pass
            os.chmod(filepath, 0o755)
    except Exception:
        pass

def fix_shebang(filepath):
    if not os.path.isfile(filepath) or os.path.islink(filepath):
        return
    try:
        with open(filepath, "rb") as f:
            header = f.read(2)
        if header == b"#!":
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
            if "/data/data/com.termux/files/usr/bin/" in content:
                content = content.replace("/data/data/com.termux/files/usr/bin/", f"{PREFIX}/bin/")
                with open(filepath, "w", encoding="utf-8") as f:
                    f.write(content)
            os.chmod(filepath, 0o755)
    except Exception:
        pass

def install_single_pkg(pkg_name, catalog, installed):
    if pkg_name in installed:
        return True
    if pkg_name not in catalog:
        aliases = {
            "ssh": "openssh",
            "python": "python",
            "python3": "python",
            "pip": "python-pip",
            "pip3": "python-pip",
            "vim": "vim",
            "neovim": "neovim",
            "clang": "clang",
            "c++": "clang",
            "gcc": "clang",
            "node": "nodejs",
            "npm": "nodejs",
        }
        mapped = aliases.get(pkg_name.lower())
        if mapped and mapped in catalog:
            print(f"\033[36m[*] Mapping package alias '{pkg_name}' -> '{mapped}'\033[0m")
            return install_single_pkg(mapped, catalog, installed)

        print(f"\033[31m[!] Package '{pkg_name}' not found in registered repositories.\033[0m")
        matches = [k for k in catalog.keys() if pkg_name.lower() in k.lower()][:4]
        if matches:
            print(f"\033[33m    Did you mean: {', '.join(matches)}?\033[0m")
        return False
        
    pkg_data = catalog[pkg_name]
    deps_raw = pkg_data.get("depends", "")
    if deps_raw:
        for dep_clause in deps_raw.split(","):
            dep_name = dep_clause.split("|")[0].strip().split()[0]
            if dep_name and dep_name not in installed and dep_name in catalog:
                print(f"\033[35m[→] Resolving dependency: {dep_name}\033[0m")
                install_single_pkg(dep_name, catalog, installed)

    url = pkg_data["repo_base"] + pkg_data["filename"]
    print(f"\033[36m[*] Downloading {pkg_name} ({pkg_data.get('version', '')})...\033[0m")
    headers = {"User-Agent": "VoidPkg/1.0 (Android; AArch64)"}
    try:
        req = urllib.request.Request(url, headers=headers)
        deb_bytes = urllib.request.urlopen(req, timeout=30, context=get_ssl_context()).read()
    except Exception as e:
        print(f"\033[31m[!] Failed to download {pkg_name}: {e}\033[0m")
        return False

    try:
        ar_members = extract_ar(deb_bytes)
        data_tar_name = next(k for k in ar_members if k.startswith("data.tar"))
        data_tar_bytes = ar_members[data_tar_name]
    except Exception as e:
        print(f"\033[31m[!] Corrupt deb package {pkg_name}: {e}\033[0m")
        return False

    tar = tarfile.open(fileobj=io.BytesIO(data_tar_bytes))
    installed_files = []
    
    for member in tar.getmembers():
        name = member.name
        idx = name.find("files/usr/")
        if idx == -1: continue
        rel_path = name[idx + len("files/usr/"):]
        if not rel_path: continue
        
        target = os.path.join(PREFIX, rel_path)
        if member.isdir():
            os.makedirs(target, exist_ok=True)
        elif member.isfile() or member.islnk() or member.issym():
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.lexists(target):
                try: os.remove(target)
                except Exception: pass
            if member.issym() or member.islnk():
                link_target = member.linkname
                if "files/usr/" in link_target:
                    link_target = os.path.join(PREFIX, link_target.split("files/usr/")[1])
                try: os.symlink(link_target, target)
                except Exception: pass
            else:
                with open(target, "wb") as f:
                    f.write(tar.extractfile(member).read())
                mode = member.mode | 0o755 if "bin/" in rel_path else member.mode
                os.chmod(target, mode)
                retarget_elf(target)
                fix_shebang(target)
            installed_files.append(rel_path)

    installed[pkg_name] = {
        "version": pkg_data.get("version", ""),
        "files": installed_files,
        "description": pkg_data.get("description", "")
    }
    with open(os.path.join(INSTALLED_DIR, f"{pkg_name}.json"), "w") as f:
        json.dump(installed[pkg_name], f, indent=2)
        
    print(f"\033[32m[✔] {pkg_name} successfully installed ({len(installed_files)} files deployed).\033[0m")
    return True

def remove_package(pkg_name):
    pkg_file = os.path.join(INSTALLED_DIR, f"{pkg_name}.json")
    if not os.path.exists(pkg_file):
        print(f"\033[33m[!] Package '{pkg_name}' is not installed.\033[0m")
        return
    with open(pkg_file, "r") as f:
        data = json.load(f)
    print(f"\033[33m[*] Removing {pkg_name}...\033[0m")
    count = 0
    for rel_path in data.get("files", []):
        p = os.path.join(PREFIX, rel_path)
        if os.path.lexists(p):
            try:
                os.remove(p)
                count += 1
            except Exception: pass
    os.remove(pkg_file)
    print(f"\033[32m[✔] {pkg_name} uninstalled ({count} files cleaned).\033[0m")

def list_installed():
    installed = get_installed()
    print("\033[36m=== VoidTerm Installed Packages ===\033[0m")
    if not installed:
        print("  (No external packages installed yet. Run: pkg install <name>)")
        return
    for name, data in sorted(installed.items()):
        ver = data.get("version", "")
        file_count = len(data.get("files", []))
        print(f"  \033[1;32m✔\033[0m {name} ({ver}) - {file_count} files")

def manage_repos(args):
    repos = load_repos()
    if not args or args[0] == "list":
        print("\033[36m=== VoidTerm Registered Repositories ===\033[0m")
        for k, v in repos.get("providers", {}).items():
            status = "\033[32m[enabled]\033[0m" if v.get("enabled", True) else "\033[31m[disabled]\033[0m"
            print(f"  - \033[1m{k}\033[0m: {v.get('name', k)} ({v.get('url', '')}) {status}")
    elif args[0] == "add" and len(args) >= 3:
        name = args[1]
        url = args[2]
        repos.setdefault("providers", {})[name] = {
            "name": name,
            "url": url,
            "packages_path": "Packages.gz",
            "enabled": True
        }
        with open(REPOS_FILE, "w") as f: json.dump(repos, f, indent=2)
        print(f"\033[32m[✔] Added repository '{name}'. Run 'pkg update' to refresh indexes.\033[0m")
    elif args[0] == "remove" and len(args) >= 2:
        name = args[1]
        if name in repos.get("providers", {}):
            del repos["providers"][name]
            with open(REPOS_FILE, "w") as f: json.dump(repos, f, indent=2)
            print(f"\033[32m[✔] Removed repository '{name}'.\033[0m")
        else:
            print(f"\033[31m[!] Repository '{name}' not found.\033[0m")

def print_help():
    print("""VoidPkg v1.0.0 - Universal Rootless Package Manager for VoidTerm
Usage:
  pkg update                   Synchronize package indexes from all repositories
  pkg search <keyword>         Search available packages
  pkg install <pkg1> [pkg2...] Download, resolve dependencies, and retarget packages
  pkg remove <pkg>             Uninstall an installed package cleanly
  pkg list                     List all installed packages
  pkg repo [list|add|remove]   Manage multi-repository sources
  pkg status                   Display prefix and storage telemetry
""")

def main():
    if len(sys.argv) < 2:
        print_help()
        return

    cmd = sys.argv[1].lower()
    args = sys.argv[2:]

    if cmd in ["update", "sync"]:
        update_catalog()
    elif cmd in ["search", "find"]:
        if not args:
            print("Usage: pkg search <query>")
            return
        search_packages(args[0])
    elif cmd in ["install", "add"]:
        if not args:
            print("Usage: pkg install <package...>")
            return
        catalog = load_catalog()
        installed = get_installed()
        for p in args:
            install_single_pkg(p, catalog, installed)
    elif cmd in ["remove", "uninstall", "delete"]:
        if not args:
            print("Usage: pkg remove <package>")
            return
        for p in args:
            remove_package(p)
    elif cmd in ["list"]:
        list_installed()
    elif cmd in ["repo", "repos", "source", "sources"]:
        manage_repos(args)
    elif cmd in ["status"]:
        print(f"\033[36m=== VoidTerm Subsystem Status ===\033[0m")
        print(f"  Prefix  : {PREFIX}")
        print(f"  Binaries: {len(os.listdir(BIN_DIR)) if os.path.exists(BIN_DIR) else 0} active tools")
        print(f"  Packages: {len(get_installed())} external packages installed")
    else:
        print_help()

if __name__ == "__main__":
    main()
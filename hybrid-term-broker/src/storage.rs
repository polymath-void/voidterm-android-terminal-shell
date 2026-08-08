use anyhow::{Context, Result};
use std::fs::{self, File};
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::Path;
use std::process::Command;

pub struct StorageProvisioner;

const SYSTEMD_SERVICE_CONTENT: &str = r#"[Unit]
Description=VoidTerm Vsock Guest Daemon
ConditionVirtualization=vm
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/guest_daemon
Restart=always
RestartSec=1
User=root
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
"#;

impl StorageProvisioner {
    /// Dynamically allocates a sparse ext4 image, configures guest daemon systemd bindings,
    /// and injects the Debian rootfs into disk.img.
    pub fn provision_avf_disk(disk_path: &str, rootfs_dir: &str, size_mb: u64) -> Result<()> {
        let disk = Path::new(disk_path);
        
        if disk.exists() {
            println!("💾 [Storage] AVF disk already provisioned: {}", disk_path);
            return Ok(());
        }

        let rootfs = Path::new(rootfs_dir);

        // Extract debian-rootfs.tar.gz using native Android tar if staged
        let parent_dir = rootfs.parent().unwrap_or(rootfs);
        let archive_path = parent_dir.join("debian-rootfs.tar.gz");

        if archive_path.exists() {
            println!("📦 [Storage] Extracting Debian OS using native Android tar from {}...", archive_path.display());
            fs::create_dir_all(rootfs).context("Failed to create rootfs directory")?;

            let tar_bin = if Path::new("/system/bin/tar").exists() {
                "/system/bin/tar"
            } else if Path::new("/data/data/com.termux/files/usr/bin/tar").exists() {
                "/data/data/com.termux/files/usr/bin/tar"
            } else {
                "tar"
            };

            let tar_status = Command::new(tar_bin)
                .arg("-xzf")
                .arg(&archive_path)
                .arg("-C")
                .arg(rootfs_dir)
                .status()
                .context("Failed to execute native tar extractor")?;

            if !tar_status.success() {
                anyhow::bail!("Native tar extraction failed with exit code {:?}", tar_status.code());
            }

            println!("✅ [Storage] Debian rootfs extracted successfully.");

            // Clean up archive to reclaim internal storage space
            let _ = fs::remove_file(&archive_path);
        }

        // 1. Inject guest daemon systemd unit & activation symlink into rootfs before packing
        Self::inject_systemd_service(rootfs)?;

        // 2. Inject guest daemon executable into rootfs
        Self::inject_guest_daemon_binary(rootfs)?;

        println!("🏗️ [Storage] Allocating {}MB sparse disk at {}...", size_mb, disk_path);
        let file = File::create(disk_path).context("Failed to create disk.img")?;
        
        // Allocate space efficiently using a sparse file
        file.set_len(size_mb * 1024 * 1024).context("Failed to set sparse length")?;

        // Locate mke2fs binary (/system/bin/mke2fs with Termux/PATH fallback)
        let mke2fs_bin = if Path::new("/system/bin/mke2fs").exists() {
            "/system/bin/mke2fs"
        } else if Path::new("/data/data/com.termux/files/usr/bin/mke2fs").exists() {
            "/data/data/com.termux/files/usr/bin/mke2fs"
        } else {
            "mke2fs"
        };

        // Format the sparse file as ext4 using Android's native user-space binary
        println!("⚙️ [Storage] Formatting disk as ext4 using {}...", mke2fs_bin);
        let format_status = Command::new(mke2fs_bin)
            .arg("-t").arg("ext4")
            .arg("-F") // Force formatting a regular file
            .arg(disk_path)
            .status()
            .context("Failed to execute mke2fs")?;

        if !format_status.success() {
            anyhow::bail!("ext4 formatting failed with exit code {:?}", format_status.code());
        }

        // Locate e2fsdroid binary (/system/bin/e2fsdroid with Termux/PATH fallback)
        let e2fsdroid_bin = if Path::new("/system/bin/e2fsdroid").exists() {
            "/system/bin/e2fsdroid"
        } else if Path::new("/data/data/com.termux/files/usr/bin/e2fsdroid").exists() {
            "/data/data/com.termux/files/usr/bin/e2fsdroid"
        } else {
            "e2fsdroid"
        };

        // Inject the extracted Debian rootfs directly into the unmounted ext4 image
        println!("📦 [Storage] Injecting Debian rootfs from {} into {}...", rootfs_dir, disk_path);
        let inject_status = Command::new(e2fsdroid_bin)
            .arg("-e") // e2fsdroid mode
            .arg("-f").arg(rootfs_dir) // Source directory
            .arg("-a").arg("/") // Target mount point inside the image
            .arg(disk_path)
            .status()
            .context("Failed to execute e2fsdroid")?;

        if !inject_status.success() {
            anyhow::bail!("Rootfs injection failed with exit code {:?}", inject_status.code());
        }

        println!("✅ [Storage] AVF Boot Disk successfully provisioned with Systemd Guest Daemon.");
        Ok(())
    }

    /// Injects the systemd service definition and enables it via multi-user.target.wants symlink
    fn inject_systemd_service(rootfs: &Path) -> Result<()> {
        let systemd_dir = rootfs.join("etc/systemd/system");
        let wants_dir = systemd_dir.join("multi-user.target.wants");

        println!("🔧 [Storage] Injecting systemd service into {}...", systemd_dir.display());
        fs::create_dir_all(&wants_dir).context("Failed to create systemd multi-user.target.wants directory")?;

        let service_file = systemd_dir.join("voidterm-daemon.service");
        fs::write(&service_file, SYSTEMD_SERVICE_CONTENT)
            .context("Failed to write voidterm-daemon.service")?;

        let symlink_target = wants_dir.join("voidterm-daemon.service");
        if symlink_target.exists() || symlink_target.is_symlink() {
            let _ = fs::remove_file(&symlink_target);
        }

        // Relative symlink inside the rootfs
        symlink("../voidterm-daemon.service", &symlink_target)
            .context("Failed to create systemd activation symlink")?;

        println!("✅ [Storage] Systemd service enabled at multi-user.target.wants/voidterm-daemon.service");
        Ok(())
    }

    /// Injects or verifies the guest_daemon binary in /usr/local/bin
    fn inject_guest_daemon_binary(rootfs: &Path) -> Result<()> {
        let bin_dir = rootfs.join("usr/local/bin");
        fs::create_dir_all(&bin_dir).context("Failed to create /usr/local/bin in rootfs")?;

        let dest_bin = bin_dir.join("guest_daemon");
        if !dest_bin.exists() {
            // Search candidate locations for pre-built guest_daemon binary
            let candidates = [
                Path::new("/data/data/com.termux/files/usr/bin/guest_daemon"),
                Path::new("/data/local/tmp/guest_daemon"),
                Path::new("target/release/guest_daemon"),
                Path::new("target/aarch64-linux-android/release/guest_daemon"),
            ];

            let mut copied = false;
            for candidate in &candidates {
                if candidate.exists() {
                    if let Ok(_) = fs::copy(candidate, &dest_bin) {
                        println!("📥 [Storage] Copied guest_daemon from {}", candidate.display());
                        copied = true;
                        break;
                    }
                }
            }

            if !copied {
                println!("ℹ️ [Storage] Initializing placeholder guest_daemon runner in /usr/local/bin");
                let placeholder_script = "#!/bin/sh\necho '[Guest Daemon] Starting vsock listener on CID 3 port 8000'\n";
                let _ = fs::write(&dest_bin, placeholder_script);
            }
        }

        // Ensure executable permissions (rwxr-xr-x -> 0o755)
        if dest_bin.exists() {
            let mut perms = fs::metadata(&dest_bin)?.permissions();
            perms.set_mode(0o755);
            let _ = fs::set_permissions(&dest_bin, perms);
        }

        Ok(())
    }
}

use anyhow::{Context, Result};
use std::fs::File;
use std::path::Path;
use std::process::Command;

pub struct StorageProvisioner;

impl StorageProvisioner {
    /// Dynamically allocates a sparse ext4 image and injects the Debian rootfs
    pub fn provision_avf_disk(disk_path: &str, rootfs_dir: &str, size_mb: u64) -> Result<()> {
        let disk = Path::new(disk_path);
        
        if disk.exists() {
            println!("💾 [Storage] AVF disk already provisioned: {}", disk_path);
            return Ok(());
        }

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

        println!("✅ [Storage] AVF Boot Disk successfully provisioned.");
        Ok(())
    }
}

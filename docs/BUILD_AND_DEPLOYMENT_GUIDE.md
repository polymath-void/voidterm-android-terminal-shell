# VoidTerm: Production Build & Deployment Guide
## Step-by-Step Instructions for Compiling, Verifying, and Packaging VoidTerm

---

## 1. Prerequisites & Toolchain Setup

To build VoidTerm from source, ensure the following tools are installed:

- **Rust & Cargo**: Version $\ge 1.75$ (`rustup target add aarch64-linux-android`)
- **Android NDK**: Version $\ge 25\text{c}$ (API level 34+)
- **Java Development Kit**: JDK 17 or JDK 21
- **Gradle**: 8.4+ (or bundled Gradle wrapper)
- **Python**: 3.10+ (for `void` CLI and test clients)

---

## 2. Compiling the Rust Core Broker (`hybrid-term-broker`)

The core daemon compiles both as a native Android shared library (`.so`) for JNI integration and as a standalone headless binary.

### A. Build Standalone Daemon (for local testing & CI):
```bash
cd hybrid-term-broker
cargo build --release --bin hybrid-term-broker
```
The compiled binary will be located at:
`target/release/hybrid-term-broker`

### B. Cross-Compile Shared Library for Android JNI (`arm64-v8a`):
```bash
cargo build --release --target aarch64-linux-android --lib

# Copy the compiled .so to the Android app jniLibs directory:
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libhybrid_term_broker.so android/app/src/main/jniLibs/arm64-v8a/
```

---

## 3. Running End-to-End Agent Verification Tests

Before packaging the Android APK, execute the end-to-end Agent Control Plane (ACP) integration test:

```bash
python3 test_acp_client.py
```

Expected output:
```text
[+] Broker online. Connecting to ACP socket: /tmp/voidterm_agent.sock
--- Test 1: Ping --- [✔]
--- Test 2: Create Session --- [✔]
--- Test 3: Exec Command in PTY --- [✔]
--- Test 4: Read PTY Snapshot --- [✔]
--- Test 5: Broadcast HUD Telemetry --- [✔]
--- Test 6: List Sessions --- [✔]
--- Test 7: Close Session --- [✔]
[✔] ALL 7 AGENT CONTROL PLANE TESTS PASSED PERFECTLY!
```

### 3.1 CLI Control via `void agent`

You can also interact with the Agent Control Plane directly using the shortform `void` CLI:

```bash
# Check ACP socket status
void agent ping

# List active sessions
void agent list

# Create a dedicated agent session
void agent create worker-1 --tier local --workdir ~/Projects

# Dispatch command and inspect circular scrollback buffer
void agent exec "cargo check" --session worker-1
void agent snapshot --session worker-1 --lines 50

# Broadcast real-time agent HUD telemetry directly to the Android UI
void agent hud "Refactoring system core" --agent DeepCoder --status working --file lib.rs --progress 0.85

# Close session
void agent close worker-1
```

---

## 4. Building the Android Application APK

From the project root directory:

```bash
cd android

# Build Debug APK
./gradlew assembleDebug

# Build Signed Release APK
./gradlew assembleRelease
```

The output APK will be placed in:
`android/app/build/outputs/apk/release/app-release.apk`

---

## 5. Google Play Store Release Checklist

1. **Target SDK Level**: Verify that `compileSdk` and `targetSdk` in [`android/app/build.gradle.kts`](file:///data/data/com.termux/files/home/Projects/polymath/polymath-void-term/hybrid-engine/android/app/build.gradle.kts) are set to **34** or higher.
2. **App Permissions**: Verify `AndroidManifest.xml` requests only required capabilities:
   - `android.permission.FOREGROUND_SERVICE`
   - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
   - `android.permission.WAKE_LOCK`
3. **W^X Policy Compliance**: Confirm that no executable binaries are extracted to or launched from `/data/data/.../files/`. All host native executables must be mapped via `libhybrid_term_broker.so` or executed within the AVF microVM (`crosvm`).
4. **App Bundle (.aab)**: For Play Store submission, build the Android App Bundle:
   ```bash
   ./gradlew bundleRelease
   ```

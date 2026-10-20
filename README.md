# Bridge 🌉

[![Android CI](https://github.com/rajanraj2/bridge/actions/workflows/android.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/android.yml)
[![macOS CI](https://github.com/rajanraj2/bridge/actions/workflows/macos.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/macos.yml)
[![Protocol CI](https://github.com/rajanraj2/bridge/actions/workflows/protocol.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/protocol.yml)
[![Release](https://img.shields.io/github/v/release/rajanraj2/bridge?color=blue)](https://github.com/rajanraj2/bridge/releases/latest)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Bridge** is an open-source, local-first continuity application connecting your Android phone and Mac seamlessly.

- 📋 **Zero-Touch Clipboard Sync**: Copy on Android or Mac; paste instantly on the other without opening any app or dealing with keyboard dismissals.
- 🔔 **Notification Mirroring & Quick Reply**: View Android notifications in macOS and reply directly.
- 📁 **File & Link Sharing**: Stream large files with integrity verification and open links immediately.
- 🔒 **100% Private & Local**: End-to-end TLS 1.3 over local Wi-Fi or Tailscale. No cloud servers, accounts, or telemetry.

---

## Installation

### macOS (macOS 13.0 Ventura or later)

Choose your preferred installation method:

#### 1. Homebrew Cask (Recommended)
```bash
brew install --cask rajanraj2/tap/bridge
```
*To update later:* `brew upgrade --cask bridge`

#### 2. One-Line Terminal Installer
Installs the universal binary (`arm64` + `x86_64`) into `/Applications`:
```bash
curl -fsSL https://raw.githubusercontent.com/rajanraj2/bridge/main/scripts/install_mac.sh | bash
```

#### 3. Drag-and-Drop Disk Image (`.dmg`)
1. Download **[Bridge-v0.1.12.dmg](https://github.com/rajanraj2/bridge/releases/latest)**.
2. Open the disk image and drag **Bridge.app** into `/Applications`.

#### 4. Apple Installer Package (`.pkg`)
Download and run **[Bridge-v0.1.12.pkg](https://github.com/rajanraj2/bridge/releases/latest)** to install via macOS's native setup wizard.

---

### Android (Android 11 / API 30 or later)

1. Download **[Bridge-v0.1.12.apk](https://github.com/rajanraj2/bridge/releases/latest)** on your phone and tap to install.
2. *Or install via ADB from your computer:*
   ```bash
   adb install -r Bridge-v0.1.12.apk
   ```

---

## Quick Start: Pairing in 30 Seconds

1. **Open Bridge on macOS**: The app runs unobtrusively in your Menu Bar (`Bridge`).
2. **Open Bridge on Android**: Grant the requested permissions:
   - **Notification Access**: For notification mirroring and quick replies.
   - **Accessibility Service**: Enables silent background clipboard synchronization.
   - **Battery Optimization Exemption**: Keeps background connectivity alive.
3. **Connect & Pair**:
   - Both devices discover each other automatically on local Wi-Fi.
   - For remote connectivity outside your local network, enter your phone or Mac's Tailscale IP / MagicDNS.
   - Scan the QR code or tap connect, verify the 6-digit Short Authentication String (SAS), and you're paired!

---

## Contributing & PR Submission Process

We welcome bug fixes, improvements, and features! To ensure stability, all changes go through a pull request workflow with automated CI validation:

```bash
# 1. Clone repository and create a feature branch (do NOT push directly to main)
git clone git@github.com:rajanraj2/bridge.git
cd bridge
git checkout -b feature/your-feature-name

# 2. Make your changes and verify builds/tests pass locally
cd macos && swift test && cd ..
cd android && ./gradlew testDebugUnitTest && cd ..

# 3. Commit your changes (following Conventional Commits)
git commit -am "fix: description of fix"

# 4. Push your branch
git push -u origin feature/your-feature-name

# 5. Open a Pull Request using GitHub CLI (or the GitHub web UI)
gh pr create \
  --title "fix: description of fix" \
  --body "Summary of changes and verification" \
  --base main \
  --head feature/your-feature-name

# 6. Add a release label (release:patch, release:minor, or release:major)
gh pr edit <PR_NUMBER> --add-label "release:patch"
```
Once all CI checks (Android, macOS, and Protocol) pass and the PR is merged into `main`, GitHub Actions automatically compiles, tests, packages, tags, and publishes a new GitHub Release with universal binaries and updates the Homebrew tap.

---

## For Developers

Bridge is built from the ground up as a 100% clean-room, modular architecture:

- **macOS Client**: Native Swift 5.10 / SwiftUI MenuBarExtra application (`macos/`).
- **Android Client**: Native Kotlin with Jetpack Compose (`android/`).
- **Protocol Contract**: Length-delimited Protocol Buffers (`bridge.v1`) schemas (`protocol/`).
- **Transport Security**: Direct TCP over mutual TLS 1.3 with hardware-backed Ed25519 identity pinning.

### Local Development Commands

```bash
# Test & Build macOS
cd macos
swift build
swift test

# Test & Build Android
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest

# Package Release Artifacts
./scripts/package_macos.sh    # Builds Universal DMG, PKG, and ZIP in dist/
./scripts/package_android.sh  # Builds aligned, signed APK in dist/
```

### Architecture & Detailed Design
For a comprehensive breakdown of the internal design, component flows, call graphs, and file-by-file explanations, refer to:
- **[Architecture & Flow Deep Dive (Interactive HTML)](docs/ARCHITECTURE_AND_FLOW_GUIDE.html)**
- **[Architecture & Flow Guide (Markdown)](docs/ARCHITECTURE_AND_FLOW_GUIDE.md)**
- **[Protocol Specification](docs/PROTOCOL.md)**
- **[Architecture Decision Records (ADRs)](docs/adr/)**

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

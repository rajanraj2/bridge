# Changelog

All notable changes to Bridge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---
## [0.1.16] - 2026-10-21

### Added
- **Multi-Device Mesh Topology & Multi-Peer Connection Pool**:
  - Upgraded macOS and Android socket servers to maintain concurrent multi-peer connection pools instead of single connection slots.
  - Added mesh routing metadata (`target_device_id`, `visited_device_ids`) to `Envelope` schema.
  - Implemented automatic mesh relaying so clipboard updates broadcast across all connected peers (Android, multiple Macs, and future iPad client).
  - Eliminated echo loops and rebroadcast storms via `visited_device_ids` hop tracking and SHA-256 content deduplication.

---
## [0.1.15] - 2026-10-21

### Added
- **Observability & Download Analytics**:
  - Added real-time GitHub Releases download counter badge to `README.md`.
  - Added `scripts/collect_release_metrics.py` reporting cumulative and per-asset downloads for APK, DMG, PKG, and ZIP packages.
  - Implemented privacy-preserving `TelemetryManager` on macOS and Android to record installation lifecycle metrics without PII.

---
## [0.1.14] - 2026-10-21

### Added
- **Unified Brand Logo & Cross-Platform Iconography**:
  - Designed the official Bridge vector brand icon representing seamless continuity between Android and Mac devices.
  - Added Android Adaptive Icons (`ic_launcher.xml`, `ic_launcher_round.xml`) and density mipmaps (`mdpi` through `xxxhdpi`).
  - Packaged macOS multi-resolution `AppIcon.icns` (16x16 to 1024x1024) into `Bridge.app` bundle and linked via `CFBundleIconFile`.
  - Added official brand mark to `README.md`.

---
## [0.1.13] - 2026-10-21

### Added
- **Android Explicit Permission Setup & Onboarding**:
  - Introduced a dedicated "App Setup & Permissions" card with clear descriptions and deep-links for each capability: Notification Mirroring, Accessibility Background Sync, Unrestricted Battery, and Foreground Notifications.
  - Real-time permission tracking on activity resume with green checkmarks when enabled.
- **UI Decluttering & Progressive Disclosure**:
  - Collapsed technical identifiers (Device ID, Local IP, Fingerprint, Diagnostic JSON dumps, and internal system checks) into an expandable "Advanced Technical Details & Logs" drawer on both Android and macOS.
  - Main dashboard and menu bar views now focus strictly on connection status and active continuity toggles.

---
## [0.1.12] - 2026-10-21

### Fixed
- **Android Soft Keyboard Flicker Prevention**:
  - Configured focus window with `SOFT_INPUT_STATE_UNCHANGED` and `FLAG_ALT_FOCUSABLE_IM` so the keyboard remains open when copying text in external apps.
- **Wispr Flow & Dictation Filter on macOS**:
  - Filtered transient and auto-generated pasteboard types (`org.nspasteboard.TransientType`).
  - Added a 400ms settle debounce in pasteboard monitoring so temporary text injections from dictation tools like Wispr Flow revert locally without syncing to the phone.

---
## [0.1.11] - 2026-10-21

### Fixed
- **Real-Time Background Clipboard Sync**:
  - Unified `dispatchLocalCopy` to directly construct and stream protobuf `Envelope` via `BridgeConnectionManager` so copied text syncs to Mac in real time without opening the Bridge Android application.
  - Removed recursion storms in logcat watcher by filtering strictly for package-targeted denial events with debounce throttling.
  - Configured `ClipboardSyncActivity` with focusable non-modal attributes to reliably obtain window focus in 5ms across third-party apps.

---
## [0.1.10] - 2026-10-20

### Fixed
- **Web & Rich Text Clipboard Conversion**:
  - Converted all Android clipboard reading points from `.text` to `.coerceToText()` so HTML text and URLs copied from Chrome and external apps are captured reliably instead of returning null.
- **Homebrew Cask Standard DMG Installation**:
  - Pointed Homebrew Cask directly to the release `.dmg` container and eliminated deprecated syntax for clean `brew install --cask rajanraj2/tap/bridge` execution.

---
## [0.1.9] - 2026-10-20

### Added
- **System-Wide Background Copy Synchronization**:
  - Integrated `BridgeAccessibilityService` to listen for text copy events across all Android applications without user intervention.
  - Combined with background service logcat watcher and 1x1 transparent focus trampoline for instant capture.
  - Transmits newly copied content directly to paired Mac over TCP/TLS in real time.

---
## [0.1.8] - 2026-10-20

### Added
- **Homebrew Cask Support**:
  - Added `Casks/bridge.rb` enabling one-command installation on any Mac: `brew install --cask rajanraj2/tap/bridge`.
  - Included automated `postflight` unquarantine hook so Homebrew installations run immediately with zero Gatekeeper warnings.
- **Universal One-Line Terminal Installer**:
  - Added `scripts/install_mac.sh` executable via `curl -fsSL https://raw.githubusercontent.com/rajanraj2/bridge/main/scripts/install_mac.sh | bash`.

### Fixed
- **macOS Gatekeeper Compatibility**:
  - Lowered `LSMinimumSystemVersion` from `14.5` to `13.0` (macOS Ventura and Sonoma) to prevent LaunchServices from flagging the binary as "damaged" on older macOS versions.
  - Added Universal 2 binary build support (`arm64` + `x86_64`) ensuring native execution across both Apple Silicon and Intel Macs.

---
## [0.1.7] - 2026-10-20

### Added
- **Automatic System-Wide Background Clipboard Sync**:
  - Implemented continuous background clipboard change detection in `BridgeForegroundService` across all Android apps.
  - Introduced transparent focus trampoline (`ClipboardSyncActivity`) to capture copied text from outside apps without manual button presses.
  - Automatically dispatches copied text to paired Mac over the active socket in real time.

---
## [0.1.6] - 2026-10-20

### Added
- **Native Apple Installer Package (`.pkg`)**:
  - Generated `Bridge-v0.1.6.pkg` via `pkgbuild` allowing users to install directly into `/Applications` using macOS's built-in installer wizard without Gatekeeper restrictions.
- **Clean Drag-and-Drop DMG**:
  - Restored clean standard DMG layout displaying strictly `Bridge.app` and `Applications` symlink without extra command files.
  - Added ad-hoc container signing to the `.dmg` file and strict world-readable bundle permissions.

---
## [0.1.5] - 2026-10-20

### Added
- **In-App Update Checker**:
  - Implemented GitHub Releases API update checker on both Android and macOS.
  - Automatic background checking on launch and manual **"🔄 Check for Updates"** button in settings/dashboard.
  - Prominent in-app banner displayed when an update is available with a 1-tap **"⬇️ Download & Update"** action.
- **macOS Gatekeeper Helpers in DMG**:
  - Packaged DMG with one-click `Enable Bridge (First Run).command` and `Open Privacy & Security Settings.command` to allow installing and opening without typing commands in the terminal.
  - Added direct "Open Privacy & Security Settings..." option in macOS menu bar and preferences window.

### Fixed
- **Silent Clipboard Synchronization**:
  - Removed all Toast pop-ups when copying or syncing clipboard on Android for a clean, non-intrusive background experience.

---
## [0.1.3] - 2026-10-20

### Fixed
- **Bidirectional Clipboard Synchronization**:
  - Resolved Android 10+ background clipboard restrictions by adding a dedicated notification action button (`📋 Copy to Clipboard`) in the persistent foreground service notification shade.
  - Added prominent **"📥 Received from Mac"** card with one-tap copy button in the Android dashboard UI.
  - Dispatched clipboard operations to Android's Main Looper thread to prevent background thread silently dropping primary clip updates.
  - Enabled `forceSend: true` in macOS pasteboard monitoring so copied content is always pushed to connected peers.
  - Added automatic connection resumption on Android app launch to keep continuity active without manual pairing triggers.
- **Asset and Release Version Matching**:
  - Converted packaging scripts (`package_android.sh`, `package_macos.sh`, and `release.yml`) from hardcoded version numbers to dynamic tag-matching names (`Bridge-v0.1.3.apk`, `Bridge-v0.1.3.dmg`, `Bridge-v0.1.3-macOS.zip`).

---
## [0.1.2] - 2026-10-20

### Fixed
- **CI & Release Pipeline**:
  - Android CI: Configured `gradle/actions/setup-gradle@v4` and pre-installation of Android SDK Platform 35 via `sdkmanager`.
  - macOS CI: Removed Swift 6-only `nonisolated` type modifiers from generated Protocol Buffers files to ensure 100% compatibility with Xcode 15 / Swift 5.10.
  - Resolved test fixture path resolution across headless CI runners via `GITHUB_WORKSPACE` and standardized relative paths.
  - Added loopback interface fallback in `getLocalIpAddresses()` for headless CI environments.
- **Deployment & Packaging**:
  - Automated release artifact packaging for Android (`Bridge-0.1.2.apk`) and macOS (`Bridge-0.1.2.dmg` and `Bridge-0.1.2-macOS.zip`).

---


## [0.1.0] - 2026-10-20

Initial release of Bridge: an open-source, local-first continuity application connecting Android and macOS devices through an independent clean-room architecture.

### Added
- **Core Architecture & Schemas**:
  - Strongly-typed `bridge.v1` Protocol Buffers schemas for envelopes, session handshake, pairing, heartbeats, notifications, clipboard, and chunked file transfer.
  - Multi-platform generated code for Swift and Kotlin.
  - Shared language-neutral JSON test fixtures with cross-platform decoding validation.
- **Pairing & Cryptographic Identity**:
  - Ed25519 cryptographic identity generation and hardware-backed storage (Android Keystore / macOS Keychain).
  - QR-assisted authenticated pairing handshake with short-lived 32-byte challenges.
  - 6-digit Short Authentication String (SAS) matching confirmation on both screens.
  - Paired device revocation with immediate session invalidation.
- **Local Discovery & Secure Session**:
  - Zero-configuration local discovery via mDNS / Bonjour (`_bridge._tcp.`).
  - TLS 1.3 transport over TCP with mutual pinned peer identity verification.
  - Monotonic session sequence numbers and heartbeat health monitoring.
  - Bounded exponential backoff with jitter for transient network recovery.
- **Notification Mirroring**:
  - Filtered Android notification forwarding to macOS.
  - Configurable application allow/deny lists.
  - Automatic sensitive content suppression.
  - Sliding-window deduplication (1000 events, 10-minute TTL).
  - Notification action descriptor and quick-reply support.
- **Clipboard Synchronization**:
  - Bidirectional plain-text clipboard synchronization.
  - Multi-tier echo-loop prevention (origin check, event deduplication, recent content hash matching).
  - Diagnostic reporting for Android 10+ background clipboard restrictions.
- **File, Text, and URL Sharing**:
  - Safe filename sanitization stripping path traversal (`../`) and illegal characters.
  - Collision-free destination path resolution.
  - Bounded 256 KiB chunked streaming with backpressure, supporting files up to 10 GiB.
  - Incremental SHA-256 verification and atomic commit from `.tmp` staging files.
  - Safe cancellation with immediate cleanup of incomplete files.
  - Android Sharesheet intent filters (`SEND` / `SEND_MULTIPLE`).
- **Remote Routing & Observability**:
  - Tailscale IP / MagicDNS endpoint configuration with local-first routing.
  - Privacy-preserving structured diagnostic logging with 100% content redaction.
  - Exportable diagnostic bundle generator in JSON format.
  - Permission & Reliability Advisor for Android and macOS.
- **Documentation**:
  - Architecture Decision Records (ADR 0001–0005).
  - Legal & licensing audit (`docs/AIRSYNC_AUDIT.md`, `docs/LEGAL_AND_LICENSING.md`).
  - Installation and user guide (`docs/INSTALL.md`).
  - Troubleshooting and privacy guide (`docs/TROUBLESHOOTING.md`).
  - Signing and distribution guide (`docs/SIGNING_AND_DISTRIBUTION.md`).
- **CI / CD**:
  - GitHub Actions workflows for Android (`android.yml`), macOS (`macos.yml`), and Protocol validation (`protocol.yml`).

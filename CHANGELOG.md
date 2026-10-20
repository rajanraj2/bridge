# Changelog

All notable changes to Bridge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

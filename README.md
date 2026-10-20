# Bridge

Bridge is an open-source, local-first continuity application connecting Android and macOS devices.

## Features (v0.1 Scope)
- **Pairing & Identity**: Authenticated QR-code pairing using Ed25519 cryptographic identities.
- **Local Discovery**: Automatic peer discovery on local networks via mDNS / Bonjour.
- **Secure Transport**: Direct TCP over TLS 1.3 with pinned peer identity verification.
- **Notification Mirroring**: Android notification forwarding to macOS with actionable replies.
- **Clipboard Sync**: Bidirectional text clipboard synchronization with echo-loop prevention.
- **File & Link Sharing**: Chunked file streaming with SHA-256 verification and URL sharing.
- **Remote Routing**: Direct connection over user-managed Tailscale IP / MagicDNS.

## Project Structure
- `protocol/` — Shared Protocol Buffers (`bridge.v1`) schemas and compatibility test fixtures.
- `android/` — Native Android client (Kotlin + Jetpack Compose).
- `macos/` — Native macOS client (Swift + SwiftUI).

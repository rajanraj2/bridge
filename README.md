# Bridge

[![Android CI](https://github.com/rajanraj2/bridge/actions/workflows/android.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/android.yml)
[![macOS CI](https://github.com/rajanraj2/bridge/actions/workflows/macos.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/macos.yml)
[![Protocol CI](https://github.com/rajanraj2/bridge/actions/workflows/protocol.yml/badge.svg)](https://github.com/rajanraj2/bridge/actions/workflows/protocol.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**Bridge** is an open-source, local-first continuity application connecting Android and macOS devices. It pairs devices securely, discovers peers on local networks, and establishes authenticated, encrypted sessions without requiring accounts, central servers, or third-party cloud relays.

---

## Features (v0.1 Scope)

- **Pairing & Cryptographic Identity**: Authenticated QR-code pairing using Ed25519 identity keys stored in hardware-backed secure storage (Android Keystore and macOS Keychain).
- **Local Discovery**: Zero-configuration peer discovery via mDNS / Bonjour.
- **Secure Transport**: Direct TCP over TLS 1.3 with mutual pinned peer identity verification.
- **Notification Mirroring**: Filtered Android notification forwarding to macOS with actionable replies.
- **Clipboard Sync**: Bidirectional text clipboard synchronization with loop prevention.
- **File & Link Sharing**: Chunked binary streaming (256 KiB chunks) with backpressure, receiver approval, and SHA-256 integrity verification.
- **Remote Routing**: Direct connection fallback over user-managed Tailscale IP or MagicDNS hostname.

---

## Architecture & Clean-Room Guarantee

Bridge is built from the ground up as a **100% independent, clean-room implementation** under the Apache License 2.0. Following an architectural and licensing audit of existing hobbyist utilities (such as AirSync), Bridge deliberately uses zero borrowed or decompiled code.

Key architectural pillars:
- **Shared Wire Contract**: Strongly typed, versioned schemas in Protocol Buffers (`bridge.v1`).
- **Framing**: 4-byte big-endian length-delimited envelopes with strict maximum frame limits.
- **Stateful Networking**: Monotonic session sequence numbers, structured error codes, and exponential backoff reconnection.

---

## Repository Structure

```text
bridge/
├── .github/workflows/       # CI pipelines (Android, macOS, Protocol)
├── android/                 # Native Android application (Kotlin + Compose)
│   ├── app/                 # Android application shell
│   ├── core/domain/         # Platform-independent models & state machines
│   ├── core/protocol/       # Wire framing codecs & message handlers
│   └── build.gradle.kts     # Root Gradle build configuration
├── macos/                   # Native macOS client (Swift + SwiftUI)
│   ├── Sources/BridgeDomain # Domain models & state machine
│   ├── Sources/BridgeProtocol # Wire framing decoders & encoders
│   ├── Sources/BridgeSecurity # Identity generation, SAS, SHA-256
│   ├── Sources/BridgeNetwork  # Bonjour discovery & connection coordinator
│   ├── Sources/BridgeApp    # SwiftUI MenuBarExtra application shell
│   └── Tests/               # XCTest suite
├── protocol/                # Shared protocol definitions & test fixtures
│   ├── proto/bridge/v1/     # Protocol Buffers wire schemas
│   └── fixtures/            # Shared JSON compatibility test fixtures
├── CONTRIBUTING.md          # Contribution guidelines & branching model
├── SECURITY.md              # Security model & vulnerability disclosure
└── LICENSE                  # Apache License 2.0
```

---

## Getting Started & Building Locally

### Prerequisites
- **Android**: JDK 21, Android SDK (Platform 35, Build-Tools 34+), Android Studio.
- **macOS**: macOS 14.5+ with Xcode 15+ or Swift 5.10+.
- **Protocol**: Protocol Buffers Compiler (`protoc` 3.x+).

### Building Android
```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

### Building macOS
```bash
cd macos
swift build
swift test
```

### Validating Protocol Schemas & Fixtures
```bash
protoc --proto_path=protocol/proto --descriptor_set_out=/dev/null protocol/proto/bridge/v1/*.proto
python3 -c "
import json, glob
for f in glob.glob('protocol/fixtures/*.json'):
    with open(f) as fp:
        json.load(fp)
print('Fixtures valid!')
"
```

## Documentation & Guides
- [Installation & Setup](docs/INSTALL.md)
- [Troubleshooting & Privacy Guide](docs/TROUBLESHOOTING.md)
- [Signing & Distribution Guide](docs/SIGNING_AND_DISTRIBUTION.md)
- [Architecture Decision Records](docs/adr/)
- [Changelog & Releases](CHANGELOG.md)


---

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, clean-room policy, and the process for submitting pull requests.

We strictly follow a branch protection workflow:
- **Never push directly to `main`**.
- Develop in `feature/<milestone>-<description>` branches.
- Open a Pull Request against `main`.
- Merge only after all CI checks pass.

---

## License

Bridge is licensed under the [Apache License, Version 2.0](LICENSE).

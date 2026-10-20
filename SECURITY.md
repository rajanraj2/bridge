# Security Policy

Bridge takes security and user privacy seriously. As a local-first application bridging sensitive cross-device data (notifications, clipboard, files), strong cryptographic guarantees and defense-in-depth are foundational to the project.

---

## 1. Supported Versions

| Version | Supported |
|---|---|
| `v0.1.x` | :white_check_mark: Current development milestone |
| `< v0.1.0` | :x: Pre-release / unsupported |

---

## 2. Reporting a Vulnerability

If you discover a security vulnerability or privacy flaw in Bridge:

1. **Do not create a public GitHub issue.**
2. Send an email to the security response team at **security@bridgeproject.dev** (or submit a private vulnerability report via GitHub Security Advisories if enabled).
3. Include detailed information:
   - Component affected (Android client, macOS client, protocol schema, or cryptographic handshake).
   - Step-by-step reproduction instructions or proof-of-concept.
   - Assessment of impact (e.g., unauthorized pairing, plaintext data leakage, denial-of-service).
4. You will receive an acknowledgment within 48 hours, followed by regular status updates as a fix is developed and tested.
5. Please allow a 90-day coordinated disclosure period before public discussion.

---

## 3. Threat Model & Security Architecture

### 3.1 Peer Identity & Pairing
- **Cryptographic Keys:** Devices generate an Ed25519 identity key pair on first launch. Private keys are persisted exclusively in hardware-backed secure storage (Android Keystore and macOS Keychain).
- **Ephemeral Pairing Challenges:** Initial discovery and pairing use short-lived, single-use challenges. Discovery over mDNS or possession of a QR code payload never grants ambient trust.
- **Explicit Mutual Confirmation:** Both devices display short authentication fingerprints (SAS) that require explicit user approval before trust is established and persisted.

### 3.2 Transport Security
- **TLS 1.3:** All communication traverses TCP secured by TLS 1.3.
- **Peer Pinning:** During the TLS handshake, both endpoints verify the peer's certificate and public key against the persisted trusted identity. Connections from untrusted or revoked peers are rejected immediately.
- **No Plaintext Fallback:** Downgrading to unauthenticated transport or plaintext is strictly disallowed.

### 3.3 Privacy & Data Protection
- **Zero Content Logging:** The logger enforces strict redaction. Clipboard text, notification titles and bodies, file payloads, private keys, and pairing secrets are never logged.
- **Ephemeral Processing:** Bridge does not retain persistent logs of notification history or clipboard contents.
- **No Cloud Relay:** All communication occurs directly over the local Wi-Fi network or through user-managed Tailscale mesh interfaces. Bridge operates zero servers or telemetry relays.

### 3.4 File Transfer Safety
- **Receiver Approval:** Incoming file transfers require explicit receiver authorization.
- **Sandboxed Paths:** Received files are written to isolated staging areas using sanitized, random filenames. Sender-provided paths are rejected to prevent directory traversal attacks (`../`).
- **Integrity Validation:** Chunks are offset-checked, and the complete file is verified against its declared SHA-256 digest before atomic commit to the user's downloads folder.

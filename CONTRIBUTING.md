# Contributing to Bridge

Thank you for your interest in contributing to Bridge! Bridge is an open-source, local-first continuity platform connecting Android and macOS devices through a shared, versioned protocol.

---

## 1. Development Principles & Code of Conduct

1. **Security & Privacy First:** Never commit code that logs sensitive user data (clipboard text, notification bodies, private keys, or pairing tokens).
2. **Clean-Room Implementation:** No source code, assets, or proprietary schemas may be copied or adapted from AirSync or other restrictively licensed tools. All contributions must be original works licensed under Apache 2.0.
3. **Protocol-Driven:** All cross-platform behavior is governed by the versioned Protocol Buffers schema under `protocol/proto/bridge/v1/`. Changes to the wire format require corresponding shared test fixtures.
4. **Tested & Verified:** Every PR must include unit tests covering state transitions, error paths, and edge cases.

---

## 2. Branching & Pull Request Workflow

Bridge strictly enforces branch protections both on GitHub and locally:

### 2.1 Branch Protection Rules
1. **Never commit directly to `main`**: Direct commits and direct pushes to `main` are blocked on GitHub and rejected locally by Git hooks.
2. **Never merge locally into `main`**: Merging into `main` must only occur via GitHub Pull Requests after automated CI checks pass.
3. **Local Hook Setup**:
   To enforce these safeguards locally in your clone, run:
   ```bash
   git config core.hooksPath .githooks
   ```
   This activates `.githooks/pre-commit`, `.githooks/pre-merge-commit`, and `.githooks/pre-push`, preventing accidental local commits, local merges, or pushes to `main`.

### 2.2 Branch Naming Conventions
- Features: `feature/<short-description>` (e.g., `feature/bluetooth-le-discovery`)
- Bug fixes: `fix/<short-description>`
- Documentation: `docs/<short-description>`

### 2.3 Pull Request & Automated Release Labels
All changes must be submitted via a Pull Request against `main`. When creating or reviewing a PR intended for a release, attach a release label:

| Label | Effect on Merge | Example |
|---|---|---|
| `release:patch` or `patch` | Bumps patch version | `0.1.2` → `0.1.3` |
| `release:minor` or `minor` | Bumps minor version | `0.1.2` → `0.2.0` |
| `release:major` or `major` | Bumps major version | `0.1.2` → `1.0.0` |

When a PR with a release label is merged into `main`:
1. GitHub Actions automatically executes `scripts/bump_version.sh`.
2. Creates and pushes the new release tag `vX.Y.Z`.
3. Triggers `release.yml` to compile the Android APK, package the macOS DMG and ZIP, and publish the release with assets.
4. If no release label is present, the PR is merged without triggering a new release.
---

## 3. Repository Structure

- `protocol/` — Shared Protocol Buffers (`bridge.v1`) schemas and cross-platform test fixtures.
- `android/` — Native Android client built with Kotlin and Jetpack Compose (Gradle).
- `macos/` — Native macOS client built with Swift and SwiftUI (Xcode / Swift Package Manager).
- `docs/` — Architecture specifications, protocol documentation, ADRs, and audit reports.

---

## 4. Local Development

### 4.1 Android
- **Prerequisites:** JDK 17 or higher, Android SDK (API 34+), Android Studio Ladybug or later.
- **Build:**
  ```bash
  cd android
  ./gradlew assembleDebug
  ```
- **Test:**
  ```bash
  cd android
  ./gradlew testDebugUnitTest
  ```

### 4.2 macOS
- **Prerequisites:** macOS 14.5+ with Xcode 15+ / Swift 5.10+.
- **Build & Test via Swift Package Manager:**
  ```bash
  cd macos
  swift build
  swift test
  ```
- **Build via Xcode:**
  Open `macos/Bridge.xcodeproj` in Xcode, select the `BridgeApp` scheme, and run `Product -> Build` or `Product -> Test`.

### 4.3 Protocol Schemas
- Verify syntax and compatibility:
  ```bash
  protoc --proto_path=protocol/proto --descriptor_set_out=/dev/null protocol/proto/bridge/v1/*.proto
  ```

---

## 5. Definition of Done Checklist

Before submitting a PR, verify:
- [ ] Code strictly follows architectural boundaries (adapters do not own transport; transport does not own policy).
- [ ] No clipboard contents, notification text, or keys appear in logs.
- [ ] Unit tests are added for new business logic and state machines.
- [ ] If wire protocol was modified, fixtures in `protocol/fixtures/` are updated.
- [ ] Clean build and test passes on both Android and macOS.
- [ ] Documentation updated where applicable.

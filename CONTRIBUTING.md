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

Bridge strictly enforces branch protections:

1. **Never commit directly to `main`:** All development occurs in dedicated feature or fix branches.
2. **Branch Naming:**
   - Features: `feature/<milestone>-<short-description>` (e.g., `feature/m0-foundation`)
   - Fixes: `fix/<short-description>`
   - Documentation: `docs/<short-description>`
3. **Pull Request Requirements:**
   - Open a PR against `main`.
   - Ensure all automated CI checks pass (Android build/tests, macOS build/tests, protocol validation).
   - Document changes clearly in the PR description, referencing relevant milestones or issue IDs.
   - Self-review the diff to verify that no unwanted temporary files, secrets, or unapproved dependencies are introduced.
4. **Merge Policy:** Branches are merged into `main` via squash-merge or rebase-merge only after all validation and CI pipelines pass.

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

#!/usr/bin/env bash
set -euo pipefail

export PATH="/home/linuxbrew/.linuxbrew/bin:${PATH}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${REPO_ROOT}/dist"
APP_DIR="${DIST_DIR}/Bridge.app"

echo "=== Packaging Bridge for macOS ==="
mkdir -p "${DIST_DIR}"
rm -rf "${APP_DIR}"

mkdir -p "${APP_DIR}/Contents/MacOS"
mkdir -p "${APP_DIR}/Contents/Resources"

# 1. Compile release binary
cd "${REPO_ROOT}/macos"
echo "Building release binary..."
swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path)/BridgeApp"
if [ ! -f "${BIN_PATH}" ]; then
    echo "Error: Binary not found at ${BIN_PATH}"
    exit 1
fi

cp "${BIN_PATH}" "${APP_DIR}/Contents/MacOS/BridgeApp"
chmod +x "${APP_DIR}/Contents/MacOS/BridgeApp"

# 2. Write Info.plist
cat <<EOF > "${APP_DIR}/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>BridgeApp</string>
    <key>CFBundleIdentifier</key>
    <string>dev.bridge.BridgeApp</string>
    <key>CFBundleName</key>
    <string>Bridge</string>
    <key>CFBundleDisplayName</key>
    <string>Bridge</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>0.1.0</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.5</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>Copyright © 2026 Bridge Project Contributors. Apache 2.0 License.</string>
</dict>
</plist>
EOF

echo "APPL????" > "${APP_DIR}/Contents/PkgInfo"

# 3. Create macOS ZIP archive
cd "${DIST_DIR}"
echo "Creating Bridge-v0.1.0-macOS.zip..."
zip -r -q "Bridge-v0.1.0-macOS.zip" "Bridge.app"

# 4. If running on macOS with hdiutil, generate DMG
if command -v hdiutil >/dev/null 2>&1; then
    echo "Creating Bridge-v0.1.0.dmg via hdiutil..."
    rm -f "Bridge-v0.1.0.dmg"
    hdiutil create -volname "Bridge" -srcfolder "${APP_DIR}" -ov -format UDZO "Bridge-v0.1.0.dmg"
    echo "Created dist/Bridge-v0.1.0.dmg"
fi

echo "Successfully packaged macOS application in ${DIST_DIR}:"
ls -lh "${DIST_DIR}"

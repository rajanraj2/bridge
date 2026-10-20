#!/usr/bin/env bash
set -euo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:${PATH}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${REPO_ROOT}/dist"
APP_DIR="${DIST_DIR}/Bridge.app"
VERSION="${1:-$(cat "${REPO_ROOT}/VERSION" 2>/dev/null | tr -d '[:space:]' || echo "0.1.3")}"
if [ -n "${GITHUB_REF_NAME:-}" ] && [[ "${GITHUB_REF_NAME}" == v* ]]; then
    VERSION="${GITHUB_REF_NAME#v}"
fi

echo "=== Packaging Bridge v${VERSION} for macOS ==="
mkdir -p "${DIST_DIR}"
rm -rf "${APP_DIR}"

mkdir -p "${APP_DIR}/Contents/MacOS"
mkdir -p "${APP_DIR}/Contents/Resources"

# 1. Compile release binary (Universal arm64 + x86_64 on macOS, host arch on Linux)
cd "${REPO_ROOT}/macos"
echo "Building release binary..."
if swift build -c release --arch arm64 --arch x86_64 2>/dev/null; then
    echo "Universal binary (arm64 + x86_64) compiled successfully!"
else
    echo "Compiling host architecture release binary..."
    swift build -c release
fi

BIN_DIR="$(swift build -c release --show-bin-path)"
if [ -f "${BIN_DIR}/BridgeApp" ]; then
    cp "${BIN_DIR}/BridgeApp" "${APP_DIR}/Contents/MacOS/BridgeApp"
elif [ -f "${BIN_DIR}/Bridge" ]; then
    cp "${BIN_DIR}/Bridge" "${APP_DIR}/Contents/MacOS/BridgeApp"
else
    echo "Searching for built binary..."
    FOUND_BIN="$(find "${REPO_ROOT}/macos/.build" -type f -name "BridgeApp" -perm +111 2>/dev/null | head -n 1 || true)"
    if [ -n "${FOUND_BIN}" ] && [ -f "${FOUND_BIN}" ]; then
        cp "${FOUND_BIN}" "${APP_DIR}/Contents/MacOS/BridgeApp"
    else
        echo "Error: Could not locate compiled BridgeApp binary"
        exit 1
    fi
fi

chmod +x "${APP_DIR}/Contents/MacOS/BridgeApp"

if [ -f "${REPO_ROOT}/macos/Resources/AppIcon.icns" ]; then
    cp "${REPO_ROOT}/macos/Resources/AppIcon.icns" "${APP_DIR}/Contents/Resources/AppIcon.icns"
fi

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
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>CFBundleSupportedPlatforms</key>
    <array>
        <string>MacOSX</string>
    </array>
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

# 3. Ensure world-readable permissions and ad-hoc sign the binary & bundle
chmod -R u=rwX,go=rX "${APP_DIR}"
if command -v codesign >/dev/null 2>&1; then
    echo "Ad-hoc codesigning Bridge.app..."
    xattr -cr "${APP_DIR}" 2>/dev/null || true
    codesign --force --sign - "${APP_DIR}/Contents/MacOS/BridgeApp" || true
    codesign --force --sign - "${APP_DIR}" || true
fi

# 4. Create macOS ZIP archive
cd "${DIST_DIR}"
ZIP_NAME="Bridge-v${VERSION}-macOS.zip"
echo "Creating ${ZIP_NAME}..."
rm -f "${ZIP_NAME}"
zip -r -q "${ZIP_NAME}" "Bridge.app"

# 5. If running on macOS with hdiutil, generate clean Drag-and-Drop DMG
if command -v hdiutil >/dev/null 2>&1; then
    DMG_NAME="Bridge-v${VERSION}.dmg"
    echo "Creating clean drag-and-drop ${DMG_NAME} via hdiutil..."
    DMG_STAGING="${DIST_DIR}/dmg_staging"
    rm -rf "${DMG_STAGING}"
    mkdir -p "${DMG_STAGING}"
    cp -R "${APP_DIR}" "${DMG_STAGING}/"
    ln -s /Applications "${DMG_STAGING}/Applications"

    rm -f "${DIST_DIR}/${DMG_NAME}"
    hdiutil create -volname "Bridge" -srcfolder "${DMG_STAGING}" -ov -format UDZO "${DIST_DIR}/${DMG_NAME}"
    rm -rf "${DMG_STAGING}"

    if command -v codesign >/dev/null 2>&1; then
        codesign --force --sign - "${DIST_DIR}/${DMG_NAME}" || true
    fi
    echo "Created dist/${DMG_NAME}"
fi

# 6. If running on macOS with pkgbuild, generate native Apple Installer package
if command -v pkgbuild >/dev/null 2>&1; then
    PKG_NAME="Bridge-v${VERSION}.pkg"
    echo "Creating native Apple installer ${PKG_NAME} via pkgbuild..."
    pkgbuild --component "${APP_DIR}" --install-location /Applications "${DIST_DIR}/${PKG_NAME}"
    echo "Created dist/${PKG_NAME}"
fi
echo "Successfully packaged macOS application in ${DIST_DIR}:"
ls -lh "${DIST_DIR}"

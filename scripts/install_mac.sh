#!/usr/bin/env bash
set -euo pipefail

echo "============================================="
echo "   Installing Bridge for macOS"
echo "============================================="

RELEASE_JSON=$(curl -s https://api.github.com/repos/rajanraj2/bridge/releases/latest)
TAG_NAME=$(echo "${RELEASE_JSON}" | grep -m1 '"tag_name":' | cut -d '"' -f 4)
DOWNLOAD_URL=$(echo "${RELEASE_JSON}" | grep '"browser_download_url":' | grep -iE 'Bridge.*-macOS\.zip' | head -n 1 | cut -d '"' -f 4)

if [ -z "${DOWNLOAD_URL}" ]; then
    DOWNLOAD_URL="https://github.com/rajanraj2/bridge/releases/latest/download/Bridge-${TAG_NAME}-macOS.zip"
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

echo "Downloading Bridge ${TAG_NAME}..."
curl -sL -o "${TEMP_DIR}/Bridge.zip" "${DOWNLOAD_URL}"

echo "Extracting..."
unzip -q -o "${TEMP_DIR}/Bridge.zip" -d "${TEMP_DIR}"

echo "Installing to /Applications/Bridge.app..."
rm -rf /Applications/Bridge.app
cp -R "${TEMP_DIR}/Bridge.app" /Applications/

echo "Configuring permissions..."
xattr -cr /Applications/Bridge.app 2>/dev/null || true

echo "Launching Bridge..."
open /Applications/Bridge.app 2>/dev/null || true

echo "============================================="
echo "✅ Bridge ${TAG_NAME} successfully installed & launched!"
echo "============================================="

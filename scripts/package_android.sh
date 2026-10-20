#!/usr/bin/env bash
set -euo pipefail

export PATH="/home/linuxbrew/.linuxbrew/bin:${PATH}"
export JAVA_HOME="/home/linuxbrew/.linuxbrew/opt/openjdk@21"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${REPO_ROOT}/dist"
mkdir -p "${DIST_DIR}"

echo "=== Packaging Bridge for Android ==="
cd "${REPO_ROOT}/android"
gradle assembleDebug

APK_SRC="${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_SRC}" ]; then
    echo "Error: APK not found at ${APK_SRC}"
    exit 1
fi
VERSION="${1:-$(cat "${REPO_ROOT}/VERSION" 2>/dev/null | tr -d '[:space:]' || echo "0.1.3")}"
if [ -n "${GITHUB_REF_NAME:-}" ] && [[ "${GITHUB_REF_NAME}" == v* ]]; then
    VERSION="${GITHUB_REF_NAME#v}"
fi

APK_DEST="${DIST_DIR}/Bridge-v${VERSION}.apk"
cp "${APK_SRC}" "${APK_DEST}"
echo "Successfully created ${APK_DEST}:"
ls -lh "${APK_DEST}"

#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="${REPO_ROOT}/VERSION"

if [ ! -f "${VERSION_FILE}" ]; then
    echo "0.1.2" > "${VERSION_FILE}"
fi

CURRENT_VERSION="$(cat "${VERSION_FILE}" | tr -d '[:space:]')"
BUMP_TYPE="${1:-patch}"

IFS='.' read -r MAJOR MINOR PATCH <<< "${CURRENT_VERSION}"

case "${BUMP_TYPE}" in
    patch)
        PATCH=$((PATCH + 1))
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    *)
        echo "Error: Unknown bump type '${BUMP_TYPE}'. Must be 'patch', 'minor', or 'major'."
        exit 1
        ;;
esac

NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
echo "Bumping version: ${CURRENT_VERSION} -> ${NEW_VERSION} (${BUMP_TYPE})"

# 1. Update VERSION file
echo "${NEW_VERSION}" > "${VERSION_FILE}"

# 2. Update Android build.gradle.kts
GRADLE_FILE="${REPO_ROOT}/android/app/build.gradle.kts"
if [ -f "${GRADLE_FILE}" ]; then
    CURRENT_CODE="$(grep -o 'versionCode = [0-9]*' "${GRADLE_FILE}" | awk '{print $3}' || echo 1)"
    NEW_CODE=$((CURRENT_CODE + 1))
    sed -i "s/versionCode = [0-9]*/versionCode = ${NEW_CODE}/g" "${GRADLE_FILE}"
    sed -i "s/versionName = \"[0-9.]*\"/versionName = \"${NEW_VERSION}\"/g" "${GRADLE_FILE}"
fi

# 3. Update package_macos.sh
MACOS_PKG="${REPO_ROOT}/scripts/package_macos.sh"
if [ -f "${MACOS_PKG}" ]; then
    sed -i "s/Bridge-v[0-9.]*-macOS.zip/Bridge-v${NEW_VERSION}-macOS.zip/g" "${MACOS_PKG}"
    sed -i "s/Bridge-v[0-9.]*.dmg/Bridge-v${NEW_VERSION}.dmg/g" "${MACOS_PKG}"
    sed -i "s/<string>[0-9.]*<\/string>/<string>${NEW_VERSION}<\/string>/g" "${MACOS_PKG}"
fi

# 4. Update package_android.sh
ANDROID_PKG="${REPO_ROOT}/scripts/package_android.sh"
if [ -f "${ANDROID_PKG}" ]; then
    sed -i "s/Bridge-v[0-9.]*.apk/Bridge-v${NEW_VERSION}.apk/g" "${ANDROID_PKG}"
fi

# 5. Update CHANGELOG.md if not already recorded
CHANGELOG="${REPO_ROOT}/CHANGELOG.md"
TODAY="$(date +%Y-%m-%d)"
if [ -f "${CHANGELOG}" ] && ! grep -q "## \[${NEW_VERSION}\]" "${CHANGELOG}"; then
    TEMP_FILE="$(mktemp)"
    awk -v ver="## [${NEW_VERSION}] - ${TODAY}\n\n### Changed\n- Version bump to ${NEW_VERSION}\n" \
        '/^## \[/ && !found { print ver; found=1 } { print }' "${CHANGELOG}" > "${TEMP_FILE}"
    mv "${TEMP_FILE}" "${CHANGELOG}"
fi

echo "Successfully bumped Bridge to version ${NEW_VERSION}!"

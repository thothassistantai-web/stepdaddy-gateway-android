#!/usr/bin/env bash
# Build signed or unsigned release APK/AAB for StepDaddy Gateway.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"

if [[ ! -d "${ANDROID_HOME}" ]]; then
  echo "ERROR: ANDROID_HOME not found at ${ANDROID_HOME}" >&2
  exit 1
fi

KEYSTORE_PROPS="${ROOT}/keystore.properties"
GRADLE_ARGS=()

if [[ -f "${KEYSTORE_PROPS}" ]]; then
  echo "==> Found keystore.properties — release signing enabled"
  GRADLE_ARGS+=(-Pandroid.injected.signing.store.file="$(grep -E '^storeFile=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
  GRADLE_ARGS+=(-Pandroid.injected.signing.store.password="$(grep -E '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
  GRADLE_ARGS+=(-Pandroid.injected.signing.key.alias="$(grep -E '^keyAlias=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
  GRADLE_ARGS+=(-Pandroid.injected.signing.key.password="$(grep -E '^keyPassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
else
  echo "==> No keystore.properties — assembleRelease will produce UNSIGNED APK"
  echo "    Create keystore.properties or sign manually (see docs/RELEASE.md)"
fi

echo "==> Running unit tests (release)"
./gradlew testReleaseUnitTest

echo "==> Building release APK and AAB"
./gradlew assembleRelease bundleRelease -x lintVitalRelease "${GRADLE_ARGS[@]}"

APK_DIR="${ROOT}/app/build/outputs/apk/release"
BUNDLE_DIR="${ROOT}/app/build/outputs/bundle/release"
RELEASE_DIR="${ROOT}/release"
mkdir -p "${RELEASE_DIR}"

# Copy artifacts with stable names
VERSION_NAME="$(grep -E 'versionName\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION_CODE="$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')"

if [[ -f "${APK_DIR}/app-release.apk" ]]; then
  cp "${APK_DIR}/app-release.apk" "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.apk"
  echo "==> Signed APK: ${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.apk"
elif [[ -f "${APK_DIR}/app-release-unsigned.apk" ]]; then
  cp "${APK_DIR}/app-release-unsigned.apk" "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-unsigned.apk"
  echo "==> Unsigned APK: ${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-unsigned.apk"
else
  echo "ERROR: No release APK found in ${APK_DIR}" >&2
  exit 1
fi

if [[ -f "${BUNDLE_DIR}/app-release.aab" ]]; then
  cp "${BUNDLE_DIR}/app-release.aab" "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
  echo "==> AAB: ${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
fi

# Generate update manifest template for GitHub Releases
MANIFEST="${RELEASE_DIR}/update-manifest.json"
APK_URL_PLACEHOLDER="https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v${VERSION_NAME}/stepdaddy-gateway-${VERSION_NAME}.apk"
cat > "${MANIFEST}" <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${APK_URL_PLACEHOLDER}",
  "releaseNotes": "See CHANGELOG.md",
  "mandatory": false
}
EOF
echo "==> Update manifest: ${MANIFEST}"

echo ""
echo "Build complete."
echo "  Debug APK (dev):  app/build/outputs/apk/debug/app-debug.apk"
echo "  Release outputs:  ${RELEASE_DIR}/"

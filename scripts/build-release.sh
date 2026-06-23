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

echo "==> Running unit tests (release + debug)"
./gradlew testReleaseUnitTest testDebugUnitTest

echo "==> Building debug APK (sideload / in-app updater channel)"
./gradlew assembleDebug -x lintVitalRelease

echo "==> Building release APK and AAB"
./gradlew assembleRelease bundleRelease -x lintVitalRelease "${GRADLE_ARGS[@]}"

APK_DIR="${ROOT}/app/build/outputs/apk/release"
BUNDLE_DIR="${ROOT}/app/build/outputs/bundle/release"
RELEASE_DIR="${ROOT}/release"
mkdir -p "${RELEASE_DIR}"

# Copy artifacts with stable names (match STEPDADDY_VERSION / app defaultConfig)
_version_file="${ROOT}/VERSION"
if [[ -f "${ROOT}/STEPDADDY_VERSION" ]]; then
  _version_file="${ROOT}/STEPDADDY_VERSION"
elif [[ -f "${ROOT}/../STEPDADDY_VERSION" ]]; then
  _version_file="${ROOT}/../STEPDADDY_VERSION"
fi
VERSION_NAME="$(grep -E '^STEPDADDY_VERSION=' "${_version_file}" | head -1 | cut -d= -f2-)"
VERSION_CODE="$(grep -E '^VERSION_CODE=' "${_version_file}" | head -1 | cut -d= -f2-)"

DEBUG_APK_DIR="${ROOT}/app/build/outputs/apk/debug"
DEBUG_APK="${DEBUG_APK_DIR}/app-debug.apk"
DEBUG_RELEASE_NAME="stepdaddy-gateway-${VERSION_NAME}-debug.apk"

if [[ -f "${DEBUG_APK}" ]]; then
  cp "${DEBUG_APK}" "${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
  echo "==> Debug APK (sideload/updater): ${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
else
  echo "ERROR: Debug APK not found at ${DEBUG_APK}" >&2
  exit 1
fi

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

# Generate update manifest for GitHub Releases (debug APK — matches sideload package ID)
MANIFEST="${RELEASE_DIR}/update-manifest.json"
APK_URL_PLACEHOLDER="https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v${VERSION_NAME}/${DEBUG_RELEASE_NAME}"
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
echo "  Sideload APK:     ${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
echo "  Release outputs:  ${RELEASE_DIR}/"

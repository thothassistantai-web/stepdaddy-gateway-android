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
  STORE_FILE="$(grep -E '^storeFile=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
  if [[ "${STORE_FILE}" != /* ]]; then
    STORE_FILE="${ROOT}/${STORE_FILE}"
  fi
  GRADLE_ARGS+=(-Pandroid.injected.signing.store.file="${STORE_FILE}")
  GRADLE_ARGS+=(-Pandroid.injected.signing.store.password="$(grep -E '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
  GRADLE_ARGS+=(-Pandroid.injected.signing.key.alias="$(grep -E '^keyAlias=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
  GRADLE_ARGS+=(-Pandroid.injected.signing.key.password="$(grep -E '^keyPassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)")
else
  echo "==> No keystore.properties — assembleRelease will produce UNSIGNED APK"
  echo "    Create keystore.properties or sign manually (see docs/RELEASE.md)"
fi

echo "==> Running unit tests (release + debug)"
bash ./gradlew testReleaseUnitTest testDebugUnitTest

echo "==> Building debug APK (sideload / in-app updater channel)"
bash ./gradlew assembleDebug -x lintVitalRelease

echo "==> Building release APK and AAB"
bash ./gradlew assembleRelease bundleRelease -x lintVitalRelease "${GRADLE_ARGS[@]}"

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
RELEASE_RELEASE_NAME="stepdaddy-gateway-${VERSION_NAME}-release.apk"

# Versionless names → GitHub /releases/latest/download/... for permanent AFTV codes
DEBUG_STABLE_NAME="stepdaddy-gateway-debug.apk"
RELEASE_STABLE_NAME="stepdaddy-gateway-release.apk"

if [[ -f "${DEBUG_APK}" ]]; then
  cp "${DEBUG_APK}" "${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
  cp "${DEBUG_APK}" "${RELEASE_DIR}/${DEBUG_STABLE_NAME}"
  echo "==> Debug APK (dev channel): ${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
  echo "==> Debug APK (stable name): ${RELEASE_DIR}/${DEBUG_STABLE_NAME}"
else
  echo "ERROR: Debug APK not found at ${DEBUG_APK}" >&2
  exit 1
fi

if [[ -f "${APK_DIR}/app-release.apk" ]]; then
  cp "${APK_DIR}/app-release.apk" "${RELEASE_DIR}/${RELEASE_RELEASE_NAME}"
  cp "${APK_DIR}/app-release.apk" "${RELEASE_DIR}/${RELEASE_STABLE_NAME}"
  echo "==> Signed release APK: ${RELEASE_DIR}/${RELEASE_RELEASE_NAME}"
  echo "==> Release APK (stable name): ${RELEASE_DIR}/${RELEASE_STABLE_NAME}"
elif [[ -f "${APK_DIR}/app-release-unsigned.apk" ]]; then
  cp "${APK_DIR}/app-release-unsigned.apk" "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-release-unsigned.apk"
  echo "==> Unsigned release APK: ${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-release-unsigned.apk"
  echo "    (skipping versionless stable name — unsigned builds should not be AFTV targets)"
else
  echo "ERROR: No release APK found in ${APK_DIR}" >&2
  exit 1
fi

if [[ -f "${BUNDLE_DIR}/app-release.aab" ]]; then
  cp "${BUNDLE_DIR}/app-release.aab" "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
  echo "==> AAB: ${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
fi

# Generate update manifest for GitHub Releases (stable OTA → release APK; debug via apkUrlDebug)
MANIFEST="${RELEASE_DIR}/update-manifest.json"
RELEASE_APK_URL="https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v${VERSION_NAME}/${RELEASE_RELEASE_NAME}"
DEBUG_APK_URL="https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v${VERSION_NAME}/${DEBUG_RELEASE_NAME}"
sha256_of() {
  if [[ -f "$1" ]]; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo ""
  fi
}
APK_SHA256="$(sha256_of "${RELEASE_DIR}/${RELEASE_RELEASE_NAME}")"
if [[ -z "${APK_SHA256}" ]]; then
  APK_SHA256="$(sha256_of "${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-release-unsigned.apk")"
fi
APK_SHA256_DEBUG="$(sha256_of "${RELEASE_DIR}/${DEBUG_RELEASE_NAME}")"
RELEASE_NOTES="See CHANGELOG.md"
NOTES_FILE="${RELEASE_DIR}/RELEASE-NOTES-${VERSION_NAME}.md"
if [[ -f "${NOTES_FILE}" ]]; then
  RELEASE_NOTES="$(python3 -c '
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
m = re.search(r"(?m)^- \\*\\*.+\\*\\* — .+$", text)
print(m.group(0).lstrip("- ").strip() if m else "See CHANGELOG.md")
' "${NOTES_FILE}")"
fi
VERSION_CODE="${VERSION_CODE}" VERSION_NAME="${VERSION_NAME}" \
RELEASE_APK_URL="${RELEASE_APK_URL}" DEBUG_APK_URL="${DEBUG_APK_URL}" \
APK_SHA256="${APK_SHA256}" APK_SHA256_DEBUG="${APK_SHA256_DEBUG}" \
RELEASE_NOTES="${RELEASE_NOTES}" MANIFEST="${MANIFEST}" \
python3 - <<'PY'
import json, os
from pathlib import Path
manifest = {
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "apkUrl": os.environ["RELEASE_APK_URL"],
    "apkUrlDebug": os.environ["DEBUG_APK_URL"],
    "apkSha256": os.environ.get("APK_SHA256", ""),
    "apkSha256Debug": os.environ.get("APK_SHA256_DEBUG", ""),
    "releaseNotes": os.environ.get("RELEASE_NOTES", "See CHANGELOG.md"),
    "mandatory": False,
}
Path(os.environ["MANIFEST"]).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"==> Update manifest: {os.environ['MANIFEST']}")
PY

# Refresh AFTV Downloader code docs (codes themselves are permanent; see release/AFTV-CODES.md)
if [[ -f "${ROOT}/scripts/aftv-shortener.sh" ]]; then
  bash "${ROOT}/scripts/aftv-shortener.sh" --regen || true
fi

echo ""
echo "Build complete."
echo "  Debug APK (dev):    app/build/outputs/apk/debug/app-debug.apk"
echo "  Debug sideload:     ${RELEASE_DIR}/${DEBUG_RELEASE_NAME}"
echo "  Debug stable name:  ${RELEASE_DIR}/${DEBUG_STABLE_NAME}"
echo "  Release sideload:   ${RELEASE_DIR}/${RELEASE_RELEASE_NAME}"
echo "  Release stable:     ${RELEASE_DIR}/${RELEASE_STABLE_NAME}"
echo "  Release outputs:    ${RELEASE_DIR}/"
echo "  Publish to GitHub:  ./scripts/publish-github-release.sh"
echo "  AFTV codes:         release/AFTV-CODES.md"

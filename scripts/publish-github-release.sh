#!/usr/bin/env bash
# Create or update a GitHub Release with versioned + versionless APK assets
# and AFTV Downloader code documentation.
#
# Prerequisites: gh auth, artifacts from ./scripts/build-release.sh
#
# Usage:
#   ./scripts/publish-github-release.sh           # create/update v$VERSION
#   ./scripts/publish-github-release.sh --assets-only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

_version_file="${ROOT}/STEPDADDY_VERSION"
if [[ ! -f "${_version_file}" ]]; then
  _version_file="${ROOT}/VERSION"
fi
VERSION_NAME="$(grep -E '^STEPDADDY_VERSION=' "${_version_file}" | head -1 | cut -d= -f2-)"
VERSION_CODE="$(grep -E '^VERSION_CODE=' "${_version_file}" | head -1 | cut -d= -f2-)"
TAG="v${VERSION_NAME}"
RELEASE_DIR="${ROOT}/release"

DEBUG_VERSIONED="${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-debug.apk"
RELEASE_VERSIONED="${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}-release.apk"
DEBUG_STABLE="${RELEASE_DIR}/stepdaddy-gateway-debug.apk"
RELEASE_STABLE="${RELEASE_DIR}/stepdaddy-gateway-release.apk"
MANIFEST="${RELEASE_DIR}/update-manifest.json"
NOTES_FILE="${RELEASE_DIR}/RELEASE-NOTES-${VERSION_NAME}.md"
AFTV_MD="${RELEASE_DIR}/AFTV-CODES.md"
AFTV_JSON="${RELEASE_DIR}/aftv-codes.json"

ASSETS_ONLY=0
if [[ "${1:-}" == "--assets-only" ]]; then
  ASSETS_ONLY=1
fi

if [[ ! -f "${DEBUG_VERSIONED}" || ! -f "${RELEASE_VERSIONED}" ]]; then
  echo "ERROR: Missing versioned APKs. Run ./scripts/build-release.sh first." >&2
  exit 1
fi

# Always refresh versionless copies from the versioned build outputs
cp -f "${DEBUG_VERSIONED}" "${DEBUG_STABLE}"
cp -f "${RELEASE_VERSIONED}" "${RELEASE_STABLE}"
echo "==> Versionless assets refreshed"

# Refresh AFTV docs (does not invent codes — reads release/aftv-codes.json)
bash "${ROOT}/scripts/aftv-shortener.sh" --regen

BODY_FILE="$(mktemp)"
cleanup() { rm -f "${BODY_FILE}"; }
trap cleanup EXIT

{
  if [[ -f "${NOTES_FILE}" ]]; then
    cat "${NOTES_FILE}"
    echo ""
  else
    echo "# StepDaddy Gateway ${VERSION_NAME}"
    echo ""
    echo "versionCode: ${VERSION_CODE}"
    echo ""
  fi
  echo ""
  bash "${ROOT}/scripts/aftv-shortener.sh" --release-notes-snippet
  echo ""
  echo "---"
  echo "Assets: versioned APKs + versionless \`stepdaddy-gateway-release.apk\` / \`stepdaddy-gateway-debug.apk\` for AFTV stable URLs + \`update-manifest.json\`."
} >"${BODY_FILE}"

upload_assets() {
  local args=(
    "${DEBUG_VERSIONED}"
    "${RELEASE_VERSIONED}"
    "${DEBUG_STABLE}"
    "${RELEASE_STABLE}"
  )
  if [[ -f "${MANIFEST}" ]]; then
    args+=("${MANIFEST}")
  fi
  if [[ -f "${AFTV_MD}" ]]; then
    args+=("${AFTV_MD}")
  fi
  if [[ -f "${AFTV_JSON}" ]]; then
    args+=("${AFTV_JSON}")
  fi
  local aab="${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
  if [[ -f "${aab}" ]]; then
    args+=("${aab}")
  fi
  gh release upload "${TAG}" "${args[@]}" --clobber
}

if [[ "${ASSETS_ONLY}" -eq 1 ]]; then
  if ! gh release view "${TAG}" >/dev/null 2>&1; then
    echo "ERROR: Release ${TAG} does not exist; cannot --assets-only" >&2
    exit 1
  fi
  echo "==> Uploading/replacing assets on ${TAG}"
  upload_assets
  gh release edit "${TAG}" --notes-file "${BODY_FILE}"
else
  if gh release view "${TAG}" >/dev/null 2>&1; then
    echo "==> Release ${TAG} exists — updating notes + assets"
    gh release edit "${TAG}" --notes-file "${BODY_FILE}"
    upload_assets
  else
    echo "==> Creating release ${TAG}"
    # Prefer an existing annotated tag; otherwise create from HEAD
    if ! git rev-parse "${TAG}" >/dev/null 2>&1; then
      git tag -a "${TAG}" -m "StepDaddy Gateway ${VERSION_NAME}"
      git push origin "${TAG}"
    fi
    create_args=(
      "${TAG}"
      --title "StepDaddy Gateway ${VERSION_NAME}"
      --notes-file "${BODY_FILE}"
      "${DEBUG_VERSIONED}"
      "${RELEASE_VERSIONED}"
      "${DEBUG_STABLE}"
      "${RELEASE_STABLE}"
    )
    [[ -f "${MANIFEST}" ]] && create_args+=("${MANIFEST}")
    [[ -f "${AFTV_MD}" ]] && create_args+=("${AFTV_MD}")
    [[ -f "${AFTV_JSON}" ]] && create_args+=("${AFTV_JSON}")
    aab="${RELEASE_DIR}/stepdaddy-gateway-${VERSION_NAME}.aab"
    [[ -f "${aab}" ]] && create_args+=("${aab}")
    gh release create "${create_args[@]}"
  fi
fi

echo ""
echo "Published ${TAG}"
echo "  Latest release APK: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-release.apk"
echo "  Latest debug APK:   https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-debug.apk"
bash "${ROOT}/scripts/aftv-shortener.sh" --verify || true
bash "${ROOT}/scripts/aftv-shortener.sh" --release-notes-snippet

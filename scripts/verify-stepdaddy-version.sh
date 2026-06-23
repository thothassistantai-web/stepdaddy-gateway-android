#!/usr/bin/env bash
# Verify StepDaddy suite version alignment within stepdaddy-android (and monorepo parent when present).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CANONICAL=""
if [[ -f "${ROOT}/STEPDADDY_VERSION" ]]; then
  CANONICAL="${ROOT}/STEPDADDY_VERSION"
elif [[ -f "${ROOT}/../STEPDADDY_VERSION" ]]; then
  CANONICAL="${ROOT}/../STEPDADDY_VERSION"
else
  echo "ERROR: STEPDADDY_VERSION missing in ${ROOT} and ${ROOT}/.." >&2
  exit 1
fi

EXPECTED_VERSION=""
EXPECTED_CODE=""
failures=0

read_prop() {
  local file="$1"
  local key="$2"
  grep -E "^${key}=" "${file}" | head -1 | cut -d= -f2- | tr -d '\r'
}

check_contains() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if [[ ! -f "${file}" ]]; then
    echo "FAIL  missing ${label}: ${file}"
    failures=$((failures + 1))
    return
  fi
  if ! grep -q "${pattern}" "${file}"; then
    echo "FAIL  ${label}: expected pattern '${pattern}' in ${file}"
    failures=$((failures + 1))
    return
  fi
  echo "OK    ${label}"
}

EXPECTED_VERSION="$(read_prop "${CANONICAL}" STEPDADDY_VERSION)"
EXPECTED_CODE="$(read_prop "${CANONICAL}" VERSION_CODE)"

if [[ -z "${EXPECTED_VERSION}" || -z "${EXPECTED_CODE}" ]]; then
  echo "ERROR: STEPDADDY_VERSION or VERSION_CODE missing in ${CANONICAL}" >&2
  exit 1
fi

echo "==> Expecting STEPDADDY_VERSION=${EXPECTED_VERSION} VERSION_CODE=${EXPECTED_CODE}"
echo "==> Canonical: ${CANONICAL}"
echo

check_contains "${CANONICAL}" "STEPDADDY_VERSION=${EXPECTED_VERSION}" "STEPDADDY_VERSION"
check_contains "${CANONICAL}" "VERSION_CODE=${EXPECTED_CODE}" "STEPDADDY_VERSION code"
check_contains "${ROOT}/VERSION" "STEPDADDY_VERSION=${EXPECTED_VERSION}" "VERSION"
check_contains "${ROOT}/app/build.gradle.kts" "readStepdaddyVersionProp(\"STEPDADDY_VERSION\", \"${EXPECTED_VERSION}\")" "gradle STEPDADDY_VERSION default"
check_contains "${ROOT}/app/build.gradle.kts" "readStepdaddyVersionProp(\"VERSION_CODE\", \"${EXPECTED_CODE}\")" "gradle VERSION_CODE default"
check_contains "${ROOT}/app/build.gradle.kts" "tivimate-daddy-v\${stepdaddyVersionName}" "gradle tivimate release tag"
check_contains "${ROOT}/release/update-manifest.json" "\"versionName\": \"${EXPECTED_VERSION}\"" "update-manifest versionName"
check_contains "${ROOT}/release/update-manifest.json" "\"versionCode\": ${EXPECTED_CODE}" "update-manifest versionCode"
check_contains "${ROOT}/release/update-manifest.json" "/v${EXPECTED_VERSION}/" "update-manifest apkUrl tag"
check_contains "${ROOT}/app/src/main/assets/install_apps_catalog.json" "\"version\": \"${EXPECTED_VERSION}\"" "install_apps_catalog TiviMate version"
check_contains "${ROOT}/app/src/main/assets/install_apps_catalog.json" "tivimate-daddy-v${EXPECTED_VERSION}" "install_apps_catalog apkUrl tag"

if [[ -f "${ROOT}/../STEPDADDY_VERSION" && "${CANONICAL}" != "${ROOT}/../STEPDADDY_VERSION" ]]; then
  check_contains "${ROOT}/../STEPDADDY_VERSION" "STEPDADDY_VERSION=${EXPECTED_VERSION}" "monorepo parent STEPDADDY_VERSION"
fi

echo
if [[ "${failures}" -gt 0 ]]; then
  echo "FAILED: ${failures} check(s)"
  exit 1
fi
echo "All stepdaddy-android version touchpoints agree on ${EXPECTED_VERSION} / ${EXPECTED_CODE}"

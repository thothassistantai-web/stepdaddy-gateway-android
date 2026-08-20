#!/usr/bin/env bash
# Restore keystore.properties passwords from Linux keyring when present.
# Usage: source scripts/keystore-from-keyring.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE_FILE="${ROOT}/keystore/stepdaddy-release.jks"
if [[ ! -f "${STORE_FILE}" ]]; then
  echo "Missing ${STORE_FILE} — restore the .jks from backup first (see docs/KEYSTORE-BACKUP.md)" >&2
  return 1 2>/dev/null || exit 1
fi
STORE_PASSWORD="$(secret-tool lookup application stepdaddy-gateway attribute store-password || true)"
KEY_PASSWORD="$(secret-tool lookup application stepdaddy-gateway attribute key-password || true)"
KEY_ALIAS="$(secret-tool lookup application stepdaddy-gateway attribute key-alias || true)"
if [[ -z "${STORE_PASSWORD}" || -z "${KEY_ALIAS}" ]]; then
  echo "Keyring lookup failed — create keystore.properties manually" >&2
  return 1 2>/dev/null || exit 1
fi
KEY_PASSWORD="${KEY_PASSWORD:-$STORE_PASSWORD}"
umask 077
cat > "${ROOT}/keystore.properties" <<EOF
storeFile=keystore/stepdaddy-release.jks
storePassword=${STORE_PASSWORD}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASSWORD}
EOF
chmod 600 "${ROOT}/keystore.properties"
echo "Wrote ${ROOT}/keystore.properties from keyring"

#!/usr/bin/env bash
# Manage AFTV Downloader numeric codes for StepDaddy Gateway APKs.
#
# AFTVnews (go.aftvnews.com / aftv.news) has NO public API and uses reCAPTCHA.
# Destination URLs cannot be edited after creation. Strategy:
#   1. Publish versionless assets each release (stepdaddy-gateway-release.apk / -debug.apk)
#   2. One-time: register those stable latest/download URLs at go.aftvnews.com
#   3. Store the permanent numeric codes here; republish them on every GitHub release
#
# Usage:
#   ./scripts/aftv-shortener.sh                  # regenerate docs from JSON
#   ./scripts/aftv-shortener.sh --verify         # HEAD-check stable URLs
#   ./scripts/aftv-shortener.sh --set-codes RELEASE_CODE DEBUG_CODE
#   AFTV_CODE_RELEASE=12345 AFTV_CODE_DEBUG=67890 ./scripts/aftv-shortener.sh --from-env
#   ./scripts/aftv-shortener.sh --release-notes-snippet   # print markdown for release body
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODES_JSON="${ROOT}/release/aftv-codes.json"
CODES_MD="${ROOT}/release/AFTV-CODES.md"
REPO_SLUG="${AFTV_REPO_SLUG:-thothassistantai-web/stepdaddy-gateway-android}"
STABLE_RELEASE_URL="https://github.com/${REPO_SLUG}/releases/latest/download/stepdaddy-gateway-release.apk"
STABLE_DEBUG_URL="https://github.com/${REPO_SLUG}/releases/latest/download/stepdaddy-gateway-debug.apk"

need_jq() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 required" >&2
    exit 1
  fi
}

read_code() {
  local key="$1"
  python3 - "$CODES_JSON" "$key" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
data = json.loads(open(path, encoding="utf-8").read())
print((data.get("codes") or {}).get(key) or "")
PY
}

write_codes_json() {
  local release_code="$1" debug_code="$2"
  local quiet="${3:-0}"
  RELEASE_CODE="${release_code}" DEBUG_CODE="${debug_code}" \
  STABLE_RELEASE_URL="${STABLE_RELEASE_URL}" STABLE_DEBUG_URL="${STABLE_DEBUG_URL}" \
  CODES_JSON="${CODES_JSON}" QUIET="${quiet}" python3 - <<'PY'
import json, os
from datetime import datetime, timezone
from pathlib import Path

path = Path(os.environ["CODES_JSON"])
data = {}
if path.exists():
    data = json.loads(path.read_text(encoding="utf-8"))

data.update({
    "service": "aftvnews",
    "shortenerHome": "https://go.aftvnews.com/",
    "shortUrlBase": "https://aftv.news/",
    "notes": (
        "AFTVnews has no public API and cannot edit destination URLs. "
        "Codes point at GitHub latest/download versionless assets so one "
        "static code always resolves to the newest APK."
    ),
    "stableUrls": {
        "release": os.environ["STABLE_RELEASE_URL"],
        "debug": os.environ["STABLE_DEBUG_URL"],
    },
    "codes": {
        "release": os.environ.get("RELEASE_CODE", "").strip(),
        "debug": os.environ.get("DEBUG_CODE", "").strip(),
    },
    "packages": {
        "release": "com.thothassistant.stepdaddy.gateway",
        "debug": "com.thothassistant.stepdaddy.gateway.debug",
    },
    "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
})
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
if os.environ.get("QUIET") != "1":
    print(f"==> Wrote {path}")
PY
}

write_codes_md() {
  local quiet="${1:-0}"
  local release_code debug_code
  release_code="$(read_code release)"
  debug_code="$(read_code debug)"
  local release_display="${release_code:-TBD (register once — see below)}"
  local debug_display="${debug_code:-TBD (register once — see below)}"
  local release_short=""
  local debug_short=""
  if [[ -n "${release_code}" ]]; then
    release_short="https://aftv.news/${release_code}"
  else
    release_short="(pending)"
  fi
  if [[ -n "${debug_code}" ]]; then
    debug_short="https://aftv.news/${debug_code}"
  else
    debug_short="(pending)"
  fi

  cat >"${CODES_MD}" <<EOF
# AFTV Downloader codes

Enter these **numeric codes** in the [Downloader](https://www.aftvnews.com/downloader/) app (AFTVnews) on Fire TV / Android TV. Do **not** type the full URL unless Downloader asks for one.

| Build | Downloader code | Package | Stable APK URL |
|-------|-----------------|---------|----------------|
| **Release** (production) | \`${release_display}\` | \`com.thothassistant.stepdaddy.gateway\` | [stepdaddy-gateway-release.apk](${STABLE_RELEASE_URL}) |
| **Debug** (dev / OTA bridge) | \`${debug_display}\` | \`com.thothassistant.stepdaddy.gateway.debug\` | [stepdaddy-gateway-debug.apk](${STABLE_DEBUG_URL}) |

Short links (optional): release \`${release_short}\` · debug \`${debug_short}\`

## How it works

1. Each GitHub release uploads **versionless** assets (\`stepdaddy-gateway-release.apk\`, \`stepdaddy-gateway-debug.apk\`) alongside the versioned APKs.
2. GitHub’s \`/releases/latest/download/<name>\` always redirects to those assets on the newest release.
3. AFTVnews shortener codes are created **once** pointing at those stable URLs (AFTVnews cannot edit destinations and has no public API / captcha gate).
4. This file + \`release/aftv-codes.json\` are republished in every release body so themes / install docs stay current.

Machine-readable: [\`aftv-codes.json\`](./aftv-codes.json) · Docs: [\`docs/AFTV-DOWNLOADER.md\`](../docs/AFTV-DOWNLOADER.md)

## One-time registration (maintainer)

Only needed when codes are empty or you intentionally rotate to new stable URLs:

1. Open https://go.aftvnews.com/
2. Shorten: \`${STABLE_RELEASE_URL}\` → copy the numeric code
3. Shorten: \`${STABLE_DEBUG_URL}\` → copy the numeric code
4. Save them:

\`\`\`bash
bash scripts/aftv-shortener.sh --set-codes <RELEASE_CODE> <DEBUG_CODE>
git add release/aftv-codes.json release/AFTV-CODES.md
git commit -m "docs: record permanent AFTV Downloader codes"
\`\`\`

Do **not** re-shorten the same URL (AFTVnews returns the existing code). Do **not** point codes at versioned asset URLs.

## Verify stable downloads

\`\`\`bash
bash scripts/aftv-shortener.sh --verify
\`\`\`
EOF
  if [[ "${quiet}" != "1" ]]; then
    echo "==> Wrote ${CODES_MD}"
  fi
}

release_notes_snippet() {
  local release_code debug_code
  release_code="$(read_code release)"
  debug_code="$(read_code debug)"
  cat <<EOF
## AFTV Downloader (Fire TV)

Enter the code in **Downloader by AFTVnews** (numbers only):

| Build | Code | Latest APK |
|-------|------|------------|
| Release | ${release_code:-TBD — see release/AFTV-CODES.md} | ${STABLE_RELEASE_URL} |
| Debug | ${debug_code:-TBD — see release/AFTV-CODES.md} | ${STABLE_DEBUG_URL} |

Codes are permanent and always resolve to the newest release via versionless GitHub assets. Details: \`release/AFTV-CODES.md\`.
EOF
}

verify_urls() {
  local url status
  for url in "${STABLE_RELEASE_URL}" "${STABLE_DEBUG_URL}"; do
    status="$(curl -sI -o /dev/null -w '%{http_code}' -L --max-redirs 5 "${url}" || true)"
    # GitHub often returns 302 then 200 for the asset; -L follows. 200/302 ok at first hop too.
    first="$(curl -sI -o /dev/null -w '%{http_code}' "${url}" || true)"
    echo "  ${url}"
    echo "    first-hop=${first}  follow-redirects=${status}"
    if [[ "${first}" != "302" && "${first}" != "200" ]]; then
      echo "ERROR: expected 302/200 from GitHub latest/download; got ${first}" >&2
      echo "       Upload versionless assets with ./scripts/publish-github-release.sh" >&2
      exit 1
    fi
  done
  echo "==> Stable URLs OK"
}

ensure_json() {
  local quiet="${1:-0}"
  if [[ ! -f "${CODES_JSON}" ]]; then
    write_codes_json "" "" "${quiet}"
  else
    # Keep stable URL fields in sync with repo slug defaults
    local r d
    r="$(read_code release)"
    d="$(read_code debug)"
    write_codes_json "${r}" "${d}" "${quiet}"
  fi
}

usage() {
  sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
}

main() {
  need_jq
  case "${1:-}" in
    ""|--regen)
      ensure_json
      write_codes_md
      ;;
    --set-codes)
      if [[ $# -lt 3 ]]; then
        echo "Usage: $0 --set-codes <RELEASE_CODE> <DEBUG_CODE>" >&2
        exit 1
      fi
      write_codes_json "$2" "$3"
      write_codes_md
      ;;
    --from-env)
      write_codes_json "${AFTV_CODE_RELEASE:-}" "${AFTV_CODE_DEBUG:-}"
      write_codes_md
      ;;
    --verify)
      ensure_json
      write_codes_md
      verify_urls
      ;;
    --release-notes-snippet)
      ensure_json 1
      write_codes_md 1
      release_notes_snippet
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "$@"

#!/usr/bin/env bash
# Export channelId -> xmltvChannelId mapping for the Android gateway asset.
# Prefers stepdaddy-web local API; falls back to epg_overrides.json + channels cache.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

resolve_web_root() {
  if [ -n "${STEPDADDY_WEB_ROOT:-}" ] && [ -d "$STEPDADDY_WEB_ROOT" ]; then
    printf '%s\n' "$STEPDADDY_WEB_ROOT"
    return
  fi
  if [ -n "${STEPDADDY_APP_ROOT:-}" ] && [ -d "$STEPDADDY_APP_ROOT" ]; then
    printf '%s\n' "$STEPDADDY_APP_ROOT"
    return
  fi
  if [ -d "$HOME/Programs/stepdaddy-web" ]; then
    printf '%s\n' "$HOME/Programs/stepdaddy-web"
    return
  fi
  if [ -d "$ANDROID_ROOT/../stepdaddy-web" ]; then
    cd "$ANDROID_ROOT/../stepdaddy-web" && pwd
    return
  fi
  printf '%s\n' "$HOME/Programs/stepdaddy-web"
}

WEB_ROOT="$(resolve_web_root)"
OUT="$ANDROID_ROOT/app/src/main/assets/channel_epg_map.json"
API_URL="${STEPDADDY_API_URL:-http://127.0.0.1:3000}"

mkdir -p "$(dirname "$OUT")"

export_from_api() {
  local tmp
  tmp="$(mktemp)"
  if ! curl -fsS --connect-timeout 5 "$API_URL/settings/mappings/export?format=json" -o "$tmp"; then
    rm -f "$tmp"
    return 1
  fi
  if ! python3 -c "import json; json.load(open('$tmp'))" 2>/dev/null; then
    rm -f "$tmp"
    return 1
  fi
  mv "$tmp" "$OUT"
  return 0
}

export_from_files() {
  python3 - "$WEB_ROOT" "$OUT" <<'PY'
import json
import sys
from pathlib import Path

web_root = Path(sys.argv[1])
out = Path(sys.argv[2])
overrides_path = web_root / "app" / "epg_overrides.json"
cache_path = web_root / "app" / "dlhd_channels_cache.json"

overrides = json.loads(overrides_path.read_text(encoding="utf-8"))
cache = json.loads(cache_path.read_text(encoding="utf-8"))
channels = cache.get("channels") or cache

mapping = {}
for ch in channels:
    cid = str(ch.get("channel_id") or ch.get("id") or "")
    if not cid:
        continue
    tvg = overrides.get(cid) or ch.get("tvg_id") or ch.get("xmltv_id")
    if tvg:
        mapping[cid] = tvg

out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(mapping, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Wrote {len(mapping)} mappings to {out}")
PY
}

if export_from_api; then
  echo "Exported mapping from $API_URL -> $OUT"
  exit 0
fi

echo "API unavailable at $API_URL — falling back to stepdaddy-web files" >&2
export_from_files

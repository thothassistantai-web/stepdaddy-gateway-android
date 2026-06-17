#!/usr/bin/env bash
# Export channelId -> xmltvChannelId mapping for the Android gateway asset.
# Prefers stepdaddy-app local API; falls back to epg_overrides.json + channels cache.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_ROOT="${STEPDADDY_APP_ROOT:-$(cd "$ANDROID_ROOT/../stepdaddy-app" && pwd)}"
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
  python3 - "$tmp" "$OUT" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
data = json.load(open(src, encoding="utf-8"))
mapping = {}
for row in data.get("mappings") or []:
    cid = str(row.get("id") or "").strip()
    tvg = (row.get("tvg_id") or "").strip()
    if cid and tvg:
        mapping[cid] = tvg
overrides = data.get("overrides") or {}
payload = {
    "exported_at": data.get("exported_at"),
    "source": "api",
    "mapped_count": len(mapping),
    "override_count": len(overrides),
    "mapping": mapping,
}
with open(dst, "w", encoding="utf-8") as f:
    json.dump(payload, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"exported {len(mapping)} mappings from API -> {dst}")
PY
  rm -f "$tmp"
}

export_from_files() {
  python3 - "$APP_ROOT" "$OUT" <<'PY'
import json, sys
from pathlib import Path
app_root = Path(sys.argv[1])
dst = Path(sys.argv[2])
overrides_path = app_root / "app" / "epg_overrides.json"
cache_path = app_root / "app" / "dlhd_channels_cache.json"
overrides = {}
if overrides_path.exists():
    overrides = json.loads(overrides_path.read_text(encoding="utf-8"))
mapping = {}
if cache_path.exists():
    cache = json.loads(cache_path.read_text(encoding="utf-8"))
    for ch in cache.get("channels") or []:
        cid = str(ch.get("id") or "").strip()
        name = (ch.get("name") or "").strip()
        tvg = (ch.get("tvg_id") or overrides.get(name) or "").strip()
        if cid and tvg:
            mapping[cid] = tvg
for name, tvg in overrides.items():
  # name-keyed overrides without channel id are skipped in file fallback
  pass
payload = {
    "exported_at": None,
    "source": "files",
    "mapped_count": len(mapping),
    "override_count": len(overrides),
    "mapping": mapping,
}
dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"exported {len(mapping)} mappings from files -> {dst}")
PY
}

if export_from_api; then
  exit 0
fi
echo "API unavailable at $API_URL — falling back to stepdaddy-app files" >&2
export_from_files

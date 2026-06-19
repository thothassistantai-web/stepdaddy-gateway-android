#!/usr/bin/env bash
# Generate merged iptv-org FAST provider EPG for StepDaddy gateway supplements.
# Sites: pluto.tv, plex.tv, xumo.tv, distro.tv (matches iptv-org M3U tvg-id namespace).
#
# Usage:
#   ./scripts/grab-iptv-org-fast-epg.sh
#   ./scripts/grab-iptv-org-fast-epg.sh /path/to/output.xml.gz
#
# Output defaults to:
#   app/src/main/assets/epg/iptv_org_fast_epg.xml.gz
#
# Host the same file on GitHub raw and set Gateway Settings → iptv-org EPG URL for OTA updates.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app/src/main/assets/epg/iptv_org_fast_epg.xml.gz}"
WORKDIR="${TMPDIR:-/tmp}/stepdaddy-iptv-org-epg-$$"
SITES="pluto.tv,plex.tv,xumo.tv,distro.tv"

mkdir -p "$(dirname "$OUT")"
rm -rf "$WORKDIR"
git clone --depth 1 https://github.com/iptv-org/epg.git "$WORKDIR"

cd "$WORKDIR"
npm install --silent

PLAIN="${OUT%.gz}"
if [[ "$PLAIN" == "$OUT" ]]; then
  PLAIN="${OUT}.xml"
  OUT="${OUT}.gz"
fi

echo "Grabbing $SITES (2 days, maxConnections=2)…"
npm run grab -- \
  --sites="$SITES" \
  --output="$PLAIN" \
  --gzip="$OUT" \
  --days=2 \
  --maxConnections=2

ls -lh "$OUT"
python3 - <<PY
import gzip,re,sys
with gzip.open("$OUT","rt",errors="replace") as f:
    d=f.read()
ids=re.findall(r'<channel[^>]+id="([^"]+)"', d)
print(f"channels={len(ids)} programmes={len(re.findall(r'<programme ', d))}")
for n in ["ABCNewsLive.us@SD","48Hours.us@US","48Hours.us@SD"]:
    print(f"  {n}: {'yes' if n in d else 'no'}")
PY

echo "Done: $OUT"
echo "Rebuild APK or set iptvOrgEpgUrl in gateway settings to host this file for OTA refresh."

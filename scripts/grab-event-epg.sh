#!/usr/bin/env bash
# Off-device event / PPV EPG supplements (Peacock lanes, NHL schedule, iptv-org sports).
#
# Run on a desktop/CI host — not on the ONN stick. Output is merged by the gateway
# as a supplement gzip (host on GitHub raw and set iptvOrgEpgUrl or sidecar EPG URL).
#
# Usage:
#   ./scripts/grab-event-epg.sh
#   ./scripts/grab-event-epg.sh /path/to/event_epg.xml.gz
#
# Dependencies (optional per source):
#   - Docker (PeacockDeepLinks)
#   - python3 + nhl-api-py (NHL schedule)
#   - npm + iptv-org/epg clone (tvtv.us ACCNetwork grab)

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app/src/main/assets/epg/event_epg.dat}"
WORKDIR="${TMPDIR:-/tmp}/stepdaddy-event-epg-$$"
mkdir -p "$(dirname "$OUT")"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

PLAIN="${OUT%.gz}"
if [[ "$PLAIN" == "$OUT" ]]; then
  PLAIN="${OUT}.xml"
  OUT="${OUT}.gz"
fi

PARTS=()

# --- NHL: today's schedule → synthetic XMLTV (api-web.nhle.com) ---
if command -v python3 >/dev/null 2>&1; then
  NHL_XML="$WORKDIR/nhl.xml"
  python3 - <<'PY' "$NHL_XML"
import json, sys, urllib.request, xml.sax.saxutils as x
from datetime import datetime, timezone, timedelta

out_path = sys.argv[1]
today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
url = f"https://api-web.nhle.com/v1/schedule/{today}"
try:
    data = json.load(urllib.request.urlopen(url, timeout=30))
except Exception as exc:
    print(f"NHL schedule skip: {exc}", file=sys.stderr)
    sys.exit(0)

games = []
for day in data.get("gameWeek") or []:
    for g in day.get("games") or []:
        games.append(g)

lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="StepDaddy NHL">']
for i, g in enumerate(games[:31], start=1):
    cid = f"NHLCenterIce{i}.us"
    away = (g.get("awayTeam") or {}).get("abbrev", "?")
    home = (g.get("homeTeam") or {}).get("abbrev", "?")
    title = f"{away} @ {home}"
    start_raw = g.get("startTimeUTC") or ""
    try:
        start = datetime.fromisoformat(start_raw.replace("Z", "+00:00"))
    except Exception:
        start = datetime.now(timezone.utc)
    stop = start + timedelta(hours=3)
    fmt = lambda dt: dt.strftime("%Y%m%d%H%M%S +0000")
    lines.append(f'<channel id="{x.escape(cid)}"><display-name>{x.escape(title)}</display-name></channel>')
    lines.append(
        f'<programme start="{fmt(start)}" stop="{fmt(stop)}" channel="{x.escape(cid)}">'
        f"<title>{x.escape(title)}</title></programme>"
    )
lines.append("</tv>")
open(out_path, "w", encoding="utf-8").write("\n".join(lines))
print(f"NHL: {len(games)} games -> {out_path}")
PY
  if [[ -s "$NHL_XML" ]]; then
    PARTS+=("$NHL_XML")
  fi
fi

# --- PeacockDeepLinks (optional Docker) ---
if command -v docker >/dev/null 2>&1; then
  PEACOCK_XML="$WORKDIR/peacock.xml"
  if docker run --rm kineticman/peacockdeeplinks:latest curl -fsS http://127.0.0.1:8080/lanes/xmltv > "$PEACOCK_XML" 2>/dev/null; then
    PARTS+=("$PEACOCK_XML")
    echo "PeacockDeepLinks: merged"
  else
    echo "PeacockDeepLinks: skipped (docker image unavailable or auth required)" >&2
  fi
fi

# --- iptv-org tvtv.us subset (ACCNetwork, NBC sports) ---
if command -v npm >/dev/null 2>&1 && command -v git >/dev/null 2>&1; then
  EPG_DIR="$WORKDIR/iptv-org-epg"
  git clone --depth 1 https://github.com/iptv-org/epg.git "$EPG_DIR" 2>/dev/null || true
  if [[ -d "$EPG_DIR" ]]; then
    TVTV_XML="$WORKDIR/tvtv.xml"
    (cd "$EPG_DIR" && npm install --silent && npm run grab -- \
      --sites=tvtv.us \
      --lang=en \
      --days=2 \
      --maxConnections=2 \
      --output="$TVTV_XML" 2>/dev/null) || true
    if [[ -s "$TVTV_XML" ]]; then
      PARTS+=("$TVTV_XML")
      echo "tvtv.us: merged"
    fi
  fi
fi

# Merge parts into single guide
python3 - <<PY "$PLAIN" "${PARTS[@]}"
import sys, xml.etree.ElementTree as ET, gzip

out = sys.argv[1]
parts = sys.argv[2:]
root = ET.Element("tv", {"generator-info-name": "StepDaddy Event EPG"})
seen_ch = set()
seen_prog = set()
for path in parts:
    try:
        tree = ET.parse(path)
    except Exception:
        continue
    tv = tree.getroot()
    for ch in tv.findall("channel"):
        cid = ch.get("id")
        if not cid or cid in seen_ch:
            continue
        seen_ch.add(cid)
        root.append(ch)
    for prog in tv.findall("programme"):
        key = (prog.get("channel"), prog.get("start"), prog.get("stop"))
        if key in seen_prog:
            continue
        seen_prog.add(key)
        root.append(prog)
ET.ElementTree(root).write(out, encoding="UTF-8", xml_declaration=True)
print(f"Merged {len(parts)} parts -> {out} ({len(seen_ch)} channels, {len(seen_prog)} programmes)")
PY

gzip -c "$PLAIN" > "$OUT"
ls -lh "$OUT"
echo "Done: $OUT"
echo "Host this file and point gateway sidecar/iptv EPG URL for OTA refresh."

rm -rf "$WORKDIR"

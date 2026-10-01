#!/usr/bin/env bash
# FUSA live-diagnostics probe battery — Movies (+ optional Local).
# Uses gateway /debug/* only (no AFTV Downloader / naked VIEW intents).
set -euo pipefail

DEV="${DEV:-}"
BASE="${BASE:-}"
MOVIES="${MOVIES:-333,794,689}"
LOCAL="${LOCAL:-280,300,921}"
INCLUDE_LOCAL="${INCLUDE_LOCAL:-1}"

pick_device() {
  if [[ -n "$DEV" ]]; then
    echo "$DEV"
    return
  fi
  adb devices -l | awk '/\tdevice/{print $1; exit}'
}

DEV="$(pick_device)"
if [[ -z "$DEV" ]]; then
  echo "No adb device. Set DEV=serial or IP:port" >&2
  exit 1
fi

if [[ -z "$BASE" ]]; then
  IP="$(adb -s "$DEV" shell ip -4 addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print $2}' | head -1 || true)"
  if [[ -n "$IP" ]]; then
    BASE="http://${IP}:3000"
  else
    adb -s "$DEV" forward tcp:3000 tcp:3000 >/dev/null
    BASE="http://127.0.0.1:3000"
  fi
fi

echo "DEV=$DEV BASE=$BASE"

curl -sf -m 8 "$BASE/health?lite=1" >/dev/null || {
  echo "health failed at $BASE" >&2
  exit 1
}

curl -sf -m 8 -X POST "$BASE/debug/counters/reset" >/dev/null || true

IDS="$MOVIES"
if [[ "$INCLUDE_LOCAL" == "1" && -n "$LOCAL" ]]; then
  IDS="${MOVIES},${LOCAL}"
fi

echo "=== cold probes ==="
IFS=',' read -ra CHANS <<< "$IDS"
for id in "${CHANS[@]}"; do
  id="$(echo "$id" | tr -d '[:space:]')"
  [[ -z "$id" ]] && continue
  echo -n "probe $id: "
  curl -s -m 60 "$BASE/debug/probe?id=${id}" || echo '{"ok":false,"detail":"curl_fail"}'
  echo
done

echo "=== diagnostics summary ==="
TMP_DIAG="$(mktemp)"
curl -s -m 10 "$BASE/debug/diagnostics" -o "$TMP_DIAG" || true
python3 - "$TMP_DIAG" <<'PY'
import json,sys
path=sys.argv[1]
try:
    d=json.load(open(path))
except Exception as e:
    print('diagnostics_parse_fail', e)
    sys.exit(0)
s=d.get("streams",{})
t=d.get("tune",{})
m=d.get("memory",{})
print(f"version={d.get('version')} mem_used_mb={m.get('usedMb')}/{m.get('maxMb')}")
print(f"tune contentProxy={t.get('contentProxyMaxConcurrent')} upstream={t.get('upstreamFetchMaxConcurrent')} raceMs={t.get('dlhdRaceTimeoutMs')} source={t.get('source')}")
avg_r=s.get('avgResolveMs') or 0
avg_s=s.get('avgSegmentMs') or 0
print(f"resolve ok={s.get('resolveOk')} fail={s.get('resolveFail')} 504={s.get('resolve504')} avgMs={avg_r:.0f}")
print(f"segment ok={s.get('segmentOk')} fail={s.get('segmentFail')} unwrap={s.get('unwrapHits')} avgMs={avg_s:.0f} proxyBusy={s.get('proxyBusy')}")
for e in (s.get("lastByChannel") or [])[:8]:
    print(f"  ch={e.get('channelId')} http={e.get('httpStatus')} ms={e.get('latencyMs')} ok={e.get('ok')} {e.get('detail')}")
PY
rm -f "$TMP_DIAG"

#!/usr/bin/env bash
# USB-only tier deploy to the FUSA ONN lab stick (FUSA2541006925).
# Build debug APK, install, verify /health and Special Events in /tivimate.m3u.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_SERIAL="${ADB_SERIAL:-FUSA2541006925}"
TIER="${TIER:-1}"
PKG="${PKG:-com.thothassistant.stepdaddy.gateway.debug}"
MAIN="${PKG}/com.thothassistant.stepdaddy.gateway.ui.MainActivity"
SERVICE="${PKG}/com.thothassistant.stepdaddy.gateway.ServerService"
APK="${APK:-${ROOT}/app/build/outputs/apk/debug/app-debug.apk}"
if [[ -n "${STEPDADDY_ISOLATED_BUILD_DIR:-}" && ! -f "$APK" ]]; then
  APK="${STEPDADDY_ISOLATED_BUILD_DIR}/outputs/apk/debug/app-debug.apk"
fi
PORT="${GATEWAY_PORT:-3000}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
HEALTH_POLL_S="${HEALTH_POLL_S:-3}"
SKIP_ASSEMBLE="${SKIP_ASSEMBLE:-0}"

export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"

adb_dev() { command adb -s "$TARGET_SERIAL" "$@"; }

log() { echo "[deploy-tier${TIER}] $*" >&2; }

# Print at most N lines without SIGPIPE under `set -o pipefail`.
sample_lines() {
  local max="$1"
  shift
  sed -n "1,${max}p"
}

die() {
  log "FAIL: $*"
  exit 1
}

verify_usb_only() {
  local line state
  line="$(command adb devices -l | awk -v s="$TARGET_SERIAL" '$1 == s { print; exit }')"
  [[ -n "$line" ]] || die "${TARGET_SERIAL} not listed in adb devices — connect USB and enable debugging"

  state="$(awk '{ print $2 }' <<<"$line")"
  [[ "$state" == "device" ]] || die "${TARGET_SERIAL} state is '${state}' (expected device)"

  if [[ "$line" != *"usb:"* ]]; then
    die "${TARGET_SERIAL} is not on USB transport (refusing install; network/Wi‑Fi ADB blocked)"
  fi

  if grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[div]+' <<<"$TARGET_SERIAL" >/dev/null 2>&1; then
    die "ADB_SERIAL looks like a network endpoint — only ${TARGET_SERIAL} USB serial is allowed"
  fi

  log "USB device OK: $line"
}

assemble_debug() {
  if [[ "$SKIP_ASSEMBLE" == "1" ]]; then
    [[ -f "$APK" ]] || die "SKIP_ASSEMBLE=1 but missing $APK"
    log "Skipping assembleDebug (SKIP_ASSEMBLE=1)"
    return 0
  fi
  [[ -d "${ANDROID_HOME}" ]] || die "ANDROID_HOME not found at ${ANDROID_HOME}"
  log "Running ./gradlew :app:assembleDebug"
  (cd "$ROOT" && ./gradlew :app:assembleDebug -x lintVitalRelease)
  [[ -f "$APK" ]] || die "assembleDebug finished but APK missing: $APK"
}

install_apk() {
  log "Installing $APK to ${TARGET_SERIAL} (adb install -r)"
  adb_dev install -r "$APK"
}

wake_gateway() {
  log "Starting gateway (${SERVICE})"
  adb_dev shell pm enable "$PKG" >/dev/null 2>&1 || true
  adb_dev shell am start-foreground-service -n "$SERVICE" 2>/dev/null \
    || adb_dev shell am startservice -n "$SERVICE" 2>/dev/null \
    || adb_dev shell am start -n "$MAIN" >/dev/null 2>&1 \
    || true
}

setup_forward() {
  adb_dev forward --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  adb_dev forward "tcp:${PORT}" "tcp:${PORT}"
  log "adb forward tcp:${PORT} tcp:${PORT} (${TARGET_SERIAL})"
}

poll_health() {
  local url="http://127.0.0.1:${PORT}/health?lite=1"
  local elapsed=0 code body
  log "Polling $url (timeout ${HEALTH_TIMEOUT_S}s)"
  while (( elapsed < HEALTH_TIMEOUT_S )); do
    code="000"
    if out=$(curl -sS -o /tmp/stepdaddy-tier-health.json -w '%{http_code}' "$url" 2>/dev/null); then code="$out"; fi
    if [[ "$code" == "200" ]]; then
      body="$(cat /tmp/stepdaddy-tier-health.json)"
      log "Health OK at ${elapsed}s"
      if command -v python3 >/dev/null 2>&1; then
        python3 -m json.tool /tmp/stepdaddy-tier-health.json 2>/dev/null | sample_lines 20 >&2 || echo "$body" >&2
      else
        echo "$body" >&2
      fi
      return 0
    fi
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
    log "  t=${elapsed}s health=${code}"
  done
  die "Health timeout after ${HEALTH_TIMEOUT_S}s"
}

verify_special_events_playlist() {
  local url="http://127.0.0.1:${PORT}/tivimate.m3u"
  local tmp
  tmp="$(mktemp)"
  log "Fetching $url"
  curl -fsS "$url" -o "$tmp" || die "Failed to fetch tivimate.m3u"

  if ! grep -q 'Special Events' "$tmp"; then
    log "tivimate.m3u sample (first 40 lines):"
    head -40 "$tmp" >&2
    rm -f "$tmp"
    die "tivimate.m3u has no 'Special Events' group-title"
  fi

  log "Special Events lines (sample):"
  grep 'Special Events' "$tmp" | sample_lines 8 >&2
  local count
  count="$(grep -c 'Special Events' "$tmp" || true)"
  log "grep 'Special Events' count: ${count}"

  if [[ "$TIER" -ge 2 ]]; then
    log "Tier 2 checks: tvg-country, French [FR] / tvg-language=fra"
    if ! grep -q 'tvg-country=' "$tmp"; then
      rm -f "$tmp"
      die "Tier 2: no tvg-country attributes in tivimate.m3u"
    fi
    local country_count fr_count
    country_count="$(grep -c 'tvg-country=' "$tmp" || true)"
    log "tvg-country attribute count: ${country_count}"
    if grep -qE '\[FR\]|tvg-language="fra"' "$tmp"; then
      fr_count="$(grep -cE '\[FR\]|tvg-language="fra"' "$tmp" || true)"
      log "French-labelled events found: ${fr_count} line(s)"
      grep -E '\[FR\]|tvg-language="fra"' "$tmp" | sample_lines 4 >&2
    else
      log "WARN: no French [FR] / tvg-language=fra rows yet (may be no live FR feeds)"
    fi
    grep 'tvg-country=' "$tmp" | sample_lines 4 >&2
  fi

  rm -f "$tmp"
}

trigger_epg_refresh() {
  local url="http://127.0.0.1:${PORT}/epg.xml"
  log "Triggering EPG fetch (may schedule rebuild): $url"
  curl -fsS -o /dev/null "$url" 2>/dev/null || true
}

poll_epg_ready() {
  local url="http://127.0.0.1:${PORT}/health?lite=1"
  local elapsed=0 max_s="${EPG_READY_TIMEOUT_S:-300}"
  log "Polling health for epgReady (timeout ${max_s}s)"
  while (( elapsed < max_s )); do
    if curl -fsS "$url" -o /tmp/stepdaddy-tier-epg-health.json 2>/dev/null; then
      if command -v python3 >/dev/null 2>&1; then
        local ready count version
        ready="$(python3 -c "import json; d=json.load(open('/tmp/stepdaddy-tier-epg-health.json')); print('1' if d.get('epgReady') else '0')" 2>/dev/null || echo 0)"
        count="$(python3 -c "import json; d=json.load(open('/tmp/stepdaddy-tier-epg-health.json')); print(d.get('epgProgrammeCount', 0))" 2>/dev/null || echo 0)"
        version="$(python3 -c "import json; d=json.load(open('/tmp/stepdaddy-tier-epg-health.json')); print(d.get('version', ''))" 2>/dev/null || echo '')"
        if [[ -n "$version" ]]; then
          log "Gateway version: ${version}"
        fi
        if [[ "$ready" == "1" && "$count" -gt 0 ]]; then
          log "EPG ready at ${elapsed}s (${count} programmes)"
          return 0
        fi
      fi
    fi
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
    log "  t=${elapsed}s waiting for epgReady"
  done
  die "EPG not ready after ${max_s}s"
}

verify_special_events_epg() {
  local epg_url="http://127.0.0.1:${PORT}/epg.xml"
  local sports_url="http://127.0.0.1:${PORT}/sports-epg.xml"
  local epg_tmp sports_tmp
  epg_tmp="$(mktemp)"
  sports_tmp="$(mktemp)"

  log "Tier 3 checks: special-event programmes in epg.xml with start/stop"
  poll_epg_ready

  curl -fsS "$epg_url" -o "$epg_tmp" || die "Failed to fetch epg.xml"
  log "epg.xml size: $(wc -c <"$epg_tmp") bytes"

  local dlhd_wait=0 dlhd_max_s="${DLHD_EPG_READY_TIMEOUT_S:-120}"
  while (( dlhd_wait < dlhd_max_s )); do
    dlhd_prog_count="$(grep -cE 'channel="DLHD\.(Event|Guide)\.' "$epg_tmp" || true)"
    if [[ "$dlhd_prog_count" -gt 0 ]]; then
      break
    fi
    sleep "$HEALTH_POLL_S"
    dlhd_wait=$((dlhd_wait + HEALTH_POLL_S))
    log "  t=${dlhd_wait}s waiting for DLHD programmes in epg.xml"
    curl -fsS "$epg_url" -o "$epg_tmp" || die "Failed to fetch epg.xml"
  done

  if ! grep -qE 'channel="DLHD\.(Event|Guide)\.' "$epg_tmp"; then
    log "epg.xml sample (programme lines):"
    grep -E '<programme ' "$epg_tmp" | sample_lines 8 >&2 || head -40 "$epg_tmp" >&2
    rm -f "$epg_tmp" "$sports_tmp"
    die "Tier 3: no DLHD.Event/DLHD.Guide programmes in merged epg.xml"
  fi

  local dlhd_prog_count
  dlhd_prog_count="$(grep -cE 'channel="DLHD\.(Event|Guide)\.' "$epg_tmp" || true)"
  log "DLHD special-event programme count in epg.xml: ${dlhd_prog_count}"

  if ! grep -qE '<programme [^>]*start="[0-9]{14} [+-][0-9]{4}"[^>]*stop="[0-9]{14} [+-][0-9]{4}"[^>]*channel="DLHD\.(Event|Guide)\.|channel="DLHD\.(Event|Guide)\.[^>]*start="[0-9]{14} [+-][0-9]{4}"[^>]*stop="[0-9]{14} [+-][0-9]{4}"' "$epg_tmp"; then
    rm -f "$epg_tmp" "$sports_tmp"
    die "Tier 3: DLHD programmes missing XMLTV start/stop times"
  fi

  log "Sample DLHD programme row(s):"
  grep -E 'channel="DLHD\.(Event|Guide)\.' "$epg_tmp" | sample_lines 3 >&2

  if curl -fsS "$sports_url" -o "$sports_tmp" 2>/dev/null; then
    log "sports-epg.xml size: $(wc -c <"$sports_tmp") bytes"
    if grep -qE '<programme ' "$sports_tmp"; then
      log "sports-epg.xml programme sample:"
      grep -E '<programme ' "$sports_tmp" | sample_lines 3 >&2
    else
      log "WARN: sports-epg.xml has no programme rows yet"
    fi
  else
    log "WARN: sports-epg.xml not available (merged epg.xml still checked)"
  fi

  rm -f "$epg_tmp" "$sports_tmp"
}

verify_tier4_event_health() {
  local url="http://127.0.0.1:${PORT}/health?lite=1"
  log "Tier 4 checks: dlhd-event health summary in /health"
  curl -fsS "$url" -o /tmp/stepdaddy-tier4-health.json || die "Failed to fetch /health for tier 4"

  if ! command -v python3 >/dev/null 2>&1; then
    log "WARN: python3 missing — skipping structured tier-4 health field checks"
    return 0
  fi

  python3 - <<'PY' || die "Tier 4: /health missing dlhd-event health summary fields"
import json, sys
with open("/tmp/stepdaddy-tier4-health.json") as f:
    d = json.load(f)
version = d.get("version", "")
sup = d.get("supplement") or {}
required = (
    "dlhdEventHealthProbed",
    "dlhdEventHealthOk",
    "dlhdEventHealthFailed",
    "dlhdEventHealthUnknown",
    "dlhdEventHealthLastProbeMs",
    "dlhdEventStreams",
)
missing = [k for k in required if k not in sup]
if missing:
    print("missing supplement fields:", ", ".join(missing), file=sys.stderr)
    sys.exit(1)
print(f"version={version}")
print(
    "dlhdEventStreams={dlhdEventStreams} probed={dlhdEventHealthProbed} "
    "ok={dlhdEventHealthOk} failed={dlhdEventHealthFailed} "
    "unknown={dlhdEventHealthUnknown} lastProbeMs={dlhdEventHealthLastProbeMs}".format(**sup)
)
PY

  log "Tier 4 checks: health dots on live dlhd-event titles in tivimate.m3u"
  local playlist_url="http://127.0.0.1:${PORT}/tivimate.m3u"
  local tmp
  tmp="$(mktemp)"
  curl -fsS "$playlist_url" -o "$tmp" || die "Failed to fetch tivimate.m3u for tier 4"

  local event_stream_count dot_count sample
  event_stream_count="$(grep -c 'dlhd-event-stream/' "$tmp" || true)"
  dot_count="$(grep -E '🟢|🔴|🟡|⚪' "$tmp" | wc -l | tr -d ' ')"
  log "dlhd-event-stream URL count: ${event_stream_count}; title lines with health dots: ${dot_count}"

  if [[ "$event_stream_count" -gt 0 ]]; then
    sample="$(grep -B1 'dlhd-event-stream/' "$tmp" | grep -E '🟢|🔴|🟡|⚪' | sample_lines 6 || true)"
    if [[ -n "$sample" ]]; then
      log "Sample dlhd-event titles with health dots:"
      echo "$sample" >&2
    else
      log "WARN: dlhd-event streams present but no health dots yet (probes start after ~90s; pre-live events omit dots)"
      grep -B1 'dlhd-event-stream/' "$tmp" | sample_lines 8 >&2 || true
    fi
  else
    log "WARN: no dlhd-event-stream rows in tivimate.m3u (catalog may have no live event streams)"
  fi

  rm -f "$tmp"
}

verify_tier5_event_lifecycle() {
  local url="http://127.0.0.1:${PORT}/health?lite=1"
  local playlist_url="http://127.0.0.1:${PORT}/tivimate.m3u"
  log "Tier 5 checks: event lifecycle maintenance + upstream URL dedupe"

  curl -fsS "$url" -o /tmp/stepdaddy-tier5-health.json || die "Failed to fetch /health for tier 5"

  if ! command -v python3 >/dev/null 2>&1; then
    log "WARN: python3 missing — skipping structured tier-5 checks"
    return 0
  fi

  python3 - <<'PY' || die "Tier 5: /health missing special-events lifecycle fields"
import json, sys
with open("/tmp/stepdaddy-tier5-health.json") as f:
    d = json.load(f)
version = d.get("version", "")
sup = d.get("supplement") or {}
required = (
    "lastSpecialEventsSyncMs",
    "specialEventsScrapeAgeSeconds",
    "specialEventsStatus",
    "dlhdEventStreams",
    "specialEventGuides",
)
missing = [k for k in required if k not in sup]
if missing:
    print("missing supplement fields:", ", ".join(missing), file=sys.stderr)
    sys.exit(1)
print(f"version={version}")
print(
    "dlhdEventStreams={dlhdEventStreams} guides={specialEventGuides} "
    "status={specialEventsStatus} lastSyncMs={lastSpecialEventsSyncMs} "
    "scrapeAgeSec={specialEventsScrapeAgeSeconds}".format(**sup)
)
PY

  local tmp
  tmp="$(mktemp)"
  curl -fsS "$playlist_url" -o "$tmp" || die "Failed to fetch tivimate.m3u for tier 5"

  python3 - "$tmp" <<'PY' || die "Tier 5: duplicate dlhd-event stream URLs in playlist"
import re, sys
from collections import Counter
path = sys.argv[1]
text = open(path, encoding="utf-8", errors="replace").read()
stream_tokens = re.findall(r"/dlhd-event-stream/([^./\s|]+)\.m3u8", text)
dup_tokens = [t for t, n in Counter(stream_tokens).items() if n > 1]
if dup_tokens:
    print("duplicate dlhd-event-stream tokens:", ", ".join(dup_tokens[:8]), file=sys.stderr)
    sys.exit(1)
# tv| keys routed via daddy channel stream lines — same numeric id twice is a dedupe miss
tv_keys = re.findall(r"/stream/(\d+)\.m3u8", text)
dup_tv = [k for k, n in Counter(tv_keys).items() if n > 1]
# Only flag when both rows are Special Events group (heuristic: preceding EXTINF has Special Events)
special_tv = []
for m in re.finditer(r'#EXTINF:[^\n]*group-title="[^"]*Special Events[^"]*"[^\n]*\n([^\n]+)', text):
    line = m.group(1)
    km = re.search(r"/stream/(\d+)\.m3u8", line)
    if km:
        special_tv.append(km.group(1))
dup_special_tv = [k for k, n in Counter(special_tv).items() if n > 1]
if dup_special_tv:
    print("duplicate Special Events tv stream ids:", ", ".join(dup_special_tv[:8]), file=sys.stderr)
    sys.exit(1)
print(f"dlhd-event-stream tokens={len(stream_tokens)} unique={len(set(stream_tokens))}")
print(f"special-events tv stream ids={len(special_tv)} unique={len(set(special_tv))}")
PY

  log "Tier 5 checks: logcat lifecycle evidence (prune / verify refresh)"
  local lifecycle_log
  lifecycle_log="$(adb_dev logcat -d -t 400 2>/dev/null | grep -E 'Special Events (prune|verify refresh|refresh:)' | tail -5 || true)"
  if [[ -n "$lifecycle_log" ]]; then
    log "Recent lifecycle log lines:"
    echo "$lifecycle_log" >&2
  else
    log "WARN: no lifecycle log lines yet (maintenance starts ~30s after boot, ticks every 2m)"
  fi

  rm -f "$tmp"
}

main() {
  log "Tier ${TIER} deploy → ${TARGET_SERIAL} (USB only)"
  verify_usb_only
  assemble_debug
  install_apk
  wake_gateway
  setup_forward
  poll_health
  verify_special_events_playlist
  if [[ "$TIER" -ge 3 ]]; then
    verify_special_events_epg
  fi
  if [[ "$TIER" -ge 4 ]]; then
    verify_tier4_event_health
  fi
  if [[ "$TIER" -ge 5 ]]; then
    verify_tier5_event_lifecycle
  fi
  log "PASS tier ${TIER} deploy on ${TARGET_SERIAL}"
}

main "$@"

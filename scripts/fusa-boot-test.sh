#!/usr/bin/env bash
# FUSA ONN stick boot test for StepDaddy Android gateway.
# Encodes learnings: grant perms, pm enable, safe pre-reboot state, health poll from host,
# screencap burst on first health=200 (not fixed time), logcat grep, PASS/FAIL report.
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
PKG="${PKG:-com.thothassistant.stepdaddy.gateway.debug}"
COMPONENT="${PKG}/com.thothassistant.stepdaddy.gateway.ServerService"
MAIN="${PKG}/com.thothassistant.stepdaddy.gateway.ui.MainActivity"
PORT=3000
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
HEALTH_POLL_S="${HEALTH_POLL_S:-2}"
SCREENCAP_BURST="${SCREENCAP_BURST:-3}"
SCREENCAP_GAP_S="${SCREENCAP_GAP_S:-8}"
PROOF_OUT="${PROOF_OUT:-/home/nova/livehd/current/fusa-boot-banner-proof.png}"
RESULT_DIR="${RESULT_DIR:-/tmp/fusa-boot-test}"
CYCLE_TAG="${CYCLE_TAG:-cycle1}"
SKIP_REBOOT="${SKIP_REBOOT:-0}"

mkdir -p "$RESULT_DIR"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$RESULT_DIR/${CYCLE_TAG}_${TS}.log"
REPORT="$RESULT_DIR/${CYCLE_TAG}_${TS}_report.txt"

adb() { command adb -s "$SERIAL" "$@"; }

log() {
  local msg="[$(date -u +%H:%M:%S)] $*"
  echo "$msg" >> "$LOG"
  echo "$msg" >&2
}

resolve_ip() {
  adb shell ip -4 addr show wlan0 2>/dev/null | grep -oP 'inet \K[0-9.]+' | head -1 || true
}

grant_permissions() {
  log "Granting runtime permissions for $PKG"
  adb shell pm enable "$PKG" 2>/dev/null || true
  if adb shell pm list packages -d 2>/dev/null | grep -q "$PKG"; then
    adb shell pm enable "$PKG"
  fi
  adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
  adb shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
}

ensure_start_on_boot() {
  log "Ensuring start-on-boot preference (launch app briefly)"
  adb shell am start -n "$MAIN" -a android.intent.action.MAIN >/dev/null 2>&1 || true
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 1
}

prepare_pre_reboot() {
  log "Preparing device state before reboot"
  # Avoid force-stop alone — brief launch+HOME preserves BOOT_COMPLETED receiver state.
  adb shell am start -n "$MAIN" >/dev/null 2>&1 || true
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  # Kill process without disabling package (safer than force-stop for boot receiver).
  adb shell am kill "$PKG" 2>/dev/null || true
  sleep 1
}

poll_health() {
  local ip="$1"
  local start_epoch="$2"
  local url="http://${ip}:${PORT}/health"
  local elapsed=0
  local first_ok_s=""
  local channels=""
  local version=""

  log "Polling $url (timeout ${HEALTH_TIMEOUT_S}s, interval ${HEALTH_POLL_S}s)"
  while (( elapsed < HEALTH_TIMEOUT_S )); do
    local code body
    body="$(curl -sS -m 5 "$url" 2>/dev/null || true)"
    if [[ -n "$body" ]] && echo "$body" | grep -q '"ok"'; then
      code=200
    else
      code=000
    fi
    log "  t=${elapsed}s health=$code"
    if [[ "$code" == "200" && -z "$first_ok_s" ]]; then
      first_ok_s="$elapsed"
      channels="$(echo "$body" | grep -oP '"channels"\s*:\s*\K[0-9]+' || echo "?")"
      version="$(echo "$body" | grep -oP '"version"\s*:\s*"\K[^"]+' || echo "?")"
      log "HEALTH OK at ${elapsed}s — channels=$channels version=$version"
      echo "$first_ok_s"
      return 0
    fi
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
  done
  log "HEALTH TIMEOUT after ${HEALTH_TIMEOUT_S}s"
  echo ""
  return 1
}

screencap_burst() {
  local tag="$1"
  log "Screencap burst ($SCREENCAP_BURST shots, ${SCREENCAP_GAP_S}s apart) — sequential only"
  local i=0
  while (( i < SCREENCAP_BURST )); do
    local out="$RESULT_DIR/${CYCLE_TAG}_${TS}_screen_${tag}_$i.png"
    log "  screencap $i -> $out"
    set +e
    adb exec-out screencap -p > "$out" 2>/dev/null
    local cap_rc=$?
    if [[ $cap_rc -ne 0 ]] || [[ ! -s "$out" ]]; then
      adb shell screencap -p > "$out" 2>/dev/null || true
    fi
    set -e
    if (( i == 0 )) && [[ -s "$out" ]]; then
      cp -f "$out" "$PROOF_OUT" 2>/dev/null || true
      log "  proof saved -> $PROOF_OUT"
    fi
    i=$((i + 1))
    (( i < SCREENCAP_BURST )) && sleep "$SCREENCAP_GAP_S" || true
  done
}

verify_endpoints() {
  local ip="$1"
  local base="http://${ip}:${PORT}"
  local ok=0 fail=0

  for path in health tivimate-playlist.m3u8 epg.xml; do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' -m 30 "${base}/${path}" 2>/dev/null || echo 000)"
    log "GET /${path} -> HTTP $code"
    if [[ "$code" == "200" ]]; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
  done
  echo "$ok $fail"
}

collect_logcat() {
  local out="$RESULT_DIR/${CYCLE_TAG}_${TS}_logcat.txt"
  log "Collecting logcat (BootReceiver, GatewayOverlay, ServerService, GatewayServer)"
  adb logcat -d -t 500 2>/dev/null | grep -E 'BootReceiver|GatewayOverlay|GatewayStartHelper|ServerService|GatewayServer|BootAlarm|BootStart' > "$out" || true
  log "  -> $out"
}

write_report() {
  local verdict="$1"
  local health_s="$2"
  local endpoints_ok="$3"
  local endpoints_fail="$4"
  local overlay_perm="$5"
  {
    echo "=== FUSA Boot Test Report ==="
    echo "Cycle:     $CYCLE_TAG"
    echo "Timestamp: $TS (UTC)"
    echo "Device:    $SERIAL"
    echo "Package:   $PKG"
    echo "Verdict:   $verdict"
    echo ""
    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| Health first 200 | ${health_s:-TIMEOUT}s |"
    echo "| Endpoints OK | $endpoints_ok/3 |"
    echo "| Overlay permission | $overlay_perm |"
    echo "| Proof screencap | $PROOF_OUT |"
    echo ""
    echo "Log: $LOG"
    echo "Logcat: $RESULT_DIR/${CYCLE_TAG}_${TS}_logcat.txt"
  } | tee "$REPORT" >&2
}

main() {
  log "=== FUSA boot test start (cycle=$CYCLE_TAG) ==="

  if ! adb get-state >/dev/null 2>&1; then
    log "ERROR: device $SERIAL not reachable"
    write_report "FAIL" "" 0 3 "unknown"
    exit 1
  fi

  # Ensure legacy uvicorn package stays uninstalled
  if adb shell pm list packages 2>/dev/null | grep -q 'com.nova.stepdaddylivehd$'; then
    log "WARNING: legacy com.nova.stepdaddylivehd found — uninstalling"
    adb uninstall com.nova.stepdaddylivehd 2>/dev/null || true
  fi

  grant_permissions
  ensure_start_on_boot

  local overlay_perm
  overlay_perm="$(adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tail -1 || echo unknown)"

  if [[ "$SKIP_REBOOT" != "1" ]]; then
    prepare_pre_reboot
    log "Rebooting device..."
    adb reboot
    log "Waiting for device..."
    adb wait-for-device
    sleep 15
    log "Sending HOME"
    adb shell input keyevent KEYCODE_HOME 2>/dev/null || true
    sleep 3
  else
    log "SKIP_REBOOT=1 — skipping reboot"
  fi

  local ip
  ip="$(resolve_ip)"
  if [[ -z "$ip" ]]; then
    log "Waiting for wlan0 IP..."
    for _ in $(seq 1 30); do
      sleep 2
      ip="$(resolve_ip)"
      [[ -n "$ip" ]] && break
    done
  fi
  log "Device IP: ${ip:-UNKNOWN}"

  local health_s=""
  if [[ -n "$ip" ]]; then
    health_s="$(poll_health "$ip" "$(date +%s)" || true)"
  fi

  if [[ -n "$health_s" ]]; then
    log "Triggering immediate screencap on health=200"
    screencap_burst "health${health_s}s"
    local ep
    ep="$(verify_endpoints "$ip")"
    local ep_ok ep_fail
    ep_ok="$(echo "$ep" | awk '{print $1}')"
    ep_fail="$(echo "$ep" | awk '{print $2}')"
    collect_logcat
    if [[ "$ep_ok" -ge 3 ]]; then
      write_report "PASS" "$health_s" "$ep_ok" "$ep_fail" "$overlay_perm"
      exit 0
    else
      write_report "PARTIAL" "$health_s" "$ep_ok" "$ep_fail" "$overlay_perm"
      exit 2
    fi
  else
    screencap_burst "timeout"
    collect_logcat
    write_report "FAIL" "" 0 3 "$overlay_perm"
    exit 1
  fi
}

main "$@"

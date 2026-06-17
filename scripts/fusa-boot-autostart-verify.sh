#!/usr/bin/env bash
# Boot auto-start + TiviMate redundancy verification (gated protocol).
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
PKG="${PKG:-com.nova.stepdaddylivehd.gateway.debug}"
MAIN="${PKG}/com.nova.stepdaddylivehd.gateway.ui.MainActivity"
TIVIMATE_PKG="ar.tvplayer.tv"
PORT=3000
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
HEALTH_POLL_S=2
STREAM_TIMEOUT_S=60
REPORT="${REPORT:-/home/nova/livehd/current/fusa-boot-autostart-verify.txt}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"

adb_cmd() { command adb -s "$SERIAL" "$@"; }

log() { echo "[$(date -u +%H:%M:%S)] $*" >&2; }

resolve_ip() {
  adb_cmd shell ip -4 addr show wlan0 2>/dev/null | grep -oP 'inet \K[0-9.]+' | head -1 || true
}

grant_permissions() {
  log "Granting permissions for $PKG"
  adb_cmd shell pm enable "$PKG" 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
  adb_cmd shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
}

prepare_pre_reboot() {
  log "Pre-reboot: launch+HOME+kill (preserve BootReceiver)"
  adb_cmd shell am start -n "$MAIN" >/dev/null 2>&1 || true
  sleep 2
  adb_cmd shell input keyevent KEYCODE_HOME
  sleep 1
  adb_cmd shell am kill "$PKG" 2>/dev/null || true
  sleep 1
}

poll_health() {
  local ip="$1"
  local url="http://${ip}:${PORT}/health"
  local elapsed=0
  while (( elapsed < HEALTH_TIMEOUT_S )); do
    local body
    body="$(curl -sS -m 5 "$url" 2>/dev/null || true)"
    if [[ -n "$body" ]] && echo "$body" | grep -q '"ok"'; then
      echo "$elapsed"
      return 0
    fi
    log "  t=${elapsed}s health=pending"
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
  done
  echo ""
  return 1
}

probe_stream() {
  local base="$1" ch="$2"
  local tmp body code
  tmp="$(mktemp)"
  code="$(curl -sS -m "$STREAM_TIMEOUT_S" -o "$tmp" -w '%{http_code}' \
    -H 'Accept: application/vnd.apple.mpegurl' \
    "${base}/tivimate-stream/${ch}.m3u8" 2>/dev/null || echo '000')"
  body="$(head -c 8192 "$tmp" 2>/dev/null || true)"
  rm -f "$tmp"
  if [[ "$code" == "200" ]] && echo "$body" | grep -q '/content/'; then
    echo "PASS"
    return 0
  fi
  echo "FAIL code=$code has_content=$(echo "$body" | grep -c '/content/' || true)"
  return 1
}

launch_tivimate() {
  if adb_cmd shell pm list packages 2>/dev/null | grep -q "$TIVIMATE_PKG"; then
    log "Launching TiviMate via monkey (no StepDaddy open)"
    adb_cmd shell monkey -p "$TIVIMATE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 5
    return 0
  fi
  log "TiviMate not installed; skipping monkey launch"
  return 1
}

main() {
  {
    echo "=== FUSA Boot Auto-Start + TiviMate Redundancy Verify ==="
    echo "Timestamp: $TS (UTC)"
    echo "Device:    $SERIAL"
    echo "Package:   $PKG"
    echo ""
  } | tee "$REPORT"

  grant_permissions
  prepare_pre_reboot

  log "Rebooting device..."
  adb_cmd reboot
  adb_cmd wait-for-device
  sleep 15
  adb_cmd shell input keyevent KEYCODE_HOME 2>/dev/null || true
  sleep 3

  local ip health_s health_after_tivi stream_result stream_ch=""
  ip="$(resolve_ip)"
  if [[ -z "$ip" ]]; then
    log "Waiting for wlan0 IP..."
    for _ in $(seq 1 45); do
      sleep 2
      ip="$(resolve_ip)"
      [[ -n "$ip" ]] && break
    done
  fi
  log "Device IP: ${ip:-UNKNOWN}"

  if [[ -z "$ip" ]]; then
    echo "VERDICT: FAIL (no wlan0 IP)" | tee -a "$REPORT"
    exit 1
  fi

  health_s="$(poll_health "$ip" || true)"
  if [[ -z "$health_s" ]]; then
    echo "VERDICT: FAIL (health timeout ${HEALTH_TIMEOUT_S}s)" | tee -a "$REPORT"
    adb_cmd logcat -d -t 300 2>/dev/null | grep -E 'BootReceiver|GatewayStartHelper|ScreenWake|GatewayEnsureAlive|ServerService' | tail -40 | tee -a "$REPORT" || true
    exit 1
  fi
  log "Health OK at ${health_s}s (no StepDaddy opened)"

  local tivi_launched=0
  if launch_tivimate; then
    tivi_launched=1
  fi

  local health_after_tivi_s
  health_after_tivi_s="$(poll_health "$ip" || true)"
  if [[ -z "$health_after_tivi_s" ]]; then
    health_after_tivi="FAIL"
  else
    health_after_tivi="PASS (${health_after_tivi_s}s)"
  fi

  local base="http://${ip}:${PORT}"
  for ch in 51 857; do
    log "Probing stream $ch..."
    if probe_stream "$base" "$ch" >/tmp/stream_probe_$$.txt 2>&1; then
      stream_result="PASS"
      stream_ch="$ch"
      break
    fi
  done
  [[ -z "$stream_ch" ]] && stream_result="FAIL ($(cat /tmp/stream_probe_$$.txt 2>/dev/null || echo unknown))"
  rm -f /tmp/stream_probe_$$.txt

  local boot_log
  boot_log="$(adb_cmd logcat -d -t 400 2>/dev/null | grep -E 'BootReceiver|GatewayStartHelper|ScreenWake|GatewayEnsureAlive|ServerService|BootAlarm|BootStart' | tail -25 || true)"

  local verdict="PASS"
  [[ -z "$health_s" ]] && verdict="FAIL"
  [[ "$health_after_tivi" == FAIL* ]] && verdict="FAIL"
  [[ "$stream_result" != PASS* ]] && verdict="FAIL"

  {
    echo ""
    echo "| Step | Result |"
    echo "|------|--------|"
    echo "| Reboot → HOME only (no StepDaddy) | done |"
    echo "| Health first 200 | ${health_s}s |"
    echo "| TiviMate monkey launch | $([[ $tivi_launched -eq 1 ]] && echo yes || echo skipped) |"
    echo "| Health after TiviMate | $health_after_tivi |"
    echo "| Stream /content/ (ch ${stream_ch:-51/857}) | $stream_result |"
    echo ""
    echo "VERDICT: $verdict"
    echo ""
    echo "--- logcat (boot paths) ---"
    echo "$boot_log"
  } | tee -a "$REPORT"

  [[ "$verdict" == PASS ]] && exit 0 || exit 1
}

main "$@"

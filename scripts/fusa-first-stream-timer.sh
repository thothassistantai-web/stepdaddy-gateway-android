#!/usr/bin/env bash
# Measure gateway start + TiViMate launch → first visual stream (ExoPlayer playing).
# Uses patch GET /state (isPlaying), gateway /tivimate-events, and logcat markers.
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
GW_PKG="${GW_PKG:-com.thothassistant.stepdaddy.gateway.debug}"
GW_SERVICE="${GW_PKG}/com.thothassistant.stepdaddy.gateway.ServerService"
TIVI_PKG="${TIVI_PKG:-ar.tvplayer.tv}"
TIVI_MAIN="${TIVI_PKG}/.ui.MainActivity"
GW_PORT="${GW_PORT:-3000}"
TIVI_PORT="${TIVI_PORT:-4617}"
TIMEOUT_S="${TIMEOUT_S:-240}"
POLL_S="${POLL_S:-1}"
REPORT="${REPORT:-/home/nova/livehd/current/fusa-first-stream-timing.txt}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${RESULT_DIR:-/tmp/fusa-first-stream-timer}"
mkdir -p "$RESULT_DIR"
LOG="$RESULT_DIR/timer_${TS}.log"

adb_cmd() { command adb -s "$SERIAL" "$@"; }

log() {
  local msg="[$(date -u +%H:%M:%S)] $*"
  echo "$msg" | tee -a "$LOG" >&2
}

setup_forwards() {
  adb_cmd forward "tcp:${GW_PORT}" "tcp:${GW_PORT}" >/dev/null 2>&1 || true
  adb_cmd forward "tcp:${TIVI_PORT}" "tcp:${TIVI_PORT}" >/dev/null 2>&1 || true
}

gw_health_ok() {
  local body
  body="$(adb_cmd shell "curl -sf --max-time 8 http://127.0.0.1:${GW_PORT}/health" 2>/dev/null || true)"
  if [[ -z "$body" ]]; then
    body="$(adb_cmd shell "echo -e 'GET /health HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' | toybox nc -w 8 127.0.0.1 ${GW_PORT} 2>/dev/null" | tail -n +2 || true)"
  fi
  [[ -n "$body" ]] && echo "$body" | grep -q '"ok"'
}

tivi_state_body() {
  adb_cmd shell "curl -sf --max-time 5 http://127.0.0.1:${TIVI_PORT}/state" 2>/dev/null \
    || adb_cmd shell "echo -e 'GET /state HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' | toybox nc -w 5 127.0.0.1 ${TIVI_PORT} 2>/dev/null" | tail -n +2 \
    || true
}

tivi_playing() {
  local body playing
  body="$(tivi_state_body)"
  [[ -z "$body" ]] && return 1
  playing="$(echo "$body" | grep -oP '"isPlaying"\s*:\s*\K(true|false)' || true)"
  [[ "$playing" == "true" ]]
}

tivi_setup_done() {
  local body
  body="$(curl -sS -m 3 "http://127.0.0.1:${TIVI_PORT}/state" 2>/dev/null || true)"
  [[ -n "$body" ]] && echo "$body" | grep -q '"setupDone"\s*:\s*true'
}

logcat_playback_marker() {
  local since_epoch="$1"
  adb_cmd logcat -d -v time -T "$((since_epoch * 1000))" 2>/dev/null | grep -E \
    'StepDaddyBridge.*(Boot-tune|PLAYBACK_STARTED|Tuned)|ExoPlayer.*STATE_READY|Playback started' | head -3
}

main() {
  log "=== FUSA first-stream timer ($TS) ==="
  if ! adb_cmd get-state >/dev/null 2>&1; then
    log "ERROR: device $SERIAL not reachable"
    exit 1
  fi

  setup_forwards
  adb_cmd logcat -c 2>/dev/null || true

  log "Stopping gateway + TiViMate for cold start"
  adb_cmd shell am force-stop "$GW_PKG" 2>/dev/null || true
  adb_cmd shell am force-stop "$TIVI_PKG" 2>/dev/null || true
  sleep 2

  local t0
  t0=$(date +%s)
  log "T0=$t0 — starting gateway FGS"
  adb_cmd shell am start-foreground-service -n "$GW_SERVICE" >/dev/null 2>&1 \
    || adb_cmd shell am startservice -n "$GW_SERVICE" >/dev/null 2>&1 || true

  local gw_ready_s="" tivi_launch_s="" first_play_s=""
  local elapsed=0
  while (( elapsed < TIMEOUT_S )); do
    if [[ -z "$gw_ready_s" ]] && gw_health_ok; then
      gw_ready_s="$elapsed"
      log "Gateway /health OK at ${elapsed}s"
      log "Launching TiViMate"
      adb_cmd shell am start -n "$TIVI_MAIN" >/dev/null 2>&1 || true
      tivi_launch_s="$elapsed"
    fi
    if [[ -n "$tivi_launch_s" ]] && tivi_playing; then
      first_play_s="$elapsed"
      log "TiViMate isPlaying=true at ${elapsed}s"
      break
    fi
    sleep "$POLL_S"
    elapsed=$((elapsed + POLL_S))
  done

  local logcat_markers
  logcat_markers="$(logcat_playback_marker "$t0" || true)"

  local gw_state tivi_state events
  gw_state="$(adb_cmd shell "curl -sf --max-time 8 http://127.0.0.1:${GW_PORT}/health" 2>/dev/null || echo '{}')"
  tivi_state="$(tivi_state_body)"
  events="$(adb_cmd shell "curl -sf --max-time 8 http://127.0.0.1:${GW_PORT}/tivimate-events" 2>/dev/null || echo '[]')"

  local verdict="PASS"
  [[ -z "$gw_ready_s" || -z "$first_play_s" ]] && verdict="FAIL"

  {
    echo "================================================================"
    echo "  FUSA First Visual Stream Timer"
    echo "================================================================"
    echo "Timestamp (UTC): $TS"
    echo "Device:          $SERIAL"
    echo "Gateway:         $GW_PKG"
    echo "TiViMate:        $TIVI_PKG"
    echo "Verdict:         $verdict"
    echo ""
    echo "=== TIMING (T0 = gateway FGS start) ==="
    echo "GATEWAY_HEALTH_S:    ${gw_ready_s:-TIMEOUT}"
    echo "TIVIMATE_LAUNCH_S:   ${tivi_launch_s:-TIMEOUT}"
    echo "FIRST_PLAYING_S:     ${first_play_s:-TIMEOUT}"
    echo "GW_TO_PLAY_S:        $([ -n "$first_play_s" ] && echo "$first_play_s" || echo TIMEOUT)"
    echo ""
    echo "=== LOGCAT MARKERS ==="
    echo "${logcat_markers:-none}"
    echo ""
    echo "=== GATEWAY /health ==="
    echo "$gw_state"
    echo ""
    echo "=== TIVIMATE /state ==="
    echo "$tivi_state"
    echo ""
    echo "=== /tivimate-events (tail) ==="
    echo "$events" | head -c 4096
    echo ""
    echo "Log: $LOG"
    echo "Script: stepdaddy-android/scripts/fusa-first-stream-timer.sh"
  } | tee "$REPORT"

  log "Report: $REPORT"
  [[ "$verdict" == "PASS" ]] && exit 0 || exit 1
}

main "$@"

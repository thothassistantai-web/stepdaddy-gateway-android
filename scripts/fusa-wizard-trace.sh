#!/usr/bin/env bash
# Poll StepDaddy :4617/status and :4617/ui; optional gateway-ready gate + STEPDADDY_SETUP.
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
INTERVAL_SEC="${INTERVAL_SEC:-2}"
CAPTURE_ON_CHANGE="${CAPTURE_ON_CHANGE:-1}"
OUT_DIR="${OUT_DIR:-./trace-wizard-$(date -u +%Y%m%dT%H%M%SZ)}"
HTTP_PORT="${HTTP_PORT:-4617}"
GATEWAY_PORT="${GATEWAY_PORT:-3000}"
GATEWAY_READY_TIMEOUT_S="${GATEWAY_READY_TIMEOUT_S:-120}"
GATEWAY_POLL_S="${GATEWAY_POLL_S:-2}"
GATEWAY_PKG="${GATEWAY_PKG:-com.thothassistant.stepdaddy.gateway.debug}"
TIVIMATE_PKG="${TIVIMATE_PKG:-com.thothassistant.daddyliveTV}"
SETUP_ACTION="${TIVIMATE_PKG}.action.STEPDADDY_SETUP"
GATEWAY_BASE="${GATEWAY_BASE:-http://127.0.0.1:${GATEWAY_PORT}}"
RUN_SETUP=0
COLD_GATEWAY=0

STATUS_URL="http://127.0.0.1:${HTTP_PORT}/status"
UI_URL="http://127.0.0.1:${HTTP_PORT}/ui"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Poll DaddyLive patch HTTP tracer endpoints and print phase transitions.

  --serial SERIAL       adb device (default: FUSA2541006925 or \$ADB_SERIAL)
  --interval SEC        poll interval (default: 2)
  --no-capture          skip adb screencap on phase change
  --out DIR             output directory for PNG captures
  --setup               ENSURE_GATEWAY then broadcast STEPDADDY_SETUP before tracing
  --cold-gateway        force-stop gateway before ENSURE_GATEWAY (with --setup)
  --gateway-pkg PKG     gateway package (default: .gateway.debug)
  --tivimate-pkg PKG    DaddyLive TV package (default: com.thothassistant.daddyliveTV)
  --gateway-timeout SEC wait for health+setup (default: 120)

Requires: adb forward tcp:${HTTP_PORT} tcp:${HTTP_PORT} for tracer (or curl on device).
Gateway probes use adb shell curl against loopback :${GATEWAY_PORT}.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --interval) INTERVAL_SEC="$2"; shift 2 ;;
    --no-capture) CAPTURE_ON_CHANGE=0; shift ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --setup) RUN_SETUP=1; shift ;;
    --cold-gateway) COLD_GATEWAY=1; shift ;;
    --gateway-pkg) GATEWAY_PKG="$2"; shift 2 ;;
    --tivimate-pkg) TIVIMATE_PKG="$2"; shift 2 ;;
    --gateway-timeout) GATEWAY_READY_TIMEOUT_S="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
done

SETUP_ACTION="${TIVIMATE_PKG}.action.STEPDADDY_SETUP"
GATEWAY_SERVICE="${GATEWAY_PKG}/com.thothassistant.stepdaddy.gateway.ServerService"

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

json_field() {
  local json="$1"
  local key="$2"
  python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('${key}',''))" <<<"$json" 2>/dev/null || true
}

fetch_json() {
  local url="$1"
  adb_cmd shell "curl -sfS --max-time 4 '${url}' 2>/dev/null" || \
    curl -sfS --max-time 4 "${url}" 2>/dev/null || true
}

gateway_curl() {
  adb_cmd forward "tcp:${GATEWAY_PORT}" "tcp:${GATEWAY_PORT}" >/dev/null 2>&1 || true
  curl -sfS --max-time 8 "http://127.0.0.1:${GATEWAY_PORT}${1}" 2>/dev/null || true
}

probe_gateway_ready() {
  local health setup channels starting playlist ok
  health="$(gateway_curl "/health?lite=1")"
  setup="$(gateway_curl "/tivimate-setup")"
  [[ -n "${health//[[:space:]]/}" ]] || return 1
  [[ -n "${setup//[[:space:]]/}" ]] || return 1
  ok="$(json_field "$health" ok)"
  starting="$(json_field "$health" starting)"
  channels="$(json_field "$health" channels)"
  local supplement
  supplement="$(json_field "$health" supplementChannels)"
  channels=$(( ${channels:-0} + ${supplement:-0} ))
  playlist="$(json_field "$setup" playlist)"
  [[ "$ok" == "True" || "$ok" == "true" ]] || return 1
  [[ "$starting" == "False" || "$starting" == "false" || -z "${starting//[[:space:]]/}" ]] || return 1
  (( channels > 0 )) || return 1
  [[ -n "${playlist//[[:space:]]/}" ]] || return 1
  echo "$channels"
  return 0
}

ensure_gateway() {
  local start_ts elapsed channels last_channels="" stable=0
  start_ts="$(date +%s)"
  echo "=== ENSURE_GATEWAY ==="
  echo "device=$SERIAL pkg=$GATEWAY_PKG timeout=${GATEWAY_READY_TIMEOUT_S}s"

  if (( COLD_GATEWAY )); then
    echo "Force-stopping gateway for cold-start probe"
    adb_cmd shell am force-stop "$GATEWAY_PKG" 2>/dev/null || true
    sleep 2
  fi

  echo "Starting ServerService: $GATEWAY_SERVICE"
  adb_cmd shell am start-foreground-service -n "$GATEWAY_SERVICE" 2>&1 || \
    adb_cmd shell am startservice -n "$GATEWAY_SERVICE" 2>&1 || true

  elapsed=0
  while (( elapsed < GATEWAY_READY_TIMEOUT_S )); do
    if channels="$(probe_gateway_ready)"; then
      if [[ -n "$last_channels" && "$channels" == "$last_channels" ]]; then
        stable=$((stable + 1))
      else
        stable=1
        last_channels="$channels"
      fi
      echo "  t=${elapsed}s health+setup ok channels=$channels stable=$stable/2"
      if (( stable >= 2 )); then
        local end_ts ready_s
        end_ts="$(date +%s)"
        ready_s=$((end_ts - start_ts))
        echo "GATEWAY_READY elapsed=${ready_s}s channels=$channels"
        return 0
      fi
    else
      stable=0
      last_channels=""
      echo "  t=${elapsed}s waiting (health+setup not ready)"
      if (( elapsed > 0 && elapsed % 10 == 0 )); then
        adb_cmd shell am start-foreground-service -n "$GATEWAY_SERVICE" 2>/dev/null || true
      fi
    fi
    sleep "$GATEWAY_POLL_S"
    elapsed=$((elapsed + GATEWAY_POLL_S))
  done

  echo "GATEWAY_READY TIMEOUT after ${GATEWAY_READY_TIMEOUT_S}s"
  return 1
}

broadcast_setup() {
  echo "=== STEPDADDY_SETUP ==="
  adb_cmd shell am broadcast \
    -a "$SETUP_ACTION" \
    --es gateway_base "$GATEWAY_BASE" \
    2>&1
}

mkdir -p "$OUT_DIR"
echo "==> Wizard trace OUT_DIR=$OUT_DIR device=$SERIAL interval=${INTERVAL_SEC}s setup=$RUN_SETUP"

if (( RUN_SETUP )); then
  if ! ensure_gateway; then
    echo "Aborting: gateway not ready"
    exit 1
  fi
  broadcast_setup
  sleep 3
fi

adb_cmd forward "tcp:${HTTP_PORT}" "tcp:${HTTP_PORT}" >/dev/null 2>&1 || true

last_key=""
capture_idx=0

while true; do
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  status="$(fetch_json "$STATUS_URL")"
  ui="$(fetch_json "$UI_URL")"

  if [[ -z "${status//[[:space:]]/}" ]]; then
    echo "[$ts] WARN: empty /status (app foreground? HTTP up?)"
    sleep "$INTERVAL_SEC"
    continue
  fi

  phase="$(json_field "$status" wizardPhase)"
  step="$(json_field "$status" wizardStep)"
  screen="$(json_field "$ui" screen)"
  fg="$(json_field "$status" foreground)"
  home="$(json_field "$status" onLauncherHome)"
  proc="$(json_field "$status" processing)"
  activity="$(json_field "$status" currentActivity)"
  fragment="$(json_field "$status" currentFragment)"
  channels="$(json_field "$status" channelCount)"
  playlists="$(json_field "$status" playlistCount)"
  setup_done="$(json_field "$status" setupDone)"
  last_err="$(json_field "$status" lastError)"

  key="${phase}|${step}|${screen}|${fg}|${home}|${proc}|${channels}|${setup_done}"

  if [[ "$key" != "$last_key" ]]; then
    echo "[$ts] phase=$phase step=$step screen=$screen fg=$fg home=$home processing=$proc setupDone=$setup_done playlists=$playlists channels=$channels"
    echo "         activity=${activity##*.} fragment=$fragment"
    if [[ -n "${last_err//[[:space:]]/}" ]]; then
      echo "         lastError=$last_err"
    fi
    if [[ "$CAPTURE_ON_CHANGE" -eq 1 ]]; then
      png="${OUT_DIR}/trace-$(printf '%03d' "$capture_idx")-${screen:-unknown}.png"
      if adb_cmd exec-out screencap -p >"$png" 2>/dev/null; then
        echo "         screencap -> $(basename "$png")"
        capture_idx=$((capture_idx + 1))
      fi
    fi
    last_key="$key"
  fi

  sleep "$INTERVAL_SEC"
done

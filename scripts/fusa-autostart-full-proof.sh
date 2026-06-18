#!/usr/bin/env bash
# Full auto-start / resume proof suite for ONN FUSA sticks.
# Tests: cold boot, kill recovery, sleep/wake, TiviMate open, app launch auto-start.
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
PKG="${PKG:-com.nova.stepdaddylivehd.gateway.debug}"
MAIN="${PKG}/com.nova.stepdaddylivehd.gateway.ui.MainActivity"
TIVIMATE="${TIVIMATE_PKG:-ar.tvplayer.tv}"
PORT="${PORT:-3000}"
LOCAL_PORT="${LOCAL_PORT:-13000}"
DATE_TAG="$(date -u +%Y%m%d)"
PROOF_DIR="${PROOF_DIR:-/home/nova/livehd/current/fusa-autostart-proof-${DATE_TAG}}"
BOOT_TIMEOUT_S="${BOOT_TIMEOUT_S:-240}"
RECOVERY_TIMEOUT_S="${RECOVERY_TIMEOUT_S:-120}"
WAKE_TIMEOUT_S="${WAKE_TIMEOUT_S:-90}"
TIVIMATE_TIMEOUT_S="${TIVIMATE_TIMEOUT_S:-90}"
APP_LAUNCH_TIMEOUT_S="${APP_LAUNCH_TIMEOUT_S:-60}"

mkdir -p "$PROOF_DIR"

adb_cmd() { command adb -s "$SERIAL" "$@"; }

log() {
  local msg="[$(date -u +%H:%M:%S)] $*"
  echo "$msg" | tee -a "$PROOF_DIR/run.log"
}

grant_permissions() {
  log "Granting permissions + battery whitelist"
  adb_cmd shell pm enable "$PKG" 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
  adb_cmd shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
}

prepare_pre_reboot() {
  log "Pre-reboot: launch+HOME+kill (preserve BOOT_COMPLETED)"
  adb_cmd shell am start -n "$MAIN" >/dev/null 2>&1 || true
  sleep 2
  adb_cmd shell input keyevent KEYCODE_HOME
  sleep 1
  adb_cmd shell am kill "$PKG" 2>/dev/null || true
  sleep 1
}

health_code() {
  adb_cmd forward "tcp:${LOCAL_PORT}" "tcp:${PORT}" >/dev/null 2>&1 || true
  curl -sS -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${LOCAL_PORT}/health" 2>/dev/null || echo "000"
}

health_ok() {
  local code
  code="$(health_code)"
  [[ "$code" == "200" ]]
}

wait_health() {
  local label="$1"
  local timeout="$2"
  local out="$PROOF_DIR/${label}.txt"
  local elapsed=0
  local first_ok=""
  log "[$label] Waiting for /health 200 (timeout ${timeout}s)"
  : >"$out"
  while (( elapsed < timeout )); do
    local code
    code="$(health_code)"
    echo "t=${elapsed}s code=${code}" >>"$out"
    if [[ "$code" == "200" ]]; then
      first_ok="$elapsed"
      log "[$label] PASS health 200 at ${elapsed}s"
      echo "$first_ok" >"$PROOF_DIR/${label}_result.txt"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  log "[$label] FAIL health timeout after ${timeout}s"
  echo "TIMEOUT" >"$PROOF_DIR/${label}_result.txt"
  return 1
}

collect_boot_logcat() {
  local tag="$1"
  adb_cmd logcat -d -t 600 2>/dev/null \
    | grep -E 'BootReceiver|BootAlarm|GatewayStartHelper|ServerService|GatewayHealth|ScreenWake|GatewayEnsureAlive|BootStart|am_anr.*gateway' \
    >"$PROOF_DIR/logcat_${tag}.txt" || true
}

test_cold_boot() {
  log "=== TEST 1: Cold reboot boot auto-start ==="
  prepare_pre_reboot
  adb_cmd logcat -c 2>/dev/null || true
  local reboot_ts
  reboot_ts="$(date -u +%s)"
  echo "$reboot_ts" >"$PROOF_DIR/cold_boot_reboot_epoch.txt"
  adb_cmd reboot
  adb_cmd wait-for-device
  sleep 12
  adb_cmd shell input keyevent KEYCODE_HOME 2>/dev/null || true
  if wait_health "cold_boot" "$BOOT_TIMEOUT_S"; then
    echo "PASS" >"$PROOF_DIR/cold_boot_verdict.txt"
    collect_boot_logcat "cold_boot"
    return 0
  fi
  echo "FAIL" >"$PROOF_DIR/cold_boot_verdict.txt"
  collect_boot_logcat "cold_boot"
  return 1
}

test_kill_recovery() {
  log "=== TEST 2: Kill gateway process recovery ==="
  if ! health_ok; then
    log "[kill_recovery] SKIP — gateway not healthy"
    echo "SKIP" >"$PROOF_DIR/kill_recovery_verdict.txt"
    return 2
  fi
  local pid
  pid="$(adb_cmd shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  log "Killing pid=${pid:-unknown}"
  adb_cmd shell am kill "$PKG" 2>/dev/null || true
  sleep 2
  if wait_health "kill_recovery" "$RECOVERY_TIMEOUT_S"; then
    echo "PASS" >"$PROOF_DIR/kill_recovery_verdict.txt"
    return 0
  fi
  echo "FAIL" >"$PROOF_DIR/kill_recovery_verdict.txt"
  return 1
}

test_sleep_wake() {
  log "=== TEST 3: Sleep / wake resume ==="
  if ! health_ok; then
    log "[sleep_wake] SKIP — gateway not healthy"
    echo "SKIP" >"$PROOF_DIR/sleep_wake_verdict.txt"
    return 2
  fi
  adb_cmd shell input keyevent KEYCODE_SLEEP 2>/dev/null || adb_cmd shell input keyevent 223 2>/dev/null || true
  sleep 5
  adb_cmd shell input keyevent KEYCODE_WAKEUP 2>/dev/null || adb_cmd shell input keyevent 224 2>/dev/null || true
  sleep 3
  if wait_health "sleep_wake" "$WAKE_TIMEOUT_S"; then
    echo "PASS" >"$PROOF_DIR/sleep_wake_verdict.txt"
    return 0
  fi
  echo "FAIL" >"$PROOF_DIR/sleep_wake_verdict.txt"
  return 1
}

test_tivimate_open() {
  log "=== TEST 4: Open TiviMate ensures gateway ==="
  adb_cmd shell am kill "$PKG" 2>/dev/null || true
  sleep 2
  adb_cmd shell monkey -p "$TIVIMATE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
    adb_cmd shell am start -n "$TIVIMATE/.MainActivity" >/dev/null 2>&1 || true
  sleep 3
  if wait_health "tivimate_open" "$TIVIMATE_TIMEOUT_S"; then
    echo "PASS" >"$PROOF_DIR/tivimate_open_verdict.txt"
    return 0
  fi
  echo "FAIL" >"$PROOF_DIR/tivimate_open_verdict.txt"
  return 1
}

test_app_launch_autostart() {
  log "=== TEST 5: App launch auto-start ==="
  adb_cmd shell am kill "$PKG" 2>/dev/null || true
  sleep 2
  adb_cmd shell am start -n "$MAIN" >/dev/null 2>&1 || true
  if wait_health "app_launch" "$APP_LAUNCH_TIMEOUT_S"; then
    echo "PASS" >"$PROOF_DIR/app_launch_verdict.txt"
    adb_cmd shell input keyevent KEYCODE_HOME 2>/dev/null || true
    return 0
  fi
  echo "FAIL" >"$PROOF_DIR/app_launch_verdict.txt"
  return 1
}

write_matrix() {
  local matrix="$PROOF_DIR/MATRIX.md"
  {
    echo "# FUSA Auto-Start Proof — ${DATE_TAG}"
    echo ""
    echo "| Test | Verdict | Detail |"
    echo "|------|---------|--------|"
    for t in cold_boot kill_recovery sleep_wake tivimate_open app_launch; do
      local v r
      v="$(cat "$PROOF_DIR/${t}_verdict.txt" 2>/dev/null || echo MISSING)"
      r="$(cat "$PROOF_DIR/${t}_result.txt" 2>/dev/null || echo —)"
      echo "| $t | $v | $r |"
    done
    echo ""
    echo "Device: $SERIAL"
    echo "Package: $PKG"
    echo "Artifacts: $PROOF_DIR"
  } | tee "$matrix"
}

main() {
  log "Proof suite -> $PROOF_DIR"
  if ! adb_cmd get-state >/dev/null 2>&1; then
    adb connect 192.168.1.157:5555 2>/dev/null || true
    sleep 2
  fi
  grant_permissions

  local rc=0
  test_cold_boot || rc=1
  test_kill_recovery || rc=1
  test_sleep_wake || rc=1
  test_tivimate_open || rc=1
  test_app_launch_autostart || rc=1
  write_matrix
  log "Done (exit=$rc)"
  exit "$rc"
}

main "$@"

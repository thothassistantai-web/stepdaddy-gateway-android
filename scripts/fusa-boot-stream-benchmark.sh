#!/usr/bin/env bash
# FUSA coordinated boot + stream timing benchmark.
# Reboot → boot milestones → first playable /content/ stream → 9 more cold → warm re-tune.
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
PKG="${PKG:-com.thothassistant.stepdaddy.gateway.debug}"
MAIN="${PKG}/com.thothassistant.stepdaddy.gateway.ui.MainActivity"
APK="${APK:-$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk}"
PORT=3000
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-360}"
HEALTH_POLL_S="${HEALTH_POLL_S:-2}"
STREAM_POLL_S="${STREAM_POLL_S:-2}"
STREAM_TIMEOUT_S="${STREAM_TIMEOUT_S:-45}"
FIRST_PROBE_TIMEOUT_S="${FIRST_PROBE_TIMEOUT_S:-15}"
FIRST_STREAM_TIMEOUT_S="${FIRST_STREAM_TIMEOUT_S:-360}"
SKIP_REBOOT="${SKIP_REBOOT:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
REPORT="${REPORT:-/home/nova/livehd/current/fusa-boot-stream-benchmark.txt}"
RESULT_DIR="${RESULT_DIR:-/tmp/fusa-boot-stream-bench}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"

# Probe order for time-to-first-stream; then 9 follow-on channels (10 total incl. first).
FIRST_POLL=(51 857 5 360)
FOLLOW_ON=(155 726 16 800 963 302 766 110 588)

mkdir -p "$RESULT_DIR"
LOG="$RESULT_DIR/benchmark_${TS}.log"

adb_cmd() { command adb -s "$SERIAL" "$@"; }

log() {
  local msg="[$(date -u +%H:%M:%S)] $*"
  echo "$msg" >> "$LOG"
  echo "$msg" >&2
}

resolve_ip() {
  adb_cmd shell ip -4 addr show wlan0 2>/dev/null | grep -oP 'inet \K[0-9.]+' | head -1 || true
}

grant_permissions() {
  log "Granting permissions and enabling $PKG"
  adb_cmd shell pm enable "$PKG" 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb_cmd shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
  adb_cmd shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
}

ensure_start_on_boot() {
  log "Launch app briefly + HOME (startOnBoot pref)"
  adb_cmd shell am start -n "$MAIN" >/dev/null 2>&1 || true
  sleep 2
  adb_cmd shell input keyevent KEYCODE_HOME
  sleep 1
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

install_apk() {
  if [[ "$SKIP_INSTALL" == "1" ]]; then
    log "SKIP_INSTALL=1"
    return 0
  fi
  if [[ ! -f "$APK" ]]; then
    log "WARNING: APK not found at $APK — skipping install"
    return 0
  fi
  log "Installing $APK"
  adb_cmd install -r "$APK" 2>&1 | tee -a "$LOG"
}

# Returns: code|latency_ms|playable(0|1)|notes
probe_stream() {
  local base="$1" ch="$2"
  local timeout="${3:-$STREAM_TIMEOUT_S}"
  local tmp body code ms playable notes time_s curl_ms
  tmp="$(mktemp)"
  curl_ms="$(curl -sS -m "$timeout" -o "$tmp" -w '%{http_code} %{time_total}' \
    -H 'Accept: application/vnd.apple.mpegurl' \
    "${base}/tivimate-stream/${ch}.m3u8" 2>/dev/null || echo '000 0.0')"
  code="$(echo "$curl_ms" | awk '{print $1}')"
  time_s="$(echo "$curl_ms" | awk '{print $NF}')"
  ms="$(awk -v t="$time_s" 'BEGIN { printf "%d", t * 1000 }')"
  body="$(head -c 8192 "$tmp" 2>/dev/null || true)"
  rm -f "$tmp"
  playable=0
  notes=""
  if [[ "$code" == "200" ]] && echo "$body" | grep -q '/content/'; then
    playable=1
    notes="ok"
  elif echo "$body" | grep -qi 'upstream_busy'; then
    notes="upstream_busy"
  elif echo "$body" | grep -q '# StepDaddy:'; then
    notes="error_manifest"
  elif [[ "$code" == "502" || "$code" == "503" ]]; then
    notes="HTTP_${code}"
  elif [[ "$code" == "000" ]]; then
    notes="timeout"
  else
    notes="HTTP_${code}"
  fi
  echo "${code}|${ms}|${playable}|${notes}"
}

poll_health_until_ok() {
  local ip="$1" t0_epoch="$2"
  local url="http://${ip}:${PORT}/health"
  local elapsed=0 first_ok_s="" body=""
  while (( elapsed < HEALTH_TIMEOUT_S )); do
    body="$(curl -sS -m 5 "$url" 2>/dev/null || true)"
    if [[ -n "$body" ]] && echo "$body" | grep -q '"ok"'; then
      first_ok_s="$elapsed"
      echo "${first_ok_s}|${body}"
      return 0
    fi
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
  done
  echo "|"
  return 1
}

parse_logcat_milestones() {
  local logcat_file="$1" reboot_epoch="$2"
  local boot_completed_s="" fgs_s="" listen_s="" boot_recv="" banner_count=0

  if [[ -f "$logcat_file" ]]; then
    if grep -qE 'boot_completed|BOOT_COMPLETED' "$logcat_file" 2>/dev/null; then
      boot_completed_s="seen"
    fi
    if grep -qE 'BootReceiver|BootStart|BootAlarm|GatewayStartHelper' "$logcat_file" 2>/dev/null; then
      boot_recv="PASS"
    else
      boot_recv="FAIL"
    fi
    if grep -qiE 'startForeground|FGS' "$logcat_file" 2>/dev/null; then
      fgs_s="seen"
    fi
    if grep -qE 'GatewayServer.*[Ll]isten|Listening on' "$logcat_file" 2>/dev/null; then
      listen_s="seen"
    fi
    banner_count=$(grep -cE 'showReadyBanner|GatewayOverlay.*(show|Show|ready|Ready)' "$logcat_file" 2>/dev/null || echo 0)
  fi
  if [[ -f "$logcat_file" ]] && grep -qiE 'ANR in com.nova.stepdaddylivehd|Timeout executing service.*ServerService' "$logcat_file" 2>/dev/null; then
    boot_recv="${boot_recv}|ANR"
  fi
  echo "${boot_completed_s:-missing}|${fgs_s:-missing}|${listen_s:-missing}|${boot_recv:-unknown}|${banner_count}"
}

percentile() {
  local p="$1"
  shift
  PCT="$p" python3 - "$@" <<'PY'
import math, os, sys
vals = sorted(int(x) for x in sys.argv[1:] if str(x).isdigit())
if not vals:
    print(0)
    raise SystemExit
p = float(os.environ["PCT"])
idx = max(0, min(len(vals) - 1, math.ceil(p / 100 * len(vals)) - 1))
print(vals[idx])
PY
}

main() {
  log "=== FUSA boot+stream benchmark start ==="
  log "Device=$SERIAL PKG=$PKG REPORT=$REPORT"

  if ! adb_cmd get-state >/dev/null 2>&1; then
    log "ERROR: device $SERIAL not reachable"
    exit 1
  fi

  if adb_cmd shell pm list packages 2>/dev/null | grep -q '^package:com.nova.stepdaddylivehd$'; then
    log "Removing legacy com.nova.stepdaddylivehd"
    adb_cmd uninstall com.nova.stepdaddylivehd 2>/dev/null || true
  fi

  install_apk
  grant_permissions
  ensure_start_on_boot

  local reboot_epoch=0
  local logcat_file="$RESULT_DIR/benchmark_${TS}_logcat.txt"

  if [[ "$SKIP_REBOOT" != "1" ]]; then
    prepare_pre_reboot
    adb_cmd logcat -c 2>/dev/null || true
    reboot_epoch=$(date +%s)
    log "Rebooting device (T_reboot=$reboot_epoch)..."
    adb_cmd reboot
    adb_cmd wait-for-device
    sleep 15
    adb_cmd shell input keyevent KEYCODE_HOME 2>/dev/null || true
    sleep 3
  else
    log "SKIP_REBOOT=1"
    reboot_epoch=$(date +%s)
  fi

  local ip=""
  for _ in $(seq 1 30); do
    ip="$(resolve_ip)"
    [[ -n "$ip" ]] && break
    sleep 2
  done
  log "Device IP: ${ip:-UNKNOWN}"
  local base="http://${ip}:${PORT}"

  # Health poll runs in background while we poll streams (gateway may serve streams before /health JSON ready)
  local health_file="$RESULT_DIR/benchmark_${TS}_health.txt"
  : > "$health_file"
  if [[ -n "$ip" ]]; then
    (
      local hr
      hr="$(poll_health_until_ok "$ip" "$reboot_epoch" || true)"
      echo "$hr" > "$health_file"
    ) &
    HEALTH_PID=$!
  else
    HEALTH_PID=""
  fi

  # T=0 anchor uses reboot_epoch; logcat collected after stream tests
  local first_ch="" first_stream_s="" probe_attempts=0
  local err502=0 err503=0 err_busy=0
  local stream_poll_elapsed=0

  if [[ -n "$ip" ]]; then
    log "Polling for first playable stream (channels: ${FIRST_POLL[*]})"
    local idx=0
    while (( stream_poll_elapsed < FIRST_STREAM_TIMEOUT_S )); do
      local ch="${FIRST_POLL[$idx]}"
      idx=$(( (idx + 1) % ${#FIRST_POLL[@]} ))
      probe_attempts=$((probe_attempts + 1))
      local pr code ms playable notes
      pr="$(probe_stream "$base" "$ch" "$FIRST_PROBE_TIMEOUT_S")"
      IFS='|' read -r code ms playable notes <<< "$pr"
      log "  t=${stream_poll_elapsed}s probe ch=$ch -> HTTP $code ${ms}ms playable=$playable notes=$notes"
      [[ "$notes" == "HTTP_502" ]] && err502=$((err502 + 1))
      [[ "$notes" == "HTTP_503" ]] && err503=$((err503 + 1))
      [[ "$notes" == "upstream_busy" ]] && err_busy=$((err_busy + 1))
      if [[ "$playable" == "1" ]]; then
        first_ch="$ch"
        first_stream_s="$stream_poll_elapsed"
        break
      fi
      sleep "$STREAM_POLL_S"
      stream_poll_elapsed=$((stream_poll_elapsed + STREAM_POLL_S))
    done
  fi
  log "TIME_TO_FIRST_STREAM: ${first_stream_s:-TIMEOUT} channel=${first_ch:-none} probes=$probe_attempts"

  # Wait for health poll background job
  local health_s="" health_body=""
  if [[ -n "${HEALTH_PID:-}" ]]; then
    wait "$HEALTH_PID" 2>/dev/null || true
    if [[ -f "$health_file" ]]; then
      local hr
      hr="$(cat "$health_file")"
      health_s="${hr%%|*}"
      health_body="${hr#*|}"
    fi
  fi
  log "Health first 200: ${health_s:-TIMEOUT}"

  # Collect logcat milestones (after gateway is up)
  adb_cmd logcat -d -v time 2>/dev/null > "$logcat_file" || true
  local milestones
  milestones="$(parse_logcat_milestones "$logcat_file" "$reboot_epoch")"
  local boot_completed_ev fgs_ev listen_ev boot_recv_ev banner_count
  IFS='|' read -r boot_completed_ev fgs_ev listen_ev boot_recv_ev banner_count <<< "$milestones"
  log "Milestones: boot_completed=$boot_completed_ev FGS=$fgs_ev listen=$listen_ev boot_recv=$boot_recv_ev banners=$banner_count"

  local t0_anchor="reboot_issued"
  if [[ "$boot_completed_ev" == "seen" ]]; then
    t0_anchor="boot_completed"
  fi

  # Build 10-channel list: first success + follow-on (dedupe)
  local all_channels=()
  if [[ -n "$first_ch" ]]; then
    all_channels+=("$first_ch")
  fi
  for ch in "${FOLLOW_ON[@]}"; do
    [[ "$ch" == "$first_ch" ]] && continue
    all_channels+=("$ch")
    ((${#all_channels[@]} >= 10)) && break
  done
  # If no first stream, still run follow-on for diagnostics
  if [[ ${#all_channels[@]} -lt 10 ]]; then
    for ch in "${FIRST_POLL[@]}"; do
      [[ " ${all_channels[*]} " == *" $ch "* ]] && continue
      all_channels+=("$ch")
      ((${#all_channels[@]} >= 10)) && break
    done
  fi

  # Phase: cold sequential (skip first if already probed successfully — re-probe for latency)
  declare -a cold_codes cold_ms cold_playable cold_notes
  log "Cold sequential pass (${#all_channels[@]} channels): ${all_channels[*]}"
  for ch in "${all_channels[@]}"; do
    local pr code ms playable notes
    pr="$(probe_stream "$base" "$ch")"
    IFS='|' read -r code ms playable notes <<< "$pr"
    cold_codes+=("$code")
    cold_ms+=("$ms")
    cold_playable+=("$playable")
    cold_notes+=("$notes")
    log "  cold ch=$ch HTTP=$code ${ms}ms playable=$playable $notes"
    [[ "$notes" == "HTTP_502" ]] && err502=$((err502 + 1))
    [[ "$notes" == "HTTP_503" ]] && err503=$((err503 + 1))
    [[ "$notes" == "upstream_busy" ]] && err_busy=$((err_busy + 1))
  done

  # Phase: warm re-tune
  declare -a warm_codes warm_ms warm_playable warm_notes
  log "Warm re-tune pass"
  for ch in "${all_channels[@]}"; do
    local pr code ms playable notes
    pr="$(probe_stream "$base" "$ch")"
    IFS='|' read -r code ms playable notes <<< "$pr"
    warm_codes+=("$code")
    warm_ms+=("$ms")
    warm_playable+=("$playable")
    warm_notes+=("$notes")
    log "  warm ch=$ch HTTP=$code ${ms}ms playable=$playable $notes"
  done

  # Stats
  local cold_p50 cold_p95 warm_p50 warm_p95 cold_ok warm_ok
  cold_p50=$(percentile 50 "${cold_ms[@]}")
  cold_p95=$(percentile 95 "${cold_ms[@]}")
  warm_p50=$(percentile 50 "${warm_ms[@]}")
  warm_p95=$(percentile 95 "${warm_ms[@]}")
  cold_ok=0 warm_ok=0
  for p in "${cold_playable[@]}"; do [[ "$p" == "1" ]] && cold_ok=$((cold_ok + 1)); done
  for p in "${warm_playable[@]}"; do [[ "$p" == "1" ]] && warm_ok=$((warm_ok + 1)); done

  local version channels
  version="$(echo "$health_body" | grep -oP '"version"\s*:\s*"\K[^"]+' || echo "?")"
  channels="$(echo "$health_body" | grep -oP '"channels"\s*:\s*\K[0-9]+' || echo "?")"

  local banner_verdict="PASS"
  if [[ "$banner_count" -gt 1 ]]; then banner_verdict="FAIL (count=$banner_count)"; fi
  if [[ "$banner_count" -eq 0 ]]; then banner_verdict="WARN (none in logcat)"; fi

  local overall="PASS"
  if [[ -z "$health_s" || -z "$first_stream_s" ]]; then
    overall="FAIL"
  elif [[ "$cold_ok" -lt 8 || "$warm_ok" -lt 10 ]]; then
    overall="WARN"
  fi
  if echo "$boot_recv_ev" | grep -q 'ANR'; then
    overall="FAIL"
  fi

  {
    echo "================================================================"
    echo "  FUSA Boot + Stream Timing Benchmark"
    echo "================================================================"
    echo "Timestamp (UTC): $TS"
    echo "Device:          $SERIAL"
    echo "Package:         $PKG"
    echo "Gateway:         ${base:-UNKNOWN}"
    echo "APK version:     $version"
    echo "Channels:        $channels"
    echo "T=0 anchor:      $t0_anchor"
    echo "Overall:         $overall"
    echo ""
    echo "=== BOOT MILESTONES ==="
    echo "| Event              | Status |"
    echo "|--------------------|--------|"
    echo "| boot_completed     | $boot_completed_ev |"
    echo "| BootReceiver chain | $boot_recv_ev |"
    echo "| FGS start          | $fgs_ev |"
    echo "| Gateway listening  | $listen_ev |"
    echo "| Health first 200   | ${health_s:-TIMEOUT}s |"
    echo "| Startup banner     | $banner_verdict (events=$banner_count) |"
    echo ""
    echo "=== TIME TO FIRST STREAM (TiviMate-usable) ==="
    echo "TIME_TO_FIRST_STREAM: ${first_stream_s:-TIMEOUT}s"
    echo "FIRST_CHANNEL:        ${first_ch:-none}"
    echo "PROBE_ATTEMPTS:       $probe_attempts"
    echo "ERRORS_502:           $err502"
    echo "ERRORS_503:           $err503"
    echo "UPSTREAM_BUSY:        $err_busy"
    echo ""
    echo "=== COLD SEQUENTIAL (${#all_channels[@]} channels) ==="
    echo "| Channel | HTTP | ms | Playable | Notes |"
    echo "|---------|------|-----|----------|-------|"
    local i=0
    for ch in "${all_channels[@]}"; do
      echo "| $ch | ${cold_codes[$i]} | ${cold_ms[$i]} | ${cold_playable[$i]} | ${cold_notes[$i]} |"
      i=$((i + 1))
    done
    echo ""
    echo "COLD_P50_MS: $cold_p50"
    echo "COLD_P95_MS: $cold_p95"
    echo "PLAYABLE_COLD: ${cold_ok}/${#all_channels[@]}"
    echo ""
    echo "=== WARM RE-TUNE ==="
    echo "| Channel | HTTP | ms | Playable | Notes |"
    echo "|---------|------|-----|----------|-------|"
    i=0
    for ch in "${all_channels[@]}"; do
      echo "| $ch | ${warm_codes[$i]} | ${warm_ms[$i]} | ${warm_playable[$i]} | ${warm_notes[$i]} |"
      i=$((i + 1))
    done
    echo ""
    echo "WARM_P50_MS: $warm_p50"
    echo "WARM_P95_MS: $warm_p95"
    echo "PLAYABLE_WARM: ${warm_ok}/${#all_channels[@]}"
    echo ""
    echo "=== ARTIFACTS ==="
    echo "Log:     $LOG"
    echo "Logcat:  $logcat_file"
    echo "Script:  stepdaddy-android/scripts/fusa-boot-stream-benchmark.sh"
  } | tee "$REPORT"

  log "Report saved: $REPORT"
  [[ "$overall" == "FAIL" ]] && exit 1 || exit 0
}

main "$@"

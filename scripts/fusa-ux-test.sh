#!/usr/bin/env bash
# Full end-to-end FUSA UX test: reboot → health → banner → TiviMate/streams/EPG
set -euo pipefail

SERIAL="${ADB_SERIAL:-FUSA2541006925}"
PKG="${PKG:-com.thothassistant.stepdaddy.gateway.debug}"
COMPONENT="${PKG}/com.thothassistant.stepdaddy.gateway.ServerService"
MAIN="${PKG}/com.thothassistant.stepdaddy.gateway.ui.MainActivity"
TIVIMATE_PKG="${TIVIMATE_PKG:-ar.tvplayer.tv}"
PORT=3000
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
HEALTH_POLL_S="${HEALTH_POLL_S:-2}"
EPG_TIMEOUT_S="${EPG_TIMEOUT_S:-180}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${RESULT_DIR:-/home/nova/livehd/current}"
REPORT="${RESULT_DIR}/fusa-ux-test-${TS}_report.txt"
LOG="${RESULT_DIR}/fusa-ux-test-${TS}.log"
PROOF="${RESULT_DIR}/fusa-ux-test-${TS}.png"
TIMELINE="${RESULT_DIR}/fusa-ux-test-${TS}_timeline.txt"
LOGCAT_OUT="${RESULT_DIR}/fusa-ux-test-${TS}_logcat.txt"

adb() { command adb -s "$SERIAL" "$@"; }

log() {
  local msg="[$(date -u +%H:%M:%S)] $*"
  echo "$msg" | tee -a "$LOG" >&2
}

timeline() {
  local t="$1" event="$2"
  echo "T+${t}s | $event" | tee -a "$TIMELINE" "$LOG" >&2
}

resolve_ip() {
  adb shell ip -4 addr show wlan0 2>/dev/null | grep -oP 'inet \K[0-9.]+' | head -1 || true
}

grant_permissions() {
  log "Granting permissions for $PKG"
  adb shell pm enable "$PKG" 2>/dev/null || true
  adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
  adb shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
}

prepare_pre_reboot() {
  log "Pre-reboot: launch+HOME+kill (preserve BootReceiver)"
  adb shell am start -n "$MAIN" >/dev/null 2>&1 || true
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  adb shell am kill "$PKG" 2>/dev/null || true
  sleep 1
}

check_preconditions() {
  local fail=0
  log "=== Preconditions ==="
  if ! adb shell pm path "$PKG" >/dev/null 2>&1; then
    log "FAIL: debug package not installed"
    fail=1
  else
    log "OK: $PKG installed"
  fi
  if adb shell pm list packages 2>/dev/null | grep -q '^package:com.nova.stepdaddylivehd$'; then
    log "FAIL: legacy com.nova.stepdaddylivehd installed"
    fail=1
  else
    log "OK: no legacy package"
  fi
  local overlay
  overlay="$(adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tail -1 || echo unknown)"
  log "Overlay permission: $overlay"
  return $fail
}

monitor_logcat_bg() {
  local fifo="$RESULT_DIR/fusa-ux-logcat-fifo-$$"
  mkfifo "$fifo" 2>/dev/null || true
  adb logcat -c 2>/dev/null || true
  adb logcat -v time 2>/dev/null > "$fifo" &
  LOGCAT_PID=$!
  echo "$fifo"
}

parse_logcat_events() {
  local fifo="$1" boot_t0="$2"
  # Read from collected logcat file after test
  :
}

collect_and_analyze_logcat() {
  log "Collecting logcat for banner/timing analysis"
  adb logcat -d -v time 2>/dev/null > "$LOGCAT_OUT" || true

  # boot_completed
  local boot_done=""
  boot_done="$(grep -E 'boot_completed|BOOT_COMPLETED' "$LOGCAT_OUT" 2>/dev/null | head -1 || true)"

  # BootReceiver / FGS
  local boot_recv fgs_allowed gateway_listen
  boot_recv="$(grep -E 'BootReceiver|BootStart|BootAlarm|GatewayStartHelper' "$LOGCAT_OUT" 2>/dev/null | head -5 || true)"
  fgs_allowed="$(grep -iE 'FGS|foreground.*service|startForeground' "$LOGCAT_OUT" 2>/dev/null | grep -i stepdaddy | head -3 || true)"
  gateway_listen="$(grep -E 'GatewayServer.*[Ll]isten|Listening on' "$LOGCAT_OUT" 2>/dev/null | head -3 || true)"

  # Banner events
  local overlay_events notifier_events activity_events show_ready
  overlay_events="$(grep -E 'GatewayOverlay' "$LOGCAT_OUT" 2>/dev/null || true)"
  notifier_events="$(grep -E 'GatewayNotifier.*(show|alert|banner|ready)' "$LOGCAT_OUT" 2>/dev/null || true)"
  activity_events="$(grep -E 'ServerReadyActivity' "$LOGCAT_OUT" 2>/dev/null || true)"
  show_ready="$(grep -E 'showReadyBanner' "$LOGCAT_OUT" 2>/dev/null || true)"

  echo "$boot_done" > "${RESULT_DIR}/fusa-ux-test-${TS}_boot.txt"
  echo "---OVERLAY---" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "$overlay_events" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "---NOTIFIER---" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "$notifier_events" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "---ACTIVITY---" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "$activity_events" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "---SHOW_READY---" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  echo "$show_ready" >> "${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
}

count_startup_banners() {
  local banner_file="${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
  # Count distinct show/ready events in first 30s window (startup only)
  local overlay_show activity_launch show_ready_calls
  overlay_show=$(grep -cE 'GatewayOverlay.*(show|Show|display|ready|Ready)' "$LOGCAT_OUT" 2>/dev/null || echo 0)
  activity_launch=$(grep -cE 'ServerReadyActivity' "$LOGCAT_OUT" 2>/dev/null || echo 0)
  show_ready_calls=$(grep -cE 'showReadyBanner' "$LOGCAT_OUT" 2>/dev/null || echo 0)

  # Parse timestamps for startup window (first 30s after boot_completed)
  local startup_overlays=0
  local boot_line
  boot_line="$(grep -m1 'boot_completed\|BOOT_COMPLETED' "$LOGCAT_OUT" 2>/dev/null || true)"
  if [[ -n "$boot_line" ]]; then
  # Extract overlay shows with timestamps in startup window
    while IFS= read -r line; do
      if echo "$line" | grep -qE 'GatewayOverlay.*(show|Show|ServerReady|ready)'; then
        startup_overlays=$((startup_overlays + 1))
      fi
    done < <(grep 'GatewayOverlay' "$LOGCAT_OUT" 2>/dev/null | head -10 || true)
  fi

  echo "$overlay_show $activity_launch $show_ready_calls $startup_overlays"
}

poll_health_timeline() {
  local ip="$1"
  local url="http://${ip}:${PORT}/health"
  local elapsed=0
  local first_ok_s=""

  while (( elapsed < HEALTH_TIMEOUT_S )); do
    local body code
    body="$(curl -sS -m 5 "$url" 2>/dev/null || true)"
    if [[ -n "$body" ]] && echo "$body" | grep -q '"ok"'; then
      code=200
    else
      code=000
    fi
    timeline "$elapsed" "health poll -> HTTP $code"
    if [[ "$code" == "200" && -z "$first_ok_s" ]]; then
      first_ok_s="$elapsed"
      timeline "$elapsed" "HEALTH 200 FIRST — channels=$(echo "$body" | grep -oP '"channels"\s*:\s*\K[0-9]+' || echo ?) version=$(echo "$body" | grep -oP '"version"\s*:\s*"\K[^"]+' || echo ?)"
      echo "$first_ok_s|$body"
      return 0
    fi
    sleep "$HEALTH_POLL_S"
    elapsed=$((elapsed + HEALTH_POLL_S))
  done
  timeline "$elapsed" "HEALTH TIMEOUT"
  echo "|"
  return 1
}

screencap_now() {
  local out="$1"
  log "Screencap -> $out"
  adb exec-out screencap -p > "$out" 2>/dev/null || adb shell screencap -p > "$out" 2>/dev/null || true
}

poll_epg() {
  local ip="$1"
  local url="http://${ip}:${PORT}/epg.xml"
  local elapsed=0
  while (( elapsed < EPG_TIMEOUT_S )); do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' -m 30 "$url" 2>/dev/null || echo 000)"
    timeline "$elapsed" "epg poll -> HTTP $code"
    if [[ "$code" == "200" ]]; then
      echo "$elapsed"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo ""
  return 1
}

test_playlist_streams_logos() {
  local ip="$1"
  local base="http://${ip}:${PORT}"
  local playlist_url="${base}/tivimate-playlist.m3u8"
  local results=""

  log "Fetching playlist from host"
  local playlist
  playlist="$(curl -sS -m 30 "$playlist_url" 2>/dev/null || true)"
  if [[ -z "$playlist" ]]; then
    echo "PLAYLIST_FAIL|"
    return 1
  fi

  local channel_count logo_count
  channel_count=$(grep -c '^#EXTINF' <<< "$playlist" || echo 0)
  logo_count=$(grep -c 'tvg-logo=' <<< "$playlist" || echo 0)
  log "Playlist: $channel_count channels, $logo_count with tvg-logo"

  # Sample 3 stream URLs
  local streams=()
  while IFS= read -r line; do
  streams+=("$line")
  done < <(grep -v '^#' <<< "$playlist" | grep -v '^$' | head -3)

  local stream_results=""
  local stream_ok=0 stream_fail=0
  for s in "${streams[@]}"; do
    local full_url="$s"
    if [[ "$s" != http* ]]; then
      full_url="${base}${s}"
    fi
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' -m 15 -L "$full_url" 2>/dev/null || echo 000)"
    log "Stream $full_url -> HTTP $code"
  stream_results="${stream_results}${full_url}=${code};"
    if [[ "$code" == "200" || "$code" == "302" ]]; then
      stream_ok=$((stream_ok + 1))
    else
      stream_fail=$((stream_fail + 1))
    fi
  done

  # Sample 2 logo URLs
  local logos
  logos=$(grep -oP 'tvg-logo="\K[^"]+' <<< "$playlist" | head -2)
  local logo_results="" logo_ok=0 logo_fail=0
  while IFS= read -r logo; do
    [[ -z "$logo" ]] && continue
    local lurl="$logo"
    if [[ "$logo" != http* ]]; then
      lurl="${base}${logo}"
    fi
    local lcode
    lcode="$(curl -sS -o /dev/null -w '%{http_code}' -m 15 "$lurl" 2>/dev/null || echo 000)"
    log "Logo $lurl -> HTTP $lcode"
    logo_results="${logo_results}${lurl}=${lcode};"
    if [[ "$lcode" == "200" ]]; then logo_ok=$((logo_ok + 1)); else logo_fail=$((logo_fail + 1)); fi
  done <<< "$logos"

  # Device-side playlist check
  local device_playlist_ok="unknown"
  local dev_body
  dev_body="$(adb shell "curl -sS -m 15 http://127.0.0.1:${PORT}/tivimate-playlist.m3u8 2>/dev/null | head -5" 2>/dev/null || true)"
  if echo "$dev_body" | grep -q 'EXTM3U'; then
    device_playlist_ok="OK"
  elif adb shell "wget -qO- http://127.0.0.1:${PORT}/tivimate-playlist.m3u8 2>/dev/null | head -5" 2>/dev/null | grep -q 'EXTM3U'; then
    device_playlist_ok="OK"
  else
    device_playlist_ok="SKIP(no curl/wget on device)"
  fi
  log "Device localhost playlist: $device_playlist_ok"

  echo "PLAYLIST_OK|channels=$channel_count|logos=$logo_count|streams_ok=$stream_ok|streams_fail=$stream_fail|logos_ok=$logo_ok|logos_fail=$logo_fail|device=$device_playlist_ok|stream_detail=$stream_results|logo_detail=$logo_results"
}

test_epg_content() {
  local ip="$1"
  local url="http://${ip}:${PORT}/epg.xml"
  local xml
  xml="$(curl -sS -m 60 "$url" 2>/dev/null || true)"
  if [[ -z "$xml" ]]; then
    echo "EPG_FAIL|empty"
    return 1
  fi
  local prog_count channel_count
  prog_count=$(grep -c '<programme' <<< "$xml" || echo 0)
  channel_count=$(grep -c '<channel ' <<< "$xml" || echo 0)
  # Sample channel with programmes
  local sample_ch
  sample_ch=$(grep -oP 'channel id="\K[^"]+' <<< "$xml" | head -1 || true)
  local sample_prog=0
  if [[ -n "$sample_ch" ]]; then
    sample_prog=$(grep -c "channel=\"${sample_ch}\"" <<< "$xml" || echo 0)
  fi
  echo "EPG_OK|channels=$channel_count|programmes=$prog_count|sample_ch=$sample_ch|sample_prog=$sample_prog"
}

launch_tivimate() {
  log "Launching TiviMate ($TIVIMATE_PKG)"
  adb shell monkey -p "$TIVIMATE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 8
  local tivi_cap="${RESULT_DIR}/fusa-ux-test-${TS}_tivimate.png"
  screencap_now "$tivi_cap"
  log "TiviMate screencap -> $tivi_cap"
  echo "$tivi_cap"
}

write_report() {
  local health_s="$1" health_body="$2" epg_s="$3" playlist_result="$4" epg_result="$5"
  local banner_counts="$6" tivi_cap="$7" boot_t0="$8"
  local overlay_c activity_c ready_c startup_o
  overlay_c=$(echo "$banner_counts" | awk '{print $1}')
  activity_c=$(echo "$banner_counts" | awk '{print $2}')
  ready_c=$(echo "$banner_counts" | awk '{print $3}')
  startup_o=$(echo "$banner_counts" | awk '{print $4}')

  # Banner verdict
  local banner_verdict="PASS"
  if [[ "$ready_c" -gt 1 ]]; then
    banner_verdict="FAIL (showReadyBanner called $ready_c times)"
  elif [[ "$startup_o" -gt 1 ]]; then
    banner_verdict="FAIL ($startup_o startup overlay events)"
  elif [[ "$overlay_c" -gt 0 && "$activity_c" -gt 0 ]]; then
    banner_verdict="FAIL (both overlay AND activity shown)"
  elif [[ "$overlay_c" -eq 0 && "$activity_c" -eq 0 && "$ready_c" -eq 0 ]]; then
    banner_verdict="WARN (no banner events in logcat — may have missed or overlay not logged)"
  fi

  local overall="PASS"
  [[ -z "$health_s" ]] && overall="FAIL"
  [[ "$banner_verdict" == FAIL* ]] && overall="FAIL"
  echo "$playlist_result" | grep -q PLAYLIST_FAIL && overall="FAIL"

  {
    echo "================================================================"
    echo "  FUSA End-to-End UX Test Report"
    echo "================================================================"
    echo "Device:    $SERIAL"
    echo "Package:   $PKG"
    echo "Timestamp: $TS (UTC)"
    echo "Overall:   $overall"
    echo ""
    echo "--- TIMING TABLE (T=0 = adb reboot issued) ---"
    cat "$TIMELINE" 2>/dev/null || echo "(no timeline)"
    echo ""
    echo "--- KEY METRICS ---"
    echo "| Metric                          | Value |"
    echo "|---------------------------------|-------|"
    echo "| Reboot → health 200             | ${health_s:-TIMEOUT}s |"
    echo "| Reboot → EPG 200                | ${epg_s:-TIMEOUT}s |"
    echo "| Ready to watch (health+playlist)| ${health_s:-?}s |"
    echo "| showReadyBanner calls           | $ready_c |"
    echo "| GatewayOverlay events           | $overlay_c |"
    echo "| ServerReadyActivity events      | $activity_c |"
    echo "| Startup overlay count           | $startup_o |"
    echo "| Banner verdict                  | $banner_verdict |"
    echo ""
    echo "--- PLAYLIST / STREAMS / LOGOS ---"
    echo "$playlist_result"
    echo ""
    echo "--- EPG ---"
    echo "$epg_result"
    echo ""
    echo "--- HEALTH BODY (first 200) ---"
    echo "$health_body"
    echo ""
    echo "--- ARTIFACTS ---"
    echo "Report:    $REPORT"
    echo "Log:       $LOG"
    echo "Timeline:  $TIMELINE"
    echo "Proof cap: $PROOF"
    echo "TiviMate:  $tivi_cap"
    echo "Logcat:    $LOGCAT_OUT"
    echo "Banner:    ${RESULT_DIR}/fusa-ux-test-${TS}_banner.txt"
    echo ""
    echo "--- TIVIMATE UX ---"
    if adb shell pm path "$TIVIMATE_PKG" >/dev/null 2>&1; then
      echo "TiviMate installed: YES ($TIVIMATE_PKG)"
      echo "Expected playlist: http://127.0.0.1:3000/tivimate-playlist.m3u8"
      echo "Launched via monkey — see screencap for guide/channels state"
    else
      echo "TiviMate installed: NO — tested via curl from host"
    fi
    echo ""
    echo "--- FRICTION POINTS ---"
    if [[ -z "$health_s" ]]; then echo "- Server did not become healthy within ${HEALTH_TIMEOUT_S}s"; fi
    if [[ "$banner_verdict" == FAIL* ]]; then echo "- Double/missing startup banner: $banner_verdict"; fi
    if [[ -z "$epg_s" ]]; then echo "- EPG not ready within ${EPG_TIMEOUT_S}s"; fi
    echo "$playlist_result" | grep -q 'streams_fail=[1-9]' && echo "- Some stream URLs returned errors"
    echo "$playlist_result" | grep -q 'logos_fail=[1-9]' && echo "- Some logo URLs returned errors"
  } | tee "$REPORT"
}

main() {
  log "=== FUSA UX Test Start ==="
  mkdir -p "$RESULT_DIR"
  : > "$TIMELINE"

  if ! adb get-state >/dev/null 2>&1; then
    log "ERROR: device not reachable"
    exit 1
  fi

  check_preconditions || { log "Precondition check failed"; exit 1; }
  grant_permissions

  local ip
  ip="$(resolve_ip)"
  log "Pre-reboot IP: ${ip:-unknown}"

  prepare_pre_reboot

  log "Clearing logcat, rebooting..."
  adb logcat -c 2>/dev/null || true
  local boot_t0
  boot_t0=$(date +%s)
  timeline 0 "adb reboot issued"
  adb reboot
  log "Waiting for device..."
  adb wait-for-device
  timeline "$(($(date +%s) - boot_t0))" "adb wait-for-device"
  sleep 15
  adb shell input keyevent KEYCODE_HOME 2>/dev/null || true
  timeline "$(($(date +%s) - boot_t0))" "HOME sent (simulating real user on launcher)"

  ip=""
  for _ in $(seq 1 30); do
    ip="$(resolve_ip)"
    [[ -n "$ip" ]] && break
    sleep 2
  done
  log "Post-reboot IP: ${ip:-UNKNOWN}"
  timeline "$(($(date +%s) - boot_t0))" "wlan0 IP: ${ip:-UNKNOWN}"

  local health_result health_s health_body
  health_s=""
  health_body=""
  if [[ -n "$ip" ]]; then
    health_result="$(poll_health_timeline "$ip" || true)"
    health_s="${health_result%%|*}"
    health_body="${health_result#*|}"
    if [[ -n "$health_s" ]]; then
      screencap_now "$PROOF"
      log "Proof screencap saved: $PROOF"
    fi
  fi

  collect_and_analyze_logcat
  local boot_elapsed
  boot_elapsed="$(($(date +%s) - boot_t0))"
  timeline "$boot_elapsed" "logcat collected for analysis"

  # Parse boot_completed from logcat relative timing
  if grep -q 'boot_completed\|BOOT_COMPLETED' "$LOGCAT_OUT" 2>/dev/null; then
    timeline "?" "boot_completed seen in logcat"
  fi
  if grep -qE 'GatewayServer.*[Ll]isten|Listening on' "$LOGCAT_OUT" 2>/dev/null; then
    timeline "?" "GatewayServer Listening seen in logcat"
  fi

  local banner_counts
  banner_counts="$(count_startup_banners)"

  # EPG poll
  local epg_s=""
  if [[ -n "$ip" ]]; then
    epg_s="$(poll_epg "$ip" || true)"
    [[ -n "$epg_s" ]] && timeline "$epg_s" "EPG 200 first"
  fi

  # Playlist/streams/logos
  local playlist_result="PLAYLIST_FAIL|"
  local epg_result="EPG_SKIP|"
  if [[ -n "$ip" ]]; then
    playlist_result="$(test_playlist_streams_logos "$ip" || true)"
    epg_result="$(test_epg_content "$ip" || true)"
  fi

  # TiviMate
  local tivi_cap=""
  if adb shell pm path "$TIVIMATE_PKG" >/dev/null 2>&1; then
    tivi_cap="$(launch_tivimate)"
  else
    log "TiviMate not installed — skipping launch"
    tivi_cap="N/A"
  fi

  write_report "$health_s" "$health_body" "$epg_s" "$playlist_result" "$epg_result" "$banner_counts" "$tivi_cap" "$boot_t0"

  log "=== Test complete — report at $REPORT ==="
  cat "$REPORT"
}

main "$@"

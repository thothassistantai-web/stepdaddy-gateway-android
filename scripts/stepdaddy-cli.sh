#!/usr/bin/env bash
# StepDaddy Gateway CLI — control the running gateway via HTTP admin API.
set -euo pipefail

BASE="${STEPDADDY_URL:-http://127.0.0.1:3000}"
TOKEN="${STEPDADDY_TOKEN:-}"
JSON="${STEPDADDY_JSON:-python3 -m json.tool 2>/dev/null || cat}"

usage() {
  cat <<'EOF'
StepDaddy Gateway CLI

Environment:
  STEPDADDY_URL    Base URL (default: http://127.0.0.1:3000)
  STEPDADDY_TOKEN  Remote access token (X-StepDaddy-Token header)

Commands:
  help                         Show this help
  api                          List admin API endpoints
  health                       GET /health
  settings                     GET /api/v1/settings
  set KEY=VALUE [KEY=VALUE...]   PATCH /api/v1/settings (partial update)
  search QUERY                 Search channels by name
  refresh channels             Reload DaddyLive channel list
  refresh supplements          Sync supplement sources
  refresh epg                  Rebuild EPG
  refresh logos                Run logo backfill
  refresh tvg-ids              Backfill missing tvg-ids
  prewarm                      Rebuild playlist cache
  logo CHANNEL URL             Set runtime logo override
  unlogo CHANNEL               Remove runtime logo override
  epg-name CHANNEL TVG_ID      Set EPG name override
  epg-id CHANNEL_ID TVG_ID     Set EPG channel-id override
  resolve logo CHANNEL [TVG_ID] Probe logo lookup
  resolve epg CHANNEL          Probe EPG tvg-id lookup

  resolve stream CHANNEL [probe] Probe play URL (add probe to fetch manifest)
  channel ID                 Lookup channel by id
  stop                         Stop gateway service
  restart [http|full]          Restart HTTP engine or full gateway
  audit [GROUP]                Category misplacement audit
  move ID [ID...] GROUP        Move channels to category
  category ID|NAME GROUP       Set category override
  assets export TYPE [layer]     Export asset overlay (epg-name|logo|epg-id|category)
  assets import TYPE FILE.json   Import runtime asset overlay
  assets clear TYPE              Clear runtime asset overlay
  import-csv FILE.csv            Bulk EPG mapping import

Examples:
  ./scripts/stepdaddy-cli.sh health
  ./scripts/stepdaddy-cli.sh search hbo
  ./scripts/stepdaddy-cli.sh refresh all
  ./scripts/stepdaddy-cli.sh set supplementSportsEnabled=true dlhdBaseUrl=https://daddylive.li
  STEPDADDY_URL=http://192.168.1.50:3000 ./scripts/stepdaddy-cli.sh settings

ADB (device loopback):
  adb forward tcp:3000 tcp:3000
  ./scripts/stepdaddy-cli.sh health
EOF
}

auth_args() {
  if [[ -n "$TOKEN" ]]; then
    echo -H "X-StepDaddy-Token: $TOKEN"
  fi
}

request() {
  local method="$1"
  local path="$2"
  shift 2
  local body="${1:-}"
  local tmp
  tmp="$(mktemp)"
  local code
  local curl_args=(-sS -o "$tmp" -w '%{http_code}' -X "$method")
  if [[ -n "$TOKEN" ]]; then
    curl_args+=(-H "X-StepDaddy-Token: $TOKEN")
  fi
  if [[ -n "$body" ]]; then
    curl_args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  code="$(curl "${curl_args[@]}" "${BASE}${path}")"
  if [[ "$code" -ge 400 ]]; then
    echo "HTTP $code" >&2
    cat "$tmp" >&2
    rm -f "$tmp"
    exit 1
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -m json.tool "$tmp" 2>/dev/null || cat "$tmp"
  else
    cat "$tmp"
  fi
  rm -f "$tmp"
}

patch_settings_from_pairs() {
  local json='{'
  local first=1
  for pair in "$@"; do
    local key="${pair%%=*}"
    local value="${pair#*=}"
    [[ "$key" == "$value" ]] && { echo "Expected KEY=VALUE, got: $pair" >&2; exit 1; }
    local typed="$value"
    case "$key" in
      port) typed="$value" ;;
      supplementSportsEnabled|supplementIptvOrgEnabled|supplementNtvCxEnabled|\
supplementAdultSwimEnabled|embeddedSidecarEnabled|iptvOrgEpgEnabled|startOnBoot|autoStartOnLaunch|autoLaunchTiviMate)
        if [[ "$value" == "true" || "$value" == "1" ]]; then typed="true"
        elif [[ "$value" == "false" || "$value" == "0" ]]; then typed="false"
        else echo "Boolean expected for $key" >&2; exit 1
        fi ;;
      mirrorUrls)
        IFS=',' read -ra parts <<< "$value"
        typed='['
        local mfirst=1
        for part in "${parts[@]}"; do
          [[ $mfirst -eq 0 ]] && typed+=','
          typed+="\"${part//\"/\\\"}\""
          mfirst=0
        done
        typed+=']'
        ;;
      *)
        typed="\"${value//\"/\\\"}\""
        ;;
    esac
    [[ $first -eq 0 ]] && json+=','
    json+="\"$key\":$typed"
    first=0
  done
  json+='}'
  request PATCH /api/v1/settings "$json"
}

cmd="${1:-help}"
shift || true

case "$cmd" in
  help|-h|--help)
    usage
    ;;
  api)
    request GET /api/v1
    ;;
  health)
    request GET /health
    ;;
  settings)
    request GET /api/v1/settings
    ;;
  set)
    [[ $# -ge 1 ]] || { echo "Usage: set KEY=VALUE ..." >&2; exit 1; }
    patch_settings_from_pairs "$@"
    ;;
  search)
    [[ $# -ge 1 ]] || { echo "Usage: search QUERY" >&2; exit 1; }
    q="$(printf '%s' "$*" | jq -sRr @uri 2>/dev/null || python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$*")"
    request GET "/api/v1/channels?q=${q}"
    ;;
  refresh)
    sub="${1:-all}"
    shift || true
    case "$sub" in
      channels) request POST /api/v1/actions/refresh-channels ;;
      supplements) request POST /api/v1/actions/refresh-supplements ;;
      epg) request POST /api/v1/actions/refresh-epg ;;
      logos) request POST /api/v1/actions/refresh-logos ;;
      tvg-ids) request POST /api/v1/actions/refresh-tvg-ids ;;
      all)
        request POST /api/v1/actions/refresh-channels
        request POST /api/v1/actions/refresh-supplements
        request POST /api/v1/actions/refresh-tvg-ids
        request POST /api/v1/actions/refresh-logos
        request POST /api/v1/actions/refresh-epg
        request POST /api/v1/actions/prewarm-playlist
        ;;
      *) echo "Unknown refresh target: $sub" >&2; exit 1 ;;
    esac
    ;;
  prewarm)
    request POST /api/v1/actions/prewarm-playlist
    ;;
  logo)
    [[ $# -eq 2 ]] || { echo "Usage: logo CHANNEL URL" >&2; exit 1; }
    name="$1"
    url="$2"
    body="$(printf '{"channelName":"%s","url":"%s"}' "${name//\"/\\\"}" "${url//\"/\\\"}")"
    request POST /api/v1/overrides/logo "$body"
    ;;
  unlogo)
    [[ $# -eq 1 ]] || { echo "Usage: unlogo CHANNEL" >&2; exit 1; }
    q="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1")"
    request DELETE "/api/v1/overrides/logo?channelName=${q}"
    ;;
  epg-name)
    [[ $# -eq 2 ]] || { echo "Usage: epg-name CHANNEL TVG_ID" >&2; exit 1; }
    body="$(printf '{"channelName":"%s","tvgId":"%s"}' "${1//\"/\\\"}" "${2//\"/\\\"}")"
    request POST /api/v1/overrides/epg-name "$body"
    ;;
  epg-id)
    [[ $# -eq 2 ]] || { echo "Usage: epg-id CHANNEL_ID TVG_ID" >&2; exit 1; }
    body="$(printf '{"channelId":"%s","tvgId":"%s"}' "${1//\"/\\\"}" "${2//\"/\\\"}")"
    request POST /api/v1/overrides/epg-id "$body"
    ;;
  resolve)
    sub="${1:-}"
    shift || true
    case "$sub" in
      logo)
        [[ $# -ge 1 ]] || { echo "Usage: resolve logo CHANNEL [TVG_ID]" >&2; exit 1; }
        name="$1"
        tvg="${2:-}"
        q="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$name")"
        path="/api/v1/resolve/logo?channelName=${q}"
        [[ -n "$tvg" ]] && path="${path}&tvgId=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$tvg")"
        request GET "$path"
        ;;
      epg)
        [[ $# -eq 1 ]] || { echo "Usage: resolve epg CHANNEL" >&2; exit 1; }
        q="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1")"
        request GET "/api/v1/resolve/epg?channelName=${q}"
        ;;
      stream)
        [[ $# -ge 1 ]] || { echo "Usage: resolve stream CHANNEL [probe]" >&2; exit 1; }
        id="$1"
        probe="${2:-}"
        q="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$id")"
        path="/api/v1/resolve/stream?channelId=${q}"
        [[ "$probe" == "probe" ]] && path="${path}&probe=true"
        request GET "$path"
        ;;
      *) echo "Usage: resolve logo|epg|stream ..." >&2; exit 1 ;;
    esac
    ;;
  channel)
    [[ $# -eq 1 ]] || { echo "Usage: channel ID" >&2; exit 1; }
    q="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1")"
    request GET "/api/v1/channels/${q}"
    ;;
  stop)
    request POST /api/v1/actions/stop
    ;;
  restart)
    scope="${1:-http}"
    request POST "/api/v1/actions/restart?scope=${scope}"
    ;;
  audit)
    group="${1:-}"
    path="/api/v1/categories/audit?limit=50"
    [[ -n "$group" ]] && path="${path}&group=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$group")"
    request GET "$path"
    ;;
  move)
    [[ $# -ge 2 ]] || { echo "Usage: move ID [ID...] GROUP" >&2; exit 1; }
    group="${!#}"
    ids=("${@:1:$#-1}")
    json_ids="$(printf '"%s",' "${ids[@]}")"
    json_ids="[${json_ids%,}]"
    body="$(printf '{"channelIds":%s,"groupTitle":"%s"}' "$json_ids" "$group")"
    request POST /api/v1/categories/move "$body"
    ;;
  category)
    [[ $# -eq 2 ]] || { echo "Usage: category ID|NAME GROUP" >&2; exit 1; }
    if [[ "$1" =~ ^[0-9]+$|^[a-z]+: ]]; then
      body="$(printf '{"channelId":"%s","groupTitle":"%s"}' "${1//\"/\\\"}" "${2//\"/\\\"}")"
    else
      body="$(printf '{"channelName":"%s","groupTitle":"%s"}' "${1//\"/\\\"}" "${2//\"/\\\"}")"
    fi
    request POST /api/v1/overrides/category "$body"
    ;;
  assets)
    sub="${1:-}"
    shift || true
    case "$sub" in
      export)
        type="${1:-epg-name}"
        layer="${2:-runtime}"
        request GET "/api/v1/assets/${type}?layer=${layer}"
        ;;
      import)
        type="${1:-}"
        file="${2:-}"
        [[ -n "$type" && -f "$file" ]] || { echo "Usage: assets import TYPE FILE.json" >&2; exit 1; }
        body="$(python3 -c "import json,sys; print(json.dumps({'entries':json.load(open(sys.argv[1])),'merge':True}))" "$file")"
        request POST "/api/v1/assets/${type}" "$body"
        ;;
      clear)
        type="${1:-}"
        [[ -n "$type" ]] || { echo "Usage: assets clear TYPE" >&2; exit 1; }
        request DELETE "/api/v1/assets/${type}"
        ;;
      *) echo "Usage: assets export|import|clear ..." >&2; exit 1 ;;
    esac
    ;;
  import-csv)
    [[ $# -eq 1 && -f "$1" ]] || { echo "Usage: import-csv FILE.csv" >&2; exit 1; }
    tmp="$(mktemp)"
    args=(-sS -o "$tmp" -w '%{http_code}' -X POST -H 'Content-Type: text/csv' --data-binary @"$1")
    [[ -n "$TOKEN" ]] && args+=(-H "X-StepDaddy-Token: $TOKEN")
    code="$(curl "${args[@]}" "${BASE}/api/v1/import/epg-csv")"
    if [[ "$code" -ge 400 ]]; then echo "HTTP $code" >&2; cat "$tmp" >&2; rm -f "$tmp"; exit 1; fi
    python3 -m json.tool "$tmp" 2>/dev/null || cat "$tmp"
    rm -f "$tmp"
    ;;
  test)
    echo "== StepDaddy API smoke test =="
    request GET /health
    request GET /api/v1
    request GET /api/v1/settings
    request GET "/api/v1/channels?q=cnn&limit=3"
    request GET "/api/v1/categories/audit?limit=5"
    request POST /api/v1/actions/prewarm-playlist
    echo "== smoke test complete =="
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage >&2
    exit 1
    ;;
esac

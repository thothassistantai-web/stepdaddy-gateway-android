#!/usr/bin/env bash
# Refresh dulo.cx Live TV Supabase JWT from Linux keyring and optionally PATCH a gateway.
#
# Keyring attributes (application=stepdaddy-gateway service=dulo.cx):
#   email | password | dulo-cx-access-token | dulo-cx-refresh-token
#   supabase-url | supabase-publishable-key
#
# Usage:
#   bash scripts/dulo-cx-auth.sh
#   bash scripts/dulo-cx-auth.sh --set-gateway http://192.168.1.50:3000
#   GATEWAY_URL=http://127.0.0.1:3000 bash scripts/dulo-cx-auth.sh --set-gateway
#
# See docs/DULO-AUTH.md
set -euo pipefail

APP=(application stepdaddy-gateway service dulo.cx)
SUPA_URL_DEFAULT='https://wsudbodtjjfenprwsagd.supabase.co'
PUB_DEFAULT='sb_publishable_521pnlSRNoR0xpBn6uiuHw_f78kT63_'
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/stepdaddy-gateway"
FALLBACK_ENV="${CONFIG_DIR}/dulo-cx.env"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dulo-cx-auth.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

GATEWAY_URL="${GATEWAY_URL:-}"
SET_GATEWAY=0
PRINT_TOKEN=0
PROBE=1

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \?//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --set-gateway)
      SET_GATEWAY=1
      if [[ $# -ge 2 && "$2" != --* ]]; then GATEWAY_URL="$2"; shift; fi
      ;;
    --print-token) PRINT_TOKEN=1 ;;
    --no-probe) PROBE=0 ;;
    *) echo "Unknown arg: $1" >&2; usage 2 ;;
  esac
  shift
done

if [[ "$SET_GATEWAY" -eq 1 && -z "${GATEWAY_URL}" ]]; then
  echo "ERROR: --set-gateway needs a URL or GATEWAY_URL" >&2
  exit 2
fi

command -v secret-tool >/dev/null || { echo "ERROR: secret-tool not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 not found" >&2; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl not found" >&2; exit 1; }

lookup() { secret-tool lookup "${APP[@]}" attribute "$1" 2>/dev/null || true; }

store() {
  local attr="$1" label="$2" value="$3"
  printf '%s' "$value" | secret-tool store --label="$label" "${APP[@]}" attribute "$attr"
}

if [[ -f "$FALLBACK_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$FALLBACK_ENV"
fi

EMAIL="$(lookup email)"; EMAIL="${EMAIL:-${DULO_CX_EMAIL:-}}"
PASSWORD="$(lookup password)"; PASSWORD="${PASSWORD:-${DULO_CX_PASSWORD:-}}"
REFRESH_TOKEN="$(lookup dulo-cx-refresh-token)"; REFRESH_TOKEN="${REFRESH_TOKEN:-${DULO_CX_REFRESH_TOKEN:-}}"
SUPA_URL="$(lookup supabase-url)"; SUPA_URL="${SUPA_URL:-${DULO_CX_SUPABASE_URL:-$SUPA_URL_DEFAULT}}"
PUB="$(lookup supabase-publishable-key)"; PUB="${PUB:-${DULO_CX_SUPABASE_PUBLISHABLE_KEY:-$PUB_DEFAULT}}"

if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
  echo "ERROR: dulo.cx email/password missing from keyring (and no ${FALLBACK_ENV})" >&2
  exit 1
fi

EMAIL="$EMAIL" PASSWORD="$PASSWORD" REFRESH_TOKEN="$REFRESH_TOKEN" \
SUPA_URL="$SUPA_URL" PUB="$PUB" OUT_DIR="$TMP_DIR" python3 <<'PY'
import json, os, urllib.request, sys
from pathlib import Path

out = Path(os.environ["OUT_DIR"])

def post(grant: str, payload: dict):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        f'{os.environ["SUPA_URL"]}/auth/v1/token?grant_type={grant}',
        data=body,
        method="POST",
        headers={
            "apikey": os.environ["PUB"],
            "Authorization": f'Bearer {os.environ["PUB"]}',
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except Exception as e:
        raw = getattr(e, "read", lambda: b"")()
        try:
            data = json.loads(raw) if raw else {"error": str(e)}
        except Exception:
            data = {"error": str(e)}
        return getattr(e, "code", None), data

method = None
data = None
rt = os.environ.get("REFRESH_TOKEN") or ""
if rt:
    _, data = post("refresh_token", {"refresh_token": rt})
    if data.get("access_token"):
        method = "refresh_token"
if not method:
    _, data = post("password", {"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"]})
    if data.get("access_token"):
        method = "password"
if not method:
    msg = data.get("msg") or data.get("error_description") or data.get("error") or data
    print(f"ERROR: auth failed ({msg})", file=sys.stderr)
    # Google-only accounts often have no password identity.
    print(
        "Hint: account may be Google OAuth-only. Sign in once at https://dulo.cx/login "
        "(Continue with Google), then re-run — or set a password via Forgot password.",
        file=sys.stderr,
    )
    sys.exit(1)

access = data["access_token"]
refresh = data.get("refresh_token") or rt
(out / "access").write_text(access)
(out / "refresh").write_text(refresh)
(out / "method").write_text(method)
print(f"auth: ok method={method} access_len={len(access)} refresh_len={len(refresh)}")
PY

ACCESS_TOKEN="$(cat "$TMP_DIR/access")"
REFRESH_TOKEN="$(cat "$TMP_DIR/refresh")"
AUTH_METHOD="$(cat "$TMP_DIR/method")"

store dulo-cx-access-token "dulo.cx Live TV access token (JWT)" "$ACCESS_TOKEN"
[[ -n "$REFRESH_TOKEN" ]] && store dulo-cx-refresh-token "dulo.cx Live TV refresh token" "$REFRESH_TOKEN"
store email "dulo.cx account email" "$EMAIL"
store password "dulo.cx account password" "$PASSWORD"
store supabase-url "dulo.cx supabase url" "$SUPA_URL"
store supabase-publishable-key "dulo.cx supabase publishable key" "$PUB"

# Fallback file ONLY if keyring cannot read back the token.
if [[ -z "$(lookup dulo-cx-access-token)" ]]; then
  mkdir -p "$CONFIG_DIR"
  umask 077
  {
    echo "# Machine-local dulo.cx auth fallback (mode 600). Do not commit."
    printf 'DULO_CX_EMAIL=%q\n' "$EMAIL"
    printf 'DULO_CX_PASSWORD=%q\n' "$PASSWORD"
    printf 'DULO_CX_ACCESS_TOKEN=%q\n' "$ACCESS_TOKEN"
    printf 'DULO_CX_REFRESH_TOKEN=%q\n' "$REFRESH_TOKEN"
    printf 'DULO_CX_SUPABASE_URL=%q\n' "$SUPA_URL"
    printf 'DULO_CX_SUPABASE_PUBLISHABLE_KEY=%q\n' "$PUB"
  } >"$FALLBACK_ENV"
  chmod 600 "$FALLBACK_ENV"
  echo "Wrote fallback ${FALLBACK_ENV} (mode 600) — keyring write/read failed" >&2
fi

if [[ "$PROBE" -eq 1 ]]; then
  ACCESS_TOKEN="$ACCESS_TOKEN" python3 <<'PY'
import json, os, sys, urllib.request
token = os.environ["ACCESS_TOKEN"]
req = urllib.request.Request(
    "https://dulo.cx/api/live-tv/channels",
    headers={"User-Agent": "Mozilla/5.0", "Origin": "https://dulo.cx", "Referer": "https://dulo.cx/live"},
)
with urllib.request.urlopen(req, timeout=30) as r:
    channels = (json.loads(r.read()).get("channels") or [])
playable = [c for c in channels if c.get("playable") is not False and not c.get("supporter_only")]
if not playable:
    print("probe: no playable channel", file=sys.stderr)
    sys.exit(3)
ch = playable[0]
body = json.dumps({"deviceFingerprint": "stepdaddy-dulo-auth-script", "channelId": ch["id"]}).encode()
req = urllib.request.Request(
    "https://dulo.cx/api/live-tv/playback-session",
    data=body,
    method="POST",
    headers={
        "User-Agent": "Mozilla/5.0",
        "Origin": "https://dulo.cx",
        "Referer": "https://dulo.cx/live",
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    },
)
with urllib.request.urlopen(req, timeout=30) as r:
    data = json.loads(r.read())
ok = "/live-gateway/" in (data.get("playbackUrl") or "")
print(f"probe: channel={ch.get('name')} playback_ok={ok}")
sys.exit(0 if ok else 4)
PY
fi

if [[ "$SET_GATEWAY" -eq 1 ]]; then
  GATEWAY_URL="$GATEWAY_URL" ACCESS_TOKEN="$ACCESS_TOKEN" python3 <<'PY'
import json, os, sys, urllib.request
base = os.environ["GATEWAY_URL"].rstrip("/")
token = os.environ["ACCESS_TOKEN"]
body = json.dumps({
    "supplementDuloCxEnabled": True,
    "supplementDuloCxAccessToken": token,
}).encode()
req = urllib.request.Request(
    f"{base}/api/v1/settings",
    data=body,
    method="PATCH",
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(req, timeout=20) as r:
    data = json.loads(r.read())
    status = r.status
settings = data.get("settings") or {}
has_dulo = any(k.startswith("supplementDuloCx") for k in settings)
print(f"gateway: HTTP {status} ok={data.get('ok')} dulo_fields_in_snapshot={has_dulo}")
if not has_dulo:
    print(
        "gateway: warning — running build may predate dulo settings; deploy a build that includes supplementDuloCxAccessToken",
        file=sys.stderr,
    )
PY
fi

if [[ "$PRINT_TOKEN" -eq 1 ]]; then
  printf '%s\n' "$ACCESS_TOKEN"
fi

unset ACCESS_TOKEN REFRESH_TOKEN PASSWORD EMAIL

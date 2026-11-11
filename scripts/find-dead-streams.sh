#!/usr/bin/env bash
# Find dead/offline streams via the StepDaddy gateway offline APIs and write a
# structured report into the local stream-repair inbox for routine repair.
#
# Safe for cron: idempotent latest.json, lock file, non-zero only on hard failures.
#
# Usage:
#   ./scripts/find-dead-streams.sh
#   ./scripts/find-dead-streams.sh --recheck          # POST /channels/offline/recheck first
#   ./scripts/find-dead-streams.sh --gateway URL
#   ./scripts/find-dead-streams.sh --report-dir DIR
#   ./scripts/find-dead-streams.sh --ssh opc@host     # enrich from data/stream_offline.json
#
# Env defaults:
#   STEPDADDY_GATEWAY_URL=https://sdgateway.duckdns.org
#   STEPDADDY_SSH=opc@129.80.78.103
#   STEPDADDY_SSH_KEY=~/.ssh/oracle_openclaw_instance
#   STEPDADDY_REPAIR_INBOX=<repo>/stream-repair/inbox
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY_URL="${STEPDADDY_GATEWAY_URL:-https://sdgateway.duckdns.org}"
SSH_TARGET="${STEPDADDY_SSH:-opc@129.80.78.103}"
SSH_KEY="${STEPDADDY_SSH_KEY:-${HOME}/.ssh/oracle_openclaw_instance}"
REPORT_DIR="${STEPDADDY_REPAIR_INBOX:-${ROOT}/stream-repair/inbox}"
DO_RECHECK=0
USE_SSH=1
TIMEOUT_SEC="${STEPDADDY_HTTP_TIMEOUT:-45}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gateway) GATEWAY_URL="${2:?}"; shift 2 ;;
    --report-dir) REPORT_DIR="${2:?}"; shift 2 ;;
    --ssh) SSH_TARGET="${2:?}"; USE_SSH=1; shift 2 ;;
    --no-ssh) USE_SSH=0; shift ;;
    --recheck) DO_RECHECK=1; shift ;;
    -h|--help)
      sed -n '1,25p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

GATEWAY_URL="${GATEWAY_URL%/}"
mkdir -p "${REPORT_DIR}"
LOCK_FILE="${REPORT_DIR}/.find-dead-streams.lock"
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
  echo "[skip] another find-dead-streams run holds ${LOCK_FILE}"
  exit 0
fi

TS_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
TS_FILE="$(date -u +%Y%m%dT%H%M%SZ)"
HOST_SHORT="$(hostname -s 2>/dev/null || hostname || echo unknown)"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "${TMP_DIR}"; }
trap cleanup EXIT

curl_json() {
  local method="$1" path="$2" out="$3"
  local url="${GATEWAY_URL}${path}"
  if [[ "${method}" == "POST" ]]; then
    curl -fsS -X POST -m "${TIMEOUT_SEC}" -H 'Accept: application/json' "${url}" -o "${out}"
  else
    curl -fsS -m "${TIMEOUT_SEC}" -H 'Accept: application/json' "${url}" -o "${out}"
  fi
}

echo "==> Gateway: ${GATEWAY_URL}"
curl_json GET "/health?lite=1" "${TMP_DIR}/health.json"
echo "==> Offline status"
curl_json GET "/channels/offline" "${TMP_DIR}/offline.json"

if [[ "${DO_RECHECK}" -eq 1 ]]; then
  echo "==> Recheck (may take several minutes)"
  # Recheck can exceed default timeout; allow longer.
  curl -fsS -X POST -m "${STEPDADDY_RECHECK_TIMEOUT:-600}" -H 'Accept: application/json' \
    "${GATEWAY_URL}/channels/offline/recheck" -o "${TMP_DIR}/recheck.json" || {
      echo "[warn] recheck failed or timed out; continuing with current offline set" >&2
      echo '{"ok":false,"error":"recheck_failed_or_timeout"}' >"${TMP_DIR}/recheck.json"
    }
  curl_json GET "/channels/offline" "${TMP_DIR}/offline.json"
fi

OFFLINE_JSON_SRC="${STEPDADDY_OFFLINE_JSON:-}"
if [[ -n "${OFFLINE_JSON_SRC}" && -f "${OFFLINE_JSON_SRC}" ]]; then
  echo "==> Enriching from local ${OFFLINE_JSON_SRC}"
  cp -f "${OFFLINE_JSON_SRC}" "${TMP_DIR}/stream_offline.json"
elif [[ "${USE_SSH}" -eq 1 && -f "${SSH_KEY}" ]]; then
  echo "==> Enriching from ${SSH_TARGET}:stream_offline.json"
  if ! ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=12 "${SSH_TARGET}" \
    'python3 - <<"PY"
from pathlib import Path
p = Path("/home/opc/StepDaddyLiveHD/data/stream_offline.json")
print("{}" if not p.is_file() else p.read_text(encoding="utf-8"))
PY' >"${TMP_DIR}/stream_offline.json" 2>/dev/null; then
    echo "[warn] SSH enrich failed; report will use API sample only" >&2
    echo '{}' >"${TMP_DIR}/stream_offline.json"
  fi
fi

REPORT_PATH="${REPORT_DIR}/dead-streams-${TS_FILE}.json"
LATEST_PATH="${REPORT_DIR}/latest.json"
FINGERPRINT_PATH="${REPORT_DIR}/.last-fingerprint"

if [[ "${DO_RECHECK}" -eq 1 && -f "${TMP_DIR}/recheck.json" ]]; then
  :
else
  echo 'null' >"${TMP_DIR}/recheck.json"
fi
if [[ ! -f "${TMP_DIR}/stream_offline.json" ]]; then
  echo '{}' >"${TMP_DIR}/stream_offline.json"
fi

python3 - "${TMP_DIR}/health.json" "${TMP_DIR}/offline.json" "${TMP_DIR}/recheck.json" \
  "${TMP_DIR}/stream_offline.json" "${REPORT_PATH}" "${LATEST_PATH}" "${FINGERPRINT_PATH}" \
  "${TS_UTC}" "${GATEWAY_URL}" "${HOST_SHORT}" "${DO_RECHECK}" <<'PY'
import json, hashlib, pathlib, sys

(
    health_path,
    offline_path,
    recheck_path,
    details_path,
    report_path,
    latest_path,
    fp_path,
) = map(pathlib.Path, sys.argv[1:8])
ts_utc, gateway, host, do_recheck = sys.argv[8], sys.argv[9], sys.argv[10], sys.argv[11] == "1"

health = json.loads(health_path.read_text(encoding="utf-8"))
offline = json.loads(offline_path.read_text(encoding="utf-8"))
try:
    recheck = json.loads(recheck_path.read_text(encoding="utf-8"))
    if recheck is None:
        recheck = None
except Exception:
    recheck = {"ok": False, "error": "invalid_recheck_json"}
try:
    details = json.loads(details_path.read_text(encoding="utf-8"))
except Exception:
    details = {}

dead_ids = sorted({str(x) for x in (offline.get("dead_memory") or offline.get("offline_sample") or [])})
channels_detail = details.get("channels") if isinstance(details, dict) else None
if not isinstance(channels_detail, dict):
    channels_detail = {}

streams = []
for cid in dead_ids:
    row = channels_detail.get(cid) if isinstance(channels_detail.get(cid), dict) else {}
    streams.append({
        "channel_id": cid,
        "name": row.get("name") or row.get("title") or None,
        "offline": True if row.get("offline", True) else True,
        "last_error": row.get("last_error") or None,
        "fail_count": row.get("fail_count"),
        "last_fail_at": row.get("last_fail_at"),
        "last_check_at": row.get("last_check_at"),
        "last_ok_at": row.get("last_ok_at"),
        "error_code": (str(row.get("last_error") or "").split(":")[0] or None),
        "mirrors_tried": row.get("mirrors_tried") or row.get("mirrors") or None,
        "source": "stream_offline.json" if row else "channels/offline",
    })

fingerprint = hashlib.sha256(",".join(dead_ids).encode("utf-8")).hexdigest()
prev_fp = fp_path.read_text(encoding="utf-8").strip() if fp_path.is_file() else ""
changed = fingerprint != prev_fp

report = {
    "schema": "stepdaddy.stream-repair.dead-streams.v1",
    "generated_at": ts_utc,
    "generated_by": host,
    "gateway_url": gateway,
    "recheck_requested": do_recheck,
    "recheck": recheck,
    "health": {
        "ok": health.get("ok"),
        "channels": health.get("channels"),
        "dead_count": health.get("dead_count"),
        "upstream_base_url": health.get("upstream_base_url"),
        "relay_hosts": health.get("relay_hosts"),
        "bundle_version": health.get("bundle_version"),
        "uptime_seconds": health.get("uptime_seconds"),
    },
    "offline_api": {
        "enabled": offline.get("enabled"),
        "offline_count": offline.get("offline_count"),
        "tracked_count": offline.get("tracked_count"),
        "dead_memory_count": offline.get("dead_memory_count"),
        "fail_threshold": offline.get("fail_threshold"),
        "interval_sec": offline.get("interval_sec"),
        "hide_from_playlist": offline.get("hide_from_playlist"),
        "loop_running": offline.get("loop_running"),
    },
    "fingerprint": fingerprint,
    "changed_since_last_run": changed,
    "dead_count": len(streams),
    "streams": streams,
    "repair_hints": {
        "inbox_consumer": "Read latest.json or newest dead-streams-*.json; repair by channel_id; mark recovered via POST /channels/offline/{id} {\"action\":\"online\"} after fix.",
        "primary_mirrors": health.get("relay_hosts") or [
            "https://dlhd.st",
            "https://dlhd.pk",
            "https://thedaddy.pk",
        ],
    },
}

report_path.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
latest_path.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
fp_path.write_text(fingerprint + "\n", encoding="utf-8")

# Keep a small rolling window of stamped reports (newest 30).
inbox = report_path.parent
stamped = sorted(inbox.glob("dead-streams-*.json"), reverse=True)
for old in stamped[30:]:
    try:
        old.unlink()
    except OSError:
        pass

print(f"dead_count={len(streams)} changed={str(changed).lower()} report={report_path}")
if streams:
    for s in streams[:12]:
        err = (s.get("last_error") or "")[:80]
        print(f"  - {s['channel_id']}: {err or '(no error detail)'}")
    if len(streams) > 12:
        print(f"  ... +{len(streams) - 12} more")
else:
    print("  (no dead streams)")
PY

echo "==> Wrote ${REPORT_PATH}"
echo "==> Updated ${LATEST_PATH}"

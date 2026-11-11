#!/usr/bin/env bash
# Pull Oracle dead-stream outbox reports into the local stream-repair inbox.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_TARGET="${STEPDADDY_SSH:-opc@129.80.78.103}"
SSH_KEY="${STEPDADDY_SSH_KEY:-${HOME}/.ssh/oracle_openclaw_instance}"
REMOTE_DIR="${STEPDADDY_REMOTE_OUTBOX:-/home/opc/StepDaddyLiveHD/stream-repair/outbox}"
LOCAL_DIR="${STEPDADDY_REPAIR_INBOX:-${ROOT}/stream-repair/inbox}"
mkdir -p "${LOCAL_DIR}"
scp -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=15 \
  "${SSH_TARGET}:${REMOTE_DIR}/latest.json" \
  "${SSH_TARGET}:${REMOTE_DIR}/dead-streams-"*.json \
  "${LOCAL_DIR}/" 2>/dev/null || {
  # scp glob may fail if shell expands locally; fetch via remote tar
  ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=15 "${SSH_TARGET}" \
    "cd '${REMOTE_DIR}' && tar czf - latest.json dead-streams-*.json 2>/dev/null" \
    | tar xzf - -C "${LOCAL_DIR}"
}
echo "==> Synced into ${LOCAL_DIR}"
ls -1t "${LOCAL_DIR}"/dead-streams-*.json 2>/dev/null | head -5 || true
test -f "${LOCAL_DIR}/latest.json" && python3 -c "import json;d=json.load(open('${LOCAL_DIR}/latest.json'));print('latest dead_count=',d.get('dead_count'),'at',d.get('generated_at'))"

# Stream repair inbox

Routine inbox for **dead / offline streams** discovered by `scripts/find-dead-streams.sh`.

A later repair pass should read this directory, fix upstream mappings / mirrors, then clear recovered IDs via the gateway offline API.

## Layout

| Path | Purpose |
|------|---------|
| `inbox/latest.json` | Always the newest report (overwrite each run) |
| `inbox/dead-streams-YYYYMMDDTHHMMSSZ.json` | Timestamped snapshots (rolling, last 30 kept) |
| `inbox/.last-fingerprint` | Hash of dead channel id set (idempotency helper) |
| `inbox/.find-dead-streams.lock` | Cron lock (safe to ignore) |

Stamped reports and `latest.json` are **gitignored**. Only this README (and empty `inbox/.gitkeep`) is tracked.

## Report schema (`stepdaddy.stream-repair.dead-streams.v1`)

```json
{
  "schema": "stepdaddy.stream-repair.dead-streams.v1",
  "generated_at": "2026-11-11T05:50:00Z",
  "gateway_url": "https://sdgateway.duckdns.org",
  "dead_count": 2,
  "fingerprint": "…",
  "changed_since_last_run": true,
  "health": { "upstream_base_url": "…", "relay_hosts": ["https://dlhd.st"] },
  "offline_api": { "offline_count": 2, "dead_memory_count": 2 },
  "streams": [
    {
      "channel_id": "689",
      "name": null,
      "last_error": "Failed to find source URL for channel",
      "error_code": "Failed to find source URL for channel",
      "fail_count": 2,
      "mirrors_tried": null,
      "source": "stream_offline.json"
    }
  ]
}
```

## Producer

```bash
# Local (writes into this inbox)
./scripts/find-dead-streams.sh

# Optional: force gateway re-probe first (slow)
./scripts/find-dead-streams.sh --recheck

# Oracle cron installs the same script and can set:
#   STEPDADDY_REPAIR_INBOX=/home/opc/StepDaddyLiveHD/stream-repair/outbox
#   STEPDADDY_OFFLINE_JSON=/home/opc/StepDaddyLiveHD/data/stream_offline.json

# Pull Oracle outbox → local inbox
./scripts/pull-dead-stream-reports.sh
```

Oracle schedule (installed): `15 */6 * * *` → `~/StepDaddyLiveHD/scripts/run-find-dead-streams.sh`  
Local inbox path: `stream-repair/inbox/latest.json`

## Consumer (repair routine)

1. Load `stream-repair/inbox/latest.json` (or newest stamped file).
2. For each `streams[].channel_id`, attempt mirror / parser / catalog repair.
3. After a channel plays again:

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"action":"online"}' \
  "https://sdgateway.duckdns.org/channels/offline/<channel_id>"
```

4. Re-run `find-dead-streams.sh` and confirm `dead_count` dropped / fingerprint changed.

# StepDaddy Gateway 3.0.39

## Smart always-on merge-style backups

- **`/tivimate-smart`** always attaches match-scored DaddyLive backups (as if Merge fallbacks were ON), even when Settings stay on **Full catalog**.
- **`/tivimate`** / `/tivimate-stream` stay **direct-only** (no multi-variant masters).
- Catalog still publishes separate supplement rows under Full catalog; Smart gets failover mirrors on sync.

## Verify

```bash
# Settings can remain Full catalog
curl -s http://127.0.0.1:3000/health | head
curl -s http://127.0.0.1:3000/tivimate-smart.m3u | grep -c tivimate-smart-stream
# Pick an id that has backups:
curl -s http://127.0.0.1:3000/tivimate-smart-stream/<id>.m3u8 | head -20
# Fast path stays media playlist (no #EXT-X-STREAM-INF master):
curl -s http://127.0.0.1:3000/tivimate-stream/<id>.m3u8 | head -5
```

If TiviMate already has Smart loaded, use **Update playlist** so channel URLs refresh after upgrade.

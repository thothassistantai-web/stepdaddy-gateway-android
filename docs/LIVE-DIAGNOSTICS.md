# Live diagnostics & runtime tune

Live stream telemetry and allowlisted hot knobs so agents can diagnose and tighten performance **without rebuilding the APK** for every timeout/concurrency tweak. Real protocol/code fixes still ship via OTA.

## Endpoints (loopback or LAN `:3000`)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/debug/diagnostics` | Memory, effective tune, stream counters, recent resolve/segment events |
| `GET` | `/debug/config` | Effective runtime tune values |
| `POST`/`PUT` | `/debug/config` | Apply allowlisted patch JSON (local lab override) |
| `POST` | `/debug/config/reset` | Clear local/pack overrides → compiled defaults |
| `POST` | `/debug/counters/reset` | Reset stream counters |
| `GET` | `/debug/probe?id=333` | Cold resolve + first segment probe (records diagnostics) |

Example patch:

```bash
curl -s -X POST http://127.0.0.1:3000/debug/config \
  -H 'Content-Type: application/json' \
  -d '{"contentProxyMaxConcurrent":2,"dlhdRaceTimeoutMs":20000}'
```

## Silent shared pack

Devices pull [`release/runtime-tune.json`](../release/runtime-tune.json) on update-check / startup (same cadence as domain relay):

| Channel | URL |
|---------|-----|
| Raw `main` | `…/main/release/runtime-tune.json` |
| Releases asset | `…/latest/download/runtime-tune.json` |

Only allowlisted numeric/boolean knobs are applied (clamped). This is **not** remote code execution. APK/code changes stay on the normal OTA update protocol.

## Probe battery

```bash
DEV=192.168.1.167:46717 bash scripts/fusa-live-diagnostics.sh
```

Probes Movies (+ optional Local) channels via `/debug/probe`, prints latency/504 summary from `/debug/diagnostics`.

## Precedence

1. Local `/debug/config` patch (lab)
2. Last-good pulled `runtime-tune.json` pack
3. Compiled `GatewayConfig` defaults

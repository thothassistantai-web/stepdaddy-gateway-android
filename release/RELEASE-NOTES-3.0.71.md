# StepDaddy Gateway 3.0.71

## Highlights

- **FUSA/ONN timeout → stale-good** — `TimeoutCancellationException` is a `CancellationException`. Mirror/`withTimeout` arms that caught `CancellationException` first aborted the mirror walk and returned **504** instead of last-good playlists, causing TiviMate spinners and stuck-frame storms (~3.5s / ~10s resolve failures on ch 975).
- **Route defense** — `/tivimate-stream` timeout path now serves stale-good (200 + `X-StepDaddy-Cache`) when a last-good playlist exists before emitting 504.
- **Hub page timeout 3.5s** — was 2s; live dlstreams/dlhd stream HTML under ONN jitter no longer fails as `Timed out waiting for 2000 ms` before trying the next mirror.
- **Domain relay v3** — primary `https://dlstreams.st` (li/at often unreachable from ONN/US).
- **Softer mid-play window** — `LIVE_SOFT_SERVE_MS` 14s (covers one mirror attempt); healthy stale TTL 45s so brief CDN blips do not empty the fallback cache.
- **Runtime-tune v15** — hedged mirror race + balanced proxy waits; proxy concurrency stays at 3 for ONN LMK.

## Verify

1. Play a live channel on FUSA ONN ≥3 minutes via TiviMate (gateway `/debug/*` only).
2. During upstream blips, diagnostics should show `detail=stale` / 200 rather than clusters of `upstream_timeout` 504.
3. No multi-minute spinner or frozen macroblock frame while segments keep advancing after recovery.

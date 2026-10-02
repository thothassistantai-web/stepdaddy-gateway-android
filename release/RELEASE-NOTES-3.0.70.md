# StepDaddy Gateway 3.0.70

## Highlights

- **ONN / FUSA cold-boot survival** — Onn Full HD sticks now share Fire Stick memory-lite catalog guards (skip heavy logo/iptv-org CSV indexes; defer logo enrich) so cold boot + TiviMate does not balloon RSS into LMK (`device is not responding`). Boot also skips DaddyLive/supplement network refresh when a disk catalog already exists.
- **Content-proxy concurrency cap** — memory-lite devices hard-cap `/content` + `/vod-content` in-flight segments at **3** even if an older runtime-tune pack requests 8 (each segment buffers 1–2 MB + unwrap copy).
- **Runtime-tune v13** — `contentProxyMaxConcurrent: 3` (was 8); keep fail-fast wait 4s and short playlist TTLs.
- **Health on sticks** — `/health` defaults to the lite payload on memory-lite devices (`?full=1` for the expensive scan) so GC thrash cannot wedge the CIO loop.

## Verify

1. Cold reboot FUSA/ONN stick.
2. Open TiviMate within ~2 minutes; first channel should play without gateway babysitting.
3. `GET /health` (no query) returns quickly; `GET /debug/diagnostics` shows `contentProxyMaxConcurrent` ≤ 3 and heap not stuck at 0 free under steady play.

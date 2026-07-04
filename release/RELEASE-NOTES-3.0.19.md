# StepDaddy Gateway 3.0.19

## Xtream live playback (Fire Stick / TiviMate)

### Fixed

- **Xtream `/live/{user}/{pass}/{id}.ts` routing** — Ktor 2.x did not capture `{streamId}.{ext}` in a single path segment, so every live tune redirected to `/tivimate-stream/.m3u8` (empty id). Catalog loaded; video never started. Routes now use `{streamFile}` and strip the extension in code.
- **Empty channel id** — `/tivimate-stream/.m3u8` fails fast with 404 instead of hanging on upstream resolve.
- **Playlist cache** — no longer serves a different flavor (TiviMate pipe vs plain `/stream/`) as a "stale" body while rebuilding.

Sideload `stepdaddy-gateway-3.0.19-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. `curl -I http://127.0.0.1:3000/live/admin/password/44.m3u8` → `Location: /tivimate-stream/44.m3u8`
2. Follow redirect; media playlist and TS segments return 200 with MPEG-TS payload
3. Play ESPN USA (or any live channel) in TiviMate via Xtream login — video frames decode
4. `curl http://127.0.0.1:3000/health` — version `3.0.19`

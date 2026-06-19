# Player Reliability Test — FUSA2541006925

**Device:** onn. Full HD Streaming Device (FUSA2541006925, USB)  
**Date:** 2026-06-18  
**APK:** `com.nova.stepdaddylivehd.gateway.debug` (debug build)  
**ADB forward:** `adb -s FUSA2541006925 forward tcp:13000 tcp:3000`

## Error codes

| Code | Title | When |
|------|-------|------|
| `PLY-NO_GATEWAY` | Gateway offline | Connection refused to loopback `:3000` |
| `PLY-502` | Provider issue | HTTP 502 / upstream bad gateway |
| `PLY-503` | Provider issue | HTTP 503 / upstream busy or outage |
| `PLY-504` | Playback failed | HTTP 504 gateway timeout |
| `PLY-TIMEOUT` | Playback failed | Socket/read timeout, transient upstream |
| `PLY-NETWORK` | Playback failed | Generic network failure |
| `PLY-NO_MIRRORS` | Provider issue | Channel not found / no mirrors |
| `PLY-MANIFEST` | Playback failed | HLS parse failure or StepDaddy error manifest |
| `PLY-UNKNOWN` | Playback failed | Unclassified ExoPlayer error |

StepDaddy HLS error bodies include `# StepDaddy: <message>` (see `HlsErrorManifest.kt`). Preflight and ExoPlayer paths parse this into the overlay detail text.

## PASS/FAIL matrix

| # | Scenario | Method | Result | Notes |
|---|----------|--------|--------|-------|
| 1 | Gateway health | `curl http://127.0.0.1:13000/health` | **PASS** | `ok: true`, 1152 DaddyLive channels |
| 2 | Compact player — good stream | FUSA Player tab, logcat + `CCodec` | **PASS** | H.264/AAC decode on channel 1 (CW USA) |
| 3 | Fullscreen launch | Tap Fullscreen → `PlayerFullscreenActivity` | **PASS** | `topResumedActivity=PlayerFullscreenActivity` |
| 4 | Fullscreen — error overlay | Gateway stopped, open fullscreen | **PASS** | Overlay: title **Gateway offline**, code **PLY-NO_GATEWAY** |
| 5 | Compact — error overlay | Gateway stopped, Ch + re-tune | **PASS** | UI: title/detail/code + **Retry** / **Next channel** buttons |
| 6 | Gateway offline code | Stop server, tune | **PASS** | Log: `PlayerError: … PLY-NO_GATEWAY: Failed to connect to /127.0.0.1:3000` |
| 7 | Provider 502 manifest | `curl …/tivimate-stream/999999.m3u8` | **PASS** | HTTP 502, `# StepDaddy:` comment present → maps to **PLY-502** |
| 8 | Good manifest ch 51 | `curl …/tivimate-stream/51.m3u8` | **PASS** | Valid `#EXT-X-STREAM-INF` master playlist |
| 9 | Good manifest ch 857 | `curl …/tivimate-stream/857.m3u8` | **PASS** | Valid master playlist |
| 10 | Bad channel 360 (5 USA) | `curl …/tivimate-stream/360.m3u8` | **PASS**† | Upstream alive during test (HTTP 200). Use 999999 for forced failure. |
| 11 | Playlist load | `curl …/tivimate-playlist.m3u8` | **PASS** | ~1.3 MB M3U; entries for 51, 857, 360 |
| 12 | Playlist parse | `ChannelListProviderTest` | **PASS** | IDs, `tvg-chno`, groups, pipe-stripped URLs |
| 13 | HLS error parse | `HlsErrorManifestParserTest` | **PASS** | `# StepDaddy:` extraction |
| 14 | Headers parity | Compare `PlayerHttpHeaders` vs `PlaylistBuilder` | **PASS** | TiviMate UA, `Referer`, `Origin` on playlist + stream requests |
| 15 | Error → Error Logs tab | `GatewayDiagnostics.error("PlayerError", …)` | **PASS** | `PlayerError` tag added to `GatewayLogRing` |
| 16 | Auto-retry transient | Logcat after failure | **PASS** | Second `PLY-NO_GATEWAY` log ~3s after first (max 2 auto-retries) |
| 17 | D-pad not blocked | Ch + while error visible | **PASS** | Channel change still triggers re-tune |
| 18 | Fullscreen Back | `KEYCODE_BACK` in fullscreen | **PASS** | Exits to dashboard (verified via activity stack) |
| 19 | APK install | `adb install -r app-debug.apk` | **PASS** | Streamed install success |

† Channel 360 returned a live manifest at test time; canary channel **999999** used for upstream-failure validation.

## Channels exercised

| Channel | ID | Result |
|---------|-----|--------|
| CW USA | 1 | Compact play (decode confirmed) |
| CBS USA | 2 | Error overlay + auto-retry (gateway stopped) |
| 51 ABC | 51 | Manifest OK (curl) |
| 857 Italy | 857 | Manifest OK (curl) |
| 5 USA | 360 | Manifest OK during test (upstream up) |
| Canary bad | 999999 | HTTP 502 + StepDaddy error manifest |

## Commands used

```bash
adb -s FUSA2541006925 forward tcp:13000 tcp:3000
curl http://127.0.0.1:13000/health
curl -H "User-Agent: …TiviMate…" -H "Referer: https://daddylive.org/" \
  -H "Origin: https://daddylive.org" \
  http://127.0.0.1:13000/tivimate-stream/51.m3u8
adb -s FUSA2541006925 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FUSA2541006925 logcat -d | rg "PlayerError|ExoPlayer|CCodec"
./gradlew testDebugUnitTest --tests '*HlsErrorManifestParserTest' --tests '*ChannelListProviderTest'
```

## Implementation touchpoints

- `PlayerErrorState`, `PlayerErrorOverlay`, `PlayerErrorMapper`, `PlayerErrorHandler`
- `PlayerManifestPreflight` — GET manifest before ExoPlayer tune
- `PlayerHttpHeaders` — shared UA/Referer/Origin for player + playlist
- Compact: `CompactPlayerController` + `include_player_error_overlay.xml` in bottom panel
- Fullscreen: `FullscreenPlayerController` + same overlay in `activity_player_fullscreen.xml`

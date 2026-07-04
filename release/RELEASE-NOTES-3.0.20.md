# StepDaddy Gateway 3.0.20

## Low-RAM Android TV sticks (Onn + Fire Stick)

### Fixed

- **Onn / non-Fire stick LMK survival** — Memory-lite mode (skip heavy logo/iptv-org indexes, defer EPG/playlist prewarm, compact OkHttp pools, wake lock + priority overlay, boot keep-alive alarms) was gated on `FireTvDevice.isFireTv()` (Amazon/AFT* only). Onn Full HD sticks (~1.4 GiB RAM) loaded the full catalog and were killed by LMK. Detection is now `LowRamTvDevice.needsMemoryLite()`: Fire TV, Onn/Walmart sticks, or any leanback TV under 1.5 GiB / `isLowRamDevice`.
- **Fire Stick unchanged** — Fire-OS-only quirks (boot delay, Amazon network wait, notification channel importance) remain behind `FireTvDevice`. Shared memory reductions still apply to Fire via the low-RAM path.
- **Phones safe** — Memory lite requires leanback/TV; low-RAM phones are not affected.

### Unchanged from 3.0.19

- Xtream `/live/{user}/{pass}/{id}.ts` → `/tivimate-stream/{id}.m3u8` routing fix (all devices).

Sideload `stepdaddy-gateway-3.0.20-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. Onn + Fire Stick: `adb install -r` release APK; reboot; wait for `/health` with `version` `3.0.20`, `ok`, channels loaded, `starting=false`
2. Same PID for 5+ minutes (no LMK death)
3. `curl -I http://127.0.0.1:3000/live/admin/password/44.m3u8` → `Location: /tivimate-stream/44.m3u8`
4. Follow redirect; media playlist and TS segments return 200 with MPEG-TS payload

# StepDaddy Gateway 3.0.18

## Fire Stick boot + LMK survival

### Fixed / improved (Fire TV / Fire Stick only)

- **Boot reliability** — delayed start for memory settle, network wait before gateway start, and longer wake locks so the foreground service survives early boot LMK.
- **Boot fallback alarms** — `scheduleFireBootFallbacks` arms keep-alive alarms before delays; re-arms when an alarm fires but the gateway is not yet healthy.
- **LMK survival** — `FireMemoryGuard` raises process importance (wake lock + priority overlay), trims caches under memory pressure, and uses compact OkHttp clients.
- **Fire-lite catalog path** — defers heavy supplement/EPG/logo indexes and disk loads on Fire Stick to lower peak RAM; non-Fire devices keep full default behavior.
- **largeHeap** — enabled for the gateway process to reduce OOM kills on low-RAM sticks.

Sideload `stepdaddy-gateway-3.0.18-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. Install release APK on Fire Stick; reboot and confirm gateway starts within boot fallback window
2. `curl http://127.0.0.1:3000/health` — version `3.0.18`
3. Play a DaddyLive channel in TiviMate after cold boot
4. Confirm non-Fire phone/tablet still boots and serves playlist without Fire-lite deferrals

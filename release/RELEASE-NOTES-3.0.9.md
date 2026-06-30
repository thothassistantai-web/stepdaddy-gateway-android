# StepDaddy Gateway 3.0.9

## Mirror health observability fix

- `/health` `mirrorStats` is no longer null when Special Events mirrors exist
- `specialEventMirrorsHealthy` counts per-mirror probe results and active-mirror fallback
- Per-event mirror breakdown in `mirrorStats.specialEventMirrorDetails`

Sideload `stepdaddy-gateway-3.0.9-release.apk` (`com.thothassistant.stepdaddy.gateway`).

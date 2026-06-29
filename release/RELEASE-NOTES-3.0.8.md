# StepDaddy Gateway 3.0.8

## Special Events mirror consolidation

- One playlist row per event at `tivimate-stream/dlhd-event-{key}.m3u8`
- All upstream backup links stored as internal mirrors (multi-variant HLS failover)
- Event budget: 120 unique events, not 120 URLs
- `/health` exposes mirror totals and active mirror index

Sideload `stepdaddy-gateway-3.0.8-release.apk` (`com.thothassistant.stepdaddy.gateway`).

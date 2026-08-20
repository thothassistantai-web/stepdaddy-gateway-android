# StepDaddy Gateway 3.0.27

versionCode: 30027

## Fixed
- **Daddy Live 404 / stream resolution** — Channel API now uses `{channel_name,url}` embed URLs instead of legacy `{channel_id,channel_name}`. Relay paths prefer `dlstreams.st`; seized/broken mirrors are blocklisted so TiviMate no longer lands on dead 404 hosts.

## Upgrade
Sideload `stepdaddy-gateway-3.0.27-release.apk` (`com.thothassistant.stepdaddy.gateway`) or `stepdaddy-gateway-3.0.27-debug.apk` (`com.thothassistant.stepdaddy.gateway.debug`). In-app updates poll GitHub `releases/latest` and `update-manifest.json`.

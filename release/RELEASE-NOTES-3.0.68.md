# StepDaddy Gateway 3.0.68

versionCode: 30068

## Fixed

- **HLS image-segment unwrap** — DaddyLive CDN PNG/WebP-wrapped TS segments are unwrapped in `/content/` so ExoPlayer/TiviMate stop treating media as images (reduces looping / HttpDataSource stalls)
- **Stream resolve healing** — Resportz/DaddyLive path: smarter mirror host cooling, dead-channel vs dead-relay classification, player fallback URLs, and tighter race/timeout handling under load

## Added

- **Runtime tune pack** — allowlisted hot knobs via `release/runtime-tune.json` (raw `main` + GitHub Releases asset) without rebuilding for every concurrency/timeout tweak
- **Live diagnostics** — `/debug/diagnostics`, `/debug/config`, `/debug/probe`, and `scripts/fusa-live-diagnostics.sh` for FUSA playback consistency labs
- **Stremio Live TV addon** — embedded `/stremio/` manifest/catalog/stream routes for local Stremio installs

## Notes

Includes stream fixes from 3.0.52–3.0.56 (econfig/live hubs, sticky 502 TTL, mid-play soft-serve) plus this release's unwrap/tune/Stremio work.

OTA requires this tag's `update-manifest.json` (`versionCode: 30068`) plus versioned debug/release APKs. Devices also pull `runtime-tune.json` on update-check/startup. After upgrade, retune on FUSA and refresh the TiviMate playlist if playback was previously looping.

# StepDaddy Gateway 3.0.53

versionCode: 30053

## Fixed

- **DaddyLive assetrage chain** — embed depth 8; prioritize `assetrage` / known player hosts before nontongo so nontongo → dlive → assetrage `_econfig` resolves (fixes HTTP 504 stub / factory TiviMate no-video)

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30053`) plus versioned debug/release APKs. Gateway-only upgrade — do not replace factory TiviMate (`ar.tvplayer.tv`).

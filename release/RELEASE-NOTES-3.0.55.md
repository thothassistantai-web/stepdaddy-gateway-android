# StepDaddy Gateway 3.0.55

versionCode: 30055

## Fixed

- **Sticky mid-play 502s** — live playlist cache was 60s/120s; gateway kept serving frozen `MEDIA-SEQUENCE` while CDN segments 404'd through `/vod-content`. Fresh TTLs ≈2.5s; healthy stale-good ≤15s.
- **Soft re-resolve** — content/vod-content 404/403/5xx purge fresh playlist caches and return 503 + `Retry-After` (not sticky 502). `invalidateFreshStreamCaches()` now clears caches for real.
- **Winning embed** — channel invalidate also drops cached tiestep/assetrage embed URLs.

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30055`) plus versioned debug/release APKs. Gateway-only upgrade — do not replace factory TiviMate (`ar.tvplayer.tv`).

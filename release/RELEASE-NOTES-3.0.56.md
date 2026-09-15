# StepDaddy Gateway 3.0.56

versionCode: 30056

## Fixed

- **~60s mid-play hitch** — after 3.0.55’s short playlist TTL, misses re-walked hubs (tiestep 429 → 3–8s stalls). Cheap CDN masterUrl refresh + soft-serve stale-while-revalidate; keep winning-embed on 429.
- Master bind TTL ~30m so mid-play polls do not re-hit embed HTML.

## Notes

OTA: `update-manifest.json` versionCode 30056 + versioned APKs. Gateway-only — do not replace factory TiviMate.

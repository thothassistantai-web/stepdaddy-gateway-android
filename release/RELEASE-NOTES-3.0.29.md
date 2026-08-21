# StepDaddy Gateway 3.0.29

**Live TV cleanup + dulo.cx tier** — versionCode: 30029

## Highlights

- **Dead source removals** — Strip residual TheTvApp / xyzstreams / MOJ paths so supplement sync and health no longer carry dead streams.
- **Free-TV live backups** — Consolidate Free-TV mirrors onto DaddyLive name matches for more resilient playback.
- **dulo.cx Live TV tier** — New supplement source with catalog sync, `/dulo-stream/` playback, and `supplementDuloCxAccessToken` (see `docs/DULO-AUTH.md` + `scripts/dulo-cx-auth.sh`).
- **AFTV Downloader codes (unchanged):** Release `4860686` · Debug `1401588` — always pull the latest versionless APKs.

## Upgrade notes

- Debug OTA: in-app update should offer **3.0.29** once this release is published (`update-manifest.json`).
- Release package on device: sideload `stepdaddy-gateway-3.0.29-release.apk` or use AFTV code `4860686`.
- After install, set the dulo JWT via `scripts/dulo-cx-auth.sh --set-gateway …` (never commit tokens).

## AFTV Downloader (Fire TV)

Permanent codes (stable URLs on `/releases/latest/download/`):

- Release: `4860686` → `stepdaddy-gateway-release.apk`
- Debug: `1401588` → `stepdaddy-gateway-debug.apk`

See `release/AFTV-CODES.md`.

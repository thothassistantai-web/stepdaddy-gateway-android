# StepDaddy Gateway 3.0.54

versionCode: 30054

## Fixed

- **DaddyLive cold resolve** — prefer `freetvspor` / `tiestep` / `assetrage` ahead of slow stubs (`rippleplays`, `cricsfree`, `apexstreams`, `worldsportz`); fail-fast `hamis` 403s
- **Timeouts** — ~2s per hub HTML page; reserve ≥3.5s for m3u8 after `_econfig` so hub burn no longer yields 504
- **Hedged mirrors** — block DNS-dead `daddylive.eu` (NXDOMAIN); do not race / double-fetch against it
- **Winning embed cache** — multi-hour channelId → embed URL cache; skip nontongo when budget is low

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30054`) plus versioned debug/release APKs. Gateway-only upgrade — do not replace factory TiviMate (`ar.tvplayer.tv`).

# StepDaddy Gateway 3.0.69

versionCode: 30069

## Fixed

- **Content-proxy segment timeout** — hung `/content/` segment fetches release proxy slots after 10s instead of holding the concurrency cap until OkHttp (~25s), preventing `content_proxy_busy` / TiviMate 503 stuck-frame storms
- **Default proxy wait** — `contentProxyWaitMs` default lowered to 4s (matches runtime-tune pack v12) so fail-fast under load does not wait 20s for a free slot

## Notes

Ship with runtime-tune **v12** already published on `main` + Releases. OTA requires this tag's `update-manifest.json` (`versionCode: 30069`) plus versioned debug/release APKs. After upgrade, retune once on FUSA if a channel was previously stuck on 503.

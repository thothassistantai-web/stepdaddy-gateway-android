# StepDaddy Gateway 3.0.52

versionCode: 30052

## Fixed

- **DaddyLive streams** — follow `/live/stream={id}` pages, player hubs (`data-tv-daddy-urls`, nontongo), and decode `window._econfig` for m3u8 URLs
- **Domain relay** — `daddylive.li` primary; relay/embed host order updated (`domain-relay.json` v2)

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30052`) plus versioned debug/release APKs. Refresh playlist after upgrade so domain relay + stream resolver take effect.

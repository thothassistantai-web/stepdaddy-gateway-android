# StepDaddy Gateway 3.0.51

versionCode: 30051

## Fixed

- **Empty tvg-id pass** — 216+ DaddyLive `channel_epg_map.json` entries (ACCNX→ACC Network, BTN+/SECN+/Racer TV proxies, international sports/locale ids)
- **ACCNX EPG** — name overrides for ACCNX / ACCNX HD / ACCNX USA → `ACCNetwork.us`
- **Supplement tvg-id pass** — ~87 new `epg_name_overrides.json` entries for FAST / NTV / WOFTV rows (Arena Sport HR, Astro Arena, FAST catalog backfill); ambiguous fuzzy matches skipped

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30051`) plus versioned debug/release APKs. Refresh playlist + EPG on device after upgrade for guide lift.

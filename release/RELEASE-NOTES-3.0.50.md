# StepDaddy Gateway 3.0.50

versionCode: 30050

## Fixed

- **Pluto / hash FAST EPG** — DaddyLive Pluto and other hash-style FAST `tvg-id`s merge into mjh Fast XMLTV; WOFTV gap-fill retries those ids after thin PLEX1; residual scoring ignores name-only WOFTV hits

## Notes

OTA requires this tag's `update-manifest.json` (`versionCode: 30050`) plus versioned debug/release APKs. Phones on 3.0.49 / 30049 will see an upgrade. EPG must refresh on device for Pluto guide fixes to appear.

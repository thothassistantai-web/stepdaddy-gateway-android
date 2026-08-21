# StepDaddy Gateway 3.0.35

versionCode: 30035

## Fixes

- **Endless TiviMate spinner on channel/logo load**
  - EPG: playlist uses `/epg.xml.gz` (~3MB vs ~26MB) so guide download/parse no longer stalls the UI.
  - Logos: Metahub/external VOD posters go through `/logo/` with short timeouts + SVG fallback.
  - Logo cache: unique SHA-256 keys (no more collisions on shared `/img` paths).

## Action

In TiviMate: **Update playlist** once after installing 3.0.35 (header `stepdaddy-rev="3.0.35"`).

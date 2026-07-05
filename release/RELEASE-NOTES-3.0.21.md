# StepDaddy Gateway 3.0.21

## Fixed

- **TiviMate VOD 403** — Movie/series HLS manifests now rewrite segments through `/vod-content/` proxy with embed referer + browser User-Agent (same pattern as live `/content/` proxy). Fixes internal TiviMate playback while external MX Player still works.

## Added

- **VOD sort by year** — Movies and series in `get_vod_streams` / `get_series` sort newest release year first.
- **Nextbox provider** — Scrapes [nextbox.uno](https://nextbox.uno/) homepage + featured shelves (Popular Movies, Horror Movies, Trending TV Series, etc.) into the VOD catalog alongside vsembed/TMDB sources.

## Verify

1. TiviMate: play a movie from VOD — no 403; manifest contains `/vod-content/` URLs.
2. `curl 'http://127.0.0.1:3000/player_api.php?username=admin&password=password&action=get_vod_streams' | jq '.[0:3] | .[].name'` — newest years first.
3. VOD categories include Nextbox shelves (e.g. `🎬 Popular Movies`, `📺 Trending TV Series`).

Sideload `stepdaddy-gateway-3.0.21-release.apk` (`com.thothassistant.stepdaddy.gateway`).

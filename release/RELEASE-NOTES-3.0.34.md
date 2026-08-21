# StepDaddy Gateway 3.0.34

versionCode: 30034

## Fixes

- **VOD shelf movies** — playlist URLs no longer include `@popular_movies` / `@trending_movies` (etc.) in `/vod/movie/…` paths. Those paths returned `invalid_tmdb_id` and triggered TiviMate ParserException / playback errors after 3.0.33.
- **Fallback HLS proxy** — consolidated daddy/supplement mirrors proxy CDN segments with Referer instead of handing ExoPlayer raw CDN URLs.
- **Playlist rev** — `stepdaddy-rev="3.0.34"` + `X-Playlist-Rev` header so a TiviMate playlist update pulls the fixed M3U.

## Action required

In TiviMate: **Settings → Playlists → Update** (or remove/re-add `http://127.0.0.1:3000/tivimate.m3u`) once after installing 3.0.34.

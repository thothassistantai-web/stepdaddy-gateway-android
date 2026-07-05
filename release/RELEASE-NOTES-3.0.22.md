# StepDaddy Gateway 3.0.22

## VOD movie duplicate management

- **Automatic movie dedup** at catalog build — collapses duplicate titles from vsembed, Nextbox, TMDB, and Cinemeta before Xtream `get_vod_streams`.
- **Identity rules** — same TMDB id, same IMDB id, or normalized title + year (case/punctuation insensitive).
- **Keeper selection** — prefers vsembed/playable rows (IMDB id, stream quality) and richer metadata; merges shelf/genre tags from dropped rows.
- **Series unchanged** — dedup applies to movies only; series/episodes logic is untouched.
- **Logging** — `VOD movie dedup: removed N duplicate movies (before -> after)` in logcat during supplement sync.

## Upgrade

Sideload `stepdaddy-gateway-3.0.22-release.apk` (`com.thothassistant.stepdaddy.gateway`).

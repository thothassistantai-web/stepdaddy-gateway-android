# StepDaddy Gateway 3.0.12

## IMDB / TMDB Movies (VOD)

- Trending & popular movies appear in the **🎬 Movies** group on all user playlists
- Playback via gateway proxy: `/vod/movie/{tmdbId}.m3u8`
- TiviMate uses `.mp4` URL suffix for VOD bucket classification (same handler)
- Toggle in **Settings → IMDB / TMDB Movies (VOD)**
- Optional: set `TMDB_API_KEY` in `local.properties` for richer TMDB metadata

## Test plan

1. Enable movies supplement in Settings (default on)
2. Wait for supplement sync / restart gateway
3. `curl http://127.0.0.1:3000/tivimate.m3u | grep "🎬 Movies"`
4. `curl http://127.0.0.1:3000/movies`
5. Play a movie row in TiviMate or StreamVault

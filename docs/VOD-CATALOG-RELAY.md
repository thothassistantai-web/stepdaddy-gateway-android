# VOD catalog relay

Live overlay for **movies and shows**: maintainers can publish newly found titles and stream candidates without an APK release. Same trust model as [DOMAIN-RELAY.md](DOMAIN-RELAY.md) — data-only JSON from this GitHub repo.

## File location

| Channel | URL |
|---------|-----|
| Repo (raw `main`) | `https://raw.githubusercontent.com/thothassistantai-web/stepdaddy-gateway-android/main/release/vod-catalog-relay.json` |
| GitHub Releases asset | `https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/vod-catalog-relay.json` |

Pinned via `BuildConfig.DEFAULT_VOD_CATALOG_RELAY_URL` / `DEFAULT_VOD_CATALOG_RELAY_RELEASE_URL`.

Checked in: [`release/vod-catalog-relay.json`](../release/vod-catalog-relay.json).

## Schema

```json
{
  "version": 1,
  "minAppVersion": "3.0.0",
  "message": "Live VOD overlay — newly found movies and shows.",
  "movies": [
    {
      "tmdbId": 550,
      "title": "Fight Club",
      "year": "1999",
      "imdbId": "tt0137523",
      "overview": "",
      "posterUrl": null,
      "streams": [
        { "url": "https://cdn.example/play.m3u8", "quality": "1080p", "label": "primary", "referer": null }
      ]
    }
  ],
  "shows": [
    {
      "tmdbId": 1396,
      "title": "Breaking Bad",
      "year": "2008",
      "season": 1,
      "episode": 1,
      "episodeTitle": "Pilot",
      "imdbId": "tt0903747",
      "streams": [
        { "url": "https://cdn.example/s01e01.m3u8", "quality": "720p", "label": "primary" }
      ]
    }
  ]
}
```

| Field | Notes |
|-------|-------|
| `version` | Monotonic; older than cache ignored |
| `minAppVersion` | Ignore file if installed app is older |
| `movies[].tmdbId` | Preferred identity; required for catalog IDs (`vod:tmdb:{id}`) |
| `shows[].tmdbId` + season/episode | Identity for `vod:series:{id}:{s}:{e}` |
| `streams` | Candidate playable URLs; probed on refresh |

Validation: ≤ ~64 KB; http(s) URLs only; hostnames validated.

## Behavior

1. **Fetch** on boot (with domain-relay / update check) and on each VOD supplement sync when enabled.
2. **Cache** last-good file on disk.
3. **Probe** stream candidates (lightweight GET, concurrency capped for Fire Stick). Dead links demoted after failures; cooldown before retry.
4. **Dedup** — merge overlay movies into the TMDB/vsembed catalog then run existing `VodMovieDedup` (tmdbId → imdbId → title+year). Overlay streams preferred when working.
5. **Resolve** — `VodMovieResolver` tries working relay streams first, then vsembed / Moviebox.
6. Titles stay in the catalog if at least one path can still resolve (relay mirror or vsembed).

## Settings

**VOD catalog relay** toggle (default on). Requires TMDB/VOD movies enabled.

## Health

`vodCatalogRelayActive`, `vodCatalogRelayVersion`, `vodCatalogRelayMovies`, `vodCatalogRelayShows`, `vodCatalogRelayProbed`, `vodCatalogRelayProbeOk`, `vodCatalogRelayDeadPruned`.

## Maintainer playbook

1. Confirm stream URL plays (browser / `curl`).
2. Edit `release/vod-catalog-relay.json` — bump `version`, add movie/show + streams.
3. Commit + push `main` (raw URL updates immediately).
4. Optionally publish as a release asset with the next gateway release (or `--assets-only`).
5. Devices pick up on next VOD sync / dashboard open.

# EPG gap-fill feed probe — 2026-08-22

## ROOT CAUSE

Cloudflare cached **HTTP 404** on bare `epgshare01.online` feed URLs. Directory listing shows files updated daily; direct GET/HEAD without a cache-bust query returns a cached 404 HTML page (`cf-cache-status: HIT`, `age: ~4668s`).

**URLs have not moved** — all 17 configured feeds (3 primary + 14 gap-fill) return **200** with `?cb=<timestamp>` and `Cache-Control: no-cache`.

## Feed probe results (host + device)

| Feed | Bare URL | Cache-bust URL |
|------|----------|----------------|
| US2, US_SPORTS1, US_LOCALS1 | 404 | 200 |
| PLEX1, DISTROTV1, BEIN1 | 404 | 200 |
| UK1, DE1, FR1, IT1, ES1, CA2 | 404 | 200 |
| AU1, TR1, AE1, BR1, NZ1 | 404 | 200 |

`epgshare02.online` — DNS/connect failure (not in use).

## Code changes

1. **`EpgConfig.feedDownloadUrl()`** — append `?cb=<ms>` on every download.
2. **`LightEpgBuilder.ensureFeedCached()`** — use cache-bust URL, send `Cache-Control: no-cache`, validate gzip magic before committing cache (404 HTML no longer poisons disk).
3. **`EpgStore.trimFeedCache()`** — pin primary feed caches; trim oldest regional/gap-fill files first (old logic kept only the single newest file).

## Fallback path

When epgshare is fully unreachable: WOFTV catalog (`WhatsOnFreeTvEpgCatalog`) + tvtv.us bridge + supplement sidecar still merge. Gateway publishes with partial coverage rather than blocking on regional feeds.

## Device metrics (R5CRC1KHKFW, post-fix install + force refresh)

| Metric | Before (reported) | After |
|--------|-------------------|-------|
| epgProgrammeCount | — | 23,375 |
| withRealProgrammes | 459 | **604** |
| woftvProgrammesMerged | 2,742 | 1,982 |
| withProgrammes | — | 1,823 |
| epgReady | — | true |

Gap-fill feeds downloaded to device cache after fix. `withRealProgrammes` improved +145 vs pre-fix snapshot; still below 1,208 baseline — likely mapping/coverage, not feed availability (delegate to epg-mapping-auditor).

# dulo.tv SimilarWeb competitors — gateway fit (2026-08)

Source page: [similarweb.com/website/dulo.tv/competitors/](https://www.similarweb.com/website/dulo.tv/competitors/)

**Fetch note:** The SimilarWeb competitors UI is paywalled / JS-gated (direct HTTP empty/403; archive/jina/Google cache do not expose the competitor table). Competitor names below are taken from SimilarWeb-category / audience-affinity research that lists **dulo.tv** peers, then each domain was HTTP-probed for public APIs / M3U / live catalogs.

Goal: find **durable HTTP APIs or playlists** (Free-TV / iptv-org / ntv / dulo.cx style). Prefer Skip over fragile HTML/embed scrapers or DRM/auth nightmares.

## Already in StepDaddy (do not duplicate)

| Source | Role |
|--------|------|
| DaddyLive | Core live + Special Events |
| Free-TV/IPTV | Curated country M3U backups (`freetv:*`) |
| iptv-org | GitHub FAST playlists (`iptv:*`) |
| ntv.cx | CDN live (`ntv:*`) |
| Adult Swim | Official CDN HLS |
| dulo.cx Live | Public JSON catalog + JWT playback (`dulo:*`) |
| VOD (TMDB + vsembed) | Movie/series catalog + embed resolve |

## Competitor / related domain list

### SimilarWeb-attributed peers (primary)

| Name | Domain | Notes from probe |
|------|--------|------------------|
| LunaStream | lunastream.watch | 301 → **ezstream.wtf** (origin unreachable from probe host) |
| HDTodayz | hdtodayz.to | Unreachable (HTTP 000) |
| MyFlixer | myflixer.sc | VOD SPA; 200 HTML; robots Content-Signal; no JSON/M3U API |
| TheFlixBay | theflixbay.com | VOD SPA; HTML catch-all on `/api/*`; no playlist |
| 7xStream | 7xstream.tv | Unstable / opaque from probe host |

### Category / affinity neighbors (also evaluated)

| Name | Domain | Notes |
|------|--------|-------|
| VidPlay | vidplay.top | Same VOD-aggregator pattern as MyFlixer |
| HydraHD | hydrahd.info | Landing / hub HTML; no catalog API |
| Cineby | cineby.sc → cineby.at | Next.js VOD; sitemap only; no public stream API |
| 1Shows | 1shows.org | Large Next.js VOD |
| Rive / Rivestream | rivestream.app | Probe overlapped SPA shell; no live M3U |
| WMovies | wmovies.org | Ad-heavy VOD |
| HDToday | hdtoday.sc | Classic movie/series scraper UI |
| HiMovies | himovies.bz | MyFlixer-family clone |
| SkyFlix / FlixMomo / SerialGo / Reelzone | various `.to`/`.tv`/`.live` | Pirate VOD mirrors; churn-prone |
| FreeDSL TV | freedsl.tv | **Link directory** (DaddyLive, StreamEast, Stream4Free, radio) — not a catalog |
| Stream4Free | stream4free.tv | French live + loop channels; page-embedded HLS CDN (`data-stream.top`); **no** `/api` or M3U catalog |
| **dulo.sx** | dulo.sx | Public VOD successor to dulo.tv (dulo.tv DNS dead); SPA HTML; `/api/*` returns HTML shell |
| **dulo.cx** | dulo.cx | Live TV JSON already integrated (`GET /api/live-tv/channels`, ~233 rows) |

## Evaluation table

| Site | Public API / M3U? | Auth / DRM | Gateway fit | Verdict | Reason |
|------|-------------------|------------|-------------|---------|--------|
| lunastream.watch / ezstream.wtf | No durable API found | Unknown (host down) | — | **Skip** | Dead/redirect; nothing to integrate |
| hdtodayz.to | Unreachable | — | — | **Skip** | Domain dead from probe |
| myflixer.sc | HTML only | Embed players; ToS/legal risk | VOD only | **Skip** | Duplicate of TMDB+vsembed path; scrapers brittle |
| theflixbay.com | HTML SPA catch-all | Embed players | VOD only | **Skip** | Same as above |
| 7xstream.tv | Unstable | Unknown | — | **Skip** | No stable catalog |
| vidplay.top | HTML only | Embeds | VOD only | **Skip** | Covered by vsembed |
| hydrahd.info | HTML landing | Embeds / mirrors | VOD only | **Skip** | No API; legal-risk scraper |
| cineby.at | Sitemap only | No signup claimed; embeds | VOD only | **Skip** | FMHY already: design-note only |
| 1shows.org | Next.js HTML | Embeds | VOD only | **Skip** | No playlist API |
| rivestream.app | No live M3U | Unknown | — | **Skip** | Not Free-TV-shaped |
| wmovies.org / hdtoday.sc / himovies.bz / skyflix / … | HTML scrapers | Ads + rotating hosts | VOD only | **Skip** | High churn; legal risk; vsembed covers VOD |
| freedsl.tv | None (outbound links) | N/A | — | **Skip** | Points at DaddyLive etc. already owned |
| stream4free.tv | Per-page HLS URLs only | Opaque CDN tokens in path | Live Partial | **Skip** | Would need HTML scrape; French niche; fragile path hashes |
| dulo.sx (VOD) | No usable JSON from probe | Client-side players | VOD Partial | **Skip** | Overlaps TMDB+vsembed; not a live supplement |
| **dulo.cx Live** | Yes — JSON channels | JWT for playback | Live | **Already integrated** | Cap 100; see [DULO-AUTH.md](DULO-AUTH.md) |

## Integration decision

**No new supplement sources shipped in this pass.**

There are **zero** clear winners that are:

1. Public/stable HTTP catalogs or M3U like Free-TV / iptv-org / ntv / dulo.cx, **and**
2. Not already covered, **and**
3. Free of heavy auth, DRM, or HTML/embed scraping.

Almost every SimilarWeb peer of **dulo.tv** is a **pirate movie/TV SPA** (same product category as dulo.sx), not a live-IPTV playlist publisher. Wiring those would mean production scrapers with legal and reliability risk — worse than the vsembed VOD path we already run.

### Deferred (only if a durable API appears later)

- Official FAST provider APIs (Pluto/Tubi/… native) as **documented expansions of iptv-org playlist selection** — not new scrapers.
- stream4free.tv **only** if they publish a static M3U or JSON channel list (today: page scrape only).
- Any competitor that exposes a documented, unauthenticated `#EXTM3U` or versioned REST catalog.

## Probe method (reproducible)

- Homepages + common paths: `/api`, `/api/v1`, `/playlist.m3u8`, `/channels.json`, `/robots.txt`, `/sitemap.xml`
- UA: desktop Chrome; timeout ~10–15s
- Date: 2026-08-20 (America/New_York)

## Related docs

- [FMHY-STREAMING-EVAL.md](FMHY-STREAMING-EVAL.md) — broader FMHY live/VOD pass (Free-TV + dulo.cx already shipped)
- [DULO-AUTH.md](DULO-AUTH.md) — dulo.cx JWT keyring (no secrets in git)

# FMHY Streaming evaluation (2026-08)

Source: [FMHY Streaming wiki](https://github.com/fmhy/FMHY/wiki/Streaming) (Live TV / Sports, Free w/ Ads, IPTV Tools).

Goal: find **stable HTTP APIs or M3U playlists** that fit StepDaddy’s SupplementStore / PlaylistCache / stream-proxy patterns. Prefer free public IPTV and open FAST-style feeds. Avoid dead domains, credential theft, and half-broken scrapers as production defaults.

## Decisions already shipped

| Change | Verdict |
|--------|---------|
| TheTvApp / TVApp2 / thetvapp.link | **Removed** — not coming back. Special Events is DaddyLive schedule-only (`dlhd-guide:*` / `dlhd-event:*`). |
| MoveOnJoy / TVPass | **Removed** (runtime already gone; residue purged from health/agents). |
| xyzstreams / `247v2.xyzstreams.st` | **Removed** — site homepage still loads, but Sling `247v2` / `ftv` HLS gateways return 404 with wrong SSL (`*.surge.sh`). No durable public catalog API. Not recoverable without fragile scrape of sports index pages. |

## Evaluation table

| Site / source (FMHY area) | Usable for gateway? | Action |
|---------------------------|---------------------|--------|
| **iptv-org** (IPTV Tools / playlists) | Yes — GitHub raw M3U | **Already integrated** (`IptvOrgStreamsSource`) |
| **Free-TV/IPTV** (Awesome IPTV / public playlists) | Yes — curated country M3U, many direct HLS | **Integrated** (`FreeTvIptvSource`, USA/CA/UK) |
| **Dulo Live** (dulo.cx/live) | Partial — public JSON catalog; HLS via auth'd `/live-gateway/` | **Integrated** (`DuloCxLiveSource`, cap 100) |
| **NTV** (Live TV ⭐) | Yes — aggregator pattern | **Already integrated** (`NtvCxCdnLiveSource`) |
| **Adult Swim** (TV Streaming) | Yes — official CDN HLS | **Already integrated** |
| **Pluto / Tubi / Plex / Xumo / SamsungTVPlus** (Free w/ Ads, Smart TV) | Partially — via iptv-org `us_pluto` / `us_tubi` / … streams | **Covered by iptv-org**; no separate scraper |
| **DistroTV** (Live TV) | Partial — appears in iptv-org / Free-TV EPG lists | Rely on iptv-org Distro playlists; no extra client |
| **DaddyLive** (Live TV ⭐) | Yes — primary | **Core** (channels + Special Events schedule) |
| **xyzstreams** (Live TV) | No — 247v2 dead | **Removed** |
| Stream aggregators (Cineby, P-Stream forks, etc.) | Poor fit — embed players, rotating hosts, ToS risk | **Design note only** — do not default on |
| Dedicated movie scrapers (PrimeWire, etc.) | Poor fit — brittle HTML | Keep **TMDB + vsembed** path; no new scrapers |
| Sports link farms (CrackStreams, StreamEast, …) | High churn / abusive ads | Prefer DaddyLive Special Events; no new sports scrapers |
| Torrents-to-stream (Hayase, RuTracker, …) | Out of scope for IPTV gateway | **Skip** |
| Internet Archive / public domain | Interesting VOD | Optional future stub; not wired |
| StreamSports99 / Famelack / EasyWebTV | Unstable mirrors | Stub-worthy only if API discovered later |

## Integrated this mission

**Free-TV/IPTV** (`freetv:*` channels):

- Fetches `playlist_usa.m3u8`, `playlist_canada.m3u8`, `playlist_uk.m3u8` from GitHub raw.
- Skips YouTube/Twitch rows; publishes direct HTTP(S) URLs with `#freetv` tags.
- Settings toggle + import mode; health fields `freeTvEnabled` / `freeTvChannels` / playlists fetched|failed.
- Complements iptv-org (different curated set, smaller English backup).

**Dulo Live** (`dulo:*` channels):

- Public catalog: `GET https://dulo.cx/api/live-tv/channels` (~233 rows probed; gateway caps at 100 non-supporter).
- Distinct from ntv.cx — different host, JSON shape, and auth model.
- Playback: `POST /api/live-tv/playback-session` → `/live-gateway/` HLS (requires Supabase JWT in `supplementDuloCxAccessToken`).
- Auth / keyring refresh: [DULO-AUTH.md](DULO-AUTH.md) + `scripts/dulo-cx-auth.sh`.
- Playlist URLs: `/dulo-stream/{uuid}.m3u8`. Consolidate mode attaches `duloChannelId` mirrors onto DaddyLive name matches.
- Catalog sync works without a token; play returns `dulo_auth_required` until token is set via admin API.

## Promising but not defaulted

- **Official free ad-supported apps** (Pluto/Tubi APIs): better as documented expansions of iptv-org playlist selection than new scrapers.
- **Public-domain / Archive VOD shelves**: needs a dedicated VOD catalog design; leave as future work.
- **xyzstreams sports schedule pages**: could theoretically feed Special Events if a stable JSON API appears; do not scrape the ad-heavy SPA until then.

## SimilarWeb competitors (dulo.tv)

Follow-up eval of [SimilarWeb competitors for dulo.tv](https://www.similarweb.com/website/dulo.tv/competitors/) and affinity peers: see **[DULO-COMPETITORS-EVAL.md](DULO-COMPETITORS-EVAL.md)**.

**Result:** peers are almost all pirate VOD SPAs (MyFlixer / FlixBay / VidPlay / Cineby / HydraHD / …). No new public M3U/JSON live sources worth integrating; VOD already covered by TMDB + vsembed; dulo.cx Live already shipped. **No code changes** from that pass.

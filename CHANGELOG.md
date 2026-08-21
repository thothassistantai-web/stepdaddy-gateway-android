# Changelog

All notable changes to **StepDaddy Gateway** (native Android) are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).  
Versioning follows [Semantic Versioning](https://semver.org/) for `versionName` in `app/build.gradle.kts`.

## [Unreleased]

## [3.0.32] - 2026-08-21

### Changed

- **Default import mode = Merge fallbacks** — Free-TV, iptv-org, ntv, dulo, and Adult Swim default to `CONSOLIDATE_FALLBACKS` (high-confidence score ≥70 attachments only). Full catalog and Skip dupes remain in Settings.
- **Untouched-install migration** — upgrades still on the old `FULL_CATALOG` default with no explicit import-mode choice flip to consolidate; explicit Full catalog / Skip / Merge choices are kept. See `docs/CHANNEL-BACKUPS.md`.

### Added

- Soft dashboard note `Backups: auto · N channels` when consolidated backups are attached.
- Settings tip: wrong backup → Channel backups to remove or block.

## [3.0.31] - 2026-08-21

### Added

- **Smarter channel backup matching** — Consolidate/skip modes use a region- and language-aware score (≥70) instead of normalized-name-only matches. Rejects common false positives (ESPN vs ESPN Deportes, BBC One vs BBC News, US vs UK cousins, CNN vs CNN Türk). Full catalog remains the default.
- **Channel backups editor** — Settings → Channel backups… to view DaddyLive ↔ supplement mirrors, remove backups, block bad auto-pairs, accept/reject suggestions, and manually attach Free-TV/iptv-org/ntv/dulo streams. Overrides survive refresh. See `docs/CHANNEL-BACKUPS.md`.
- **Admin backup APIs** — `GET/POST /api/v1/backups*` for list/attach/remove.

## [3.0.30] - 2026-08-21

### Added

- **Domain relay** — GitHub-hosted `release/domain-relay.json` updates DaddyLive primary/mirrors/blocked/relay hosts without an APK bump. Cache + boot/outage fetch; dashboard banner with AFTV codes when hosts diverge from compile-time defaults. See `docs/DOMAIN-RELAY.md`.
- **VOD catalog relay** — Live overlay `release/vod-catalog-relay.json` for newly found movies/shows with stream candidates, TMDB/title+year dedupe, dead-link probe/cooldown, and resolve preference for working mirrors. Settings toggle (default on). See `docs/VOD-CATALOG-RELAY.md`.

## [3.0.29] - 2026-08-20

### Added

- **dulo.cx Live TV tier** — Supplement source with public catalog sync, `/dulo-stream/{uuid}.m3u8` playback proxy, consolidate Free-TV-style backups onto DaddyLive name matches, and admin setting `supplementDuloCxAccessToken`. Health exposes `duloCxAuthConfigured` / resolve probe fields. Auth helper: `docs/DULO-AUTH.md`, `scripts/dulo-cx-auth.sh`.
- **Free-TV live backups** — Additional Free-TV mirror rows for consolidate fallback when primary DaddyLive streams fail.

### Removed

- **Dead TheTvApp / xyzstreams / MOJ residue** — Dropped leftover routes, EPG generators, settings toggles, and agents for sources that no longer resolve.

### Changed

- **AFTV Downloader reminder** — Permanent codes remain Release `4860686` / Debug `1401588` via versionless latest assets.

## [3.0.28] - 2026-08-20

### Added

- **New release signing key** — Regenerated release keystore after the previous key was lost. New cert SHA-256 `94:91:41:8C:…:39:75`. Signed release APKs resume OTA for fresh installs. See `docs/KEYSTORE-BACKUP.md`.
- **Graduate to Release** — Debug builds (Settings / About) can download the signed release APK and open the installer. Debug and release remain different `applicationId`s; graduation is uninstall/side-by-side, not silent conversion.
- **Signing mismatch messaging** — In-app update path detects certificate mismatch and explains that Android cannot update the same package with a different signer.

### Changed

- **Update manifest channels** — Debug OTA keeps `apkUrl` (release) and `apkUrlDebug` separate so graduation can still fetch the release APK.
- **Migration docs** — `docs/UPDATES.md`, `docs/INSTALL.md`, `docs/KEYSTORE-BACKUP.md`, and release notes document stranded old-release installs (uninstall required).

## [3.0.27] - 2026-08-19

### Fixed

- **Daddy Live 404 / stream resolution** — Parse the current `/api/channels` schema `{channel_name,url}` (embed URL per row; legacy `{channel_id,channel_name}` still works). Build relay watch URLs from the embed id, prefer `dlstreams.st` player/casting paths, and skip seized/broken mirrors on the blocklist so TiviMate no longer 404s on dead hosts.

## [3.0.26] - 2026-07-12

### Changed

- **Release metadata refresh** — Version bump and OTA manifest/hash updates for the 3.0.26 build.

## [3.0.24] - 2026-07-05

### Added

- **VOD priority shelves** — Movies and series catalogs fill priority Nextbox/vsembed shelves first (Popular, Trending, Latest, etc.) then backfill remaining tier caps. Separate `shelfCategories` from Cinemeta `genre`; multi-shelf rows let the same title appear in multiple Xtream categories.
- **Tiered VOD caps** — Fire Stick 150/150, Onn 250/250, full-RAM devices 300/300 via `VodCatalogLimits`.
- **Debug/release coexistence guard** — `GatewayPackageGuard` stops the sibling gateway service on startup and logs port-conflict hints when bind fails.

### Fixed

- **Cinemeta shelf overwrite** — Cinemeta enrichment no longer replaces Nextbox shelf labels on movie rows.
- **Debug + release port 3000 conflict** — Release stops debug service; debug stops release service so only one binds port 3000.

## [3.0.23] - 2026-07-05

### Fixed

- **Onn sources hang** — Supplement sync (iptv-org, NTV.cx, Adult Swim) was skipped on all `memoryLite` devices because boot channel refresh used the Fire Stick early-return path. Onn sticks now run supplement sync with shorter deferrals; Fire Stick timing and LMK survival paths are unchanged (`FireTvDevice.isFireTv()`).

## [3.0.22] - 2026-07-04

### Added

- **VOD movie dedup** — Automatic duplicate management for movies only at catalog build time. Collapses rows that share TMDB id, IMDB id, or normalized title+year; keeps the richest/playable entry and merges shelf tags. Logs removed duplicate count.

## [3.0.21] - 2026-07-04

### Fixed

- **TiviMate VOD 403** — VOD HLS manifests proxy segments through `/vod-content/` with vsembed embed referer and browser User-Agent so TiviMate internal player no longer hits upstream 403 on CDN segments.

### Added

- **VOD year sort** — Movies and series Xtream lists sort by release year descending (newest first).
- **Nextbox catalog** — Scrapes nextbox.uno homepage and featured pages; categories mirror site shelves (Popular Movies, Horror Movies, Trending TV Series, etc.).

## [3.0.20] - 2026-07-04

### Fixed

- **Onn / low-RAM TV stick LMK survival** — Memory-lite mode (catalog skip, deferred EPG/prewarm, compact HTTP, boot keep-alive) now applies via `LowRamTvDevice` to Fire Stick, Onn/Walmart sticks, and any leanback TV under 1.5 GiB RAM — not only Amazon Fire TV. Fire-OS-only boot delay and network wait remain Fire-gated.

## [3.0.16] - 2026-06-30

### Added

- **Import mode: Merge fallbacks** — third supplement import option alongside "All channels" and "Skip dupes". Overlapping iptv-org, ntv.cx, xyz, and Adult Swim rows attach as automatic failover mirrors on the matching DaddyLive channel (multi-variant HLS master) instead of separate playlist entries. Internal iptv-org duplicates attach mirrors on the kept supplement row via `/supplement-stream/{id}/master.m3u8`. Settings uses a 3-way toggle per provider; fallbacks persist in `daddy_fallbacks.json`.

## [3.0.15] - 2026-06-30

### Fixed

- **Supplement sync lifecycle** — publish merged catalog before logo enrich; clear `syncInFlight` before refresh callbacks; outer try/finally prevents stuck sync flag
- **Health during iptv-org fetch** — incremental sync stats after playlist merge; health reports iptv-org counts from cache or in-flight merge
- **Special Events health** — `specialEventsStatus` no longer shows "syncing" during unrelated supplement refresh (iptv-org, TMDB, etc.)
- **Xtream parity** — iptv-org supplements use `GroupTitleResolver` in live categories (matches M3U); series episodes include `direct_source` playback URLs
- **Xtream auth** — `/movie/` and `/series/` redirect routes require valid credentials
- **Settings** — saving supplement/EPG toggles triggers supplement + EPG refresh without full service restart

### Changed

- **Channel numbering** — iptv-org supplements always resolve group via `GroupTitleResolver` (even with empty tags)
- **Phone layouts** — portrait dashboard stacks cards vertically with 2×2 stats; compact landscape layout for phones under 600dp sw; player rotates freely on phones (TV stays landscape)

## [3.0.14] - 2026-07-01

### Added

- **Xtream live API** — `player_api.php` `get_live_categories` and `get_live_streams` so TiviMate Xtream login imports live channels (not just VOD)

### Changed

- **Xtream player_api auth** — live actions work when Movies (VOD) supplement is disabled; VOD actions still require VOD enabled

## [3.0.13] - 2026-07-01

### Added

- **xyzstreams supplement** — US cable/broadcast live TV (~69 static channels + EPG-driven discovery probes on 247v2); TV Guide EPG via proxied tvguide.com lineup; stream proxy `/xyz-stream/{id}.m3u8`
- **xyzstreams EPG discovery toggle** — Settings → probe TV Guide for extra channels (on by default; disable for faster sync)
- **xyzstreams health logging** — `/health`, dashboard, and logcat report catalog vs discovered channel counts and discovered channel names
- **iptv-org playlist picker** — enable/disable each of the 39 GitHub FAST playlists individually (Settings → Choose iptv-org playlists…)
- **vsembed catalog** — latest movies from `vsembed.ru`/`vsembed.su` list JSON (3 pages, up to 150 titles); Cinemeta meta enrichment (no TMDB/IMDB key required)
- **Series VOD catalog** — latest episodes from vsembed `/episodes/latest/` JSON in `📺 Shows` group; supplement IDs `vod:series:{showTmdbId}:{season}:{episode}`
- **Series stream proxy** — `/vod/series/{tmdbId}/{season}/{episode}.m3u8` (and `.mp4` for TiviMate); vsembed TV embed + Moviebox TV fallback
- **Series JSON API** — `GET /series` lists episode metadata and stream URLs
- **vsembed stream resolver** — mirror failover across vsembed + legacy vidsrc-embed hosts; IMDB + TMDB embed paths
- **Moviebox SDK fallback** — Kotlin port of moviebox-js-sdk search + `/subject/play` when vsembed fails
- **VOD genre shelves** — movies grouped by Cinemeta genre (`🎬 Action`, `🎬 Comedy`, …); series by genre or per-show shelf (`📺 {Show Title}`) when listed in vsembed `/tvshows/latest/`
- **Xtream Codes VOD API** — `GET /player_api.php` with `get_vod_categories`, `get_vod_streams`, `get_vod_info`, `get_series_categories`, `get_series`, `get_series_info`; `/movie/{user}/{pass}/{id}.mp4` redirects to VOD proxy

### Changed

- VOD settings label → **Movies (VOD)**; provider tag `VOD` instead of `TMDB`
- **VOD metadata (Xtream-style)** — movies titled `Title (Year)`; episodes `Show - S01E01`; Metahub large + TMDB `w600_and_h900` posters; Cinemeta enrichment for all catalog rows (plot, genre, cast in `tvg-desc`); one meta lookup per show (fixes wrong/duplicate covers)
- **VOD playlist titles** — VOD rows no longer get live-channel `US: TITLE ᴸᴵⱽᴱ` formatting; posters bypass iptv-org logo resolver (fixes wrong covers like Avatar → Pluto logo)
- **vsembed playback** — drop dead vidsrc-embed mirrors; fix `/prorcp/` script path parsing; resolve new `master_urls` + `generate.php` token flow; shorter HTTP timeouts so Moviebox fallback runs when embed hosts fail
- **TiviMate VOD tabs** — `tvg-type="movie"` / `tvg-type="series"` (+ season/episode metadata) routes rows to Movies vs Series tabs; stream URLs use `.m3u8` (`.mp4` requests redirect to HLS)

## [3.0.12] - 2026-06-30

### Added

- **IMDB / TMDB Movies (VOD)** — trending & popular movies in `🎬 Movies` group on `tivimate.m3u`, `streamvault.m3u`, and `vlc.m3u`
- **VOD stream proxy** — `/vod/movie/{tmdbId}.m3u8` (and `.mp4` for TiviMate VOD classification) resolves playable HLS via vidsrc-embed (StreamFlix-compatible)
- **Movies JSON API** — `GET /movies` lists catalog metadata, posters, and stream URLs
- **Settings toggle** — Settings → supplements → enable IMDB/TMDB movies catalog (on by default)
- **Cinemeta catalog** — metadata from Stremio Cinemeta when no TMDB API key is configured; optional TMDB enrichment via `TMDB_API_KEY` in `local.properties`
- **Unit tests** — `TmdbVodSourceTest`, `TmdbVodPlaylistTest`, `TmdbVodCatalogParseTest`

## [3.0.11] - 2026-06-30

### Added

- **Volume normalization settings** — Settings → Audio toggle; exposed via `/health` `audio`, setup JSON, and StreamVault plugin `audio_json` on playback prepare (companion players apply loudness processing when supported)
- **Amplification gain** — Settings → Audio slider (−12 … +12 dB, default 0 dB); applied to embedded ExoPlayer preview via `Player.setVolume`
- **Unit tests** — `AudioPlaybackSettingsTest` for gain clamping and dB→linear conversion

## [3.0.10] - 2026-06-29

### Fixed

- **Special Events parser errors** — error HLS manifests no longer emit fake `#EXTINF` / `unavailable.ts` segments that trigger ExoPlayer `ParserException` on TiViMate 5.x mods
- **HBO 2 Eastern EPG** — bundled `tvtv_bundled_grids/HBO2.us.json` fallback when tvtv.us returns HTTP 429 (real programme titles instead of "Live programming" placeholders)
- **View gateway release** — About → View gateway release opens `stepdaddy-gateway-android` GitHub releases (not `tivimate-daddy`)
- **TiViMate x2 mod UI** — dashboard/About strings and Launch flow target `ar.tvplayer.tv` x2 Premium mod (`com.andyhax.haxsplash.LaunchActivity`)

## [3.0.9] - 2026-06-29

### Fixed

- **Mirror health observability** — `/health` `mirrorStats` is populated when Special Events mirrors exist; per-mirror probe cache and event-level fallback drive `specialEventMirrorsHealthy` counts instead of always reporting zero

## [3.0.8] - 2026-06-29

### Added

- **Special Events mirror consolidation** — one playlist row per event at `tivimate-stream/dlhd-event-{key}.m3u8`; all upstream `channels[]` stored as internal mirrors with multi-variant HLS master failover
- **Mirror health** — `/health` supplement stats expose `specialEventMirrorsTotal`, `specialEventMirrorsHealthy`, `specialEventMirrorEvents`, and per-event `activeMirrorIndex`

### Changed

- **Event budget** — `MAX_SPECIAL_EVENT_STREAMS=120` caps unique events, not backup URLs (e.g. Germany vs Paraguay with 58 links = 1 row)
- **Ended grace** — post-stop playlist grace reduced to 15 minutes

### Fixed

- **Stale multi-link blocks** — full special-events replace on sync drops legacy per-mirror playlist rows

## [3.0.7] - 2026-06-29

### Fixed

- **Special Events cap** — max 2 upstream links per schedule event; live/on-air rows prioritized by start time instead of category A–Z; expired rows pruned before merge; stream cap raised to 120

## [3.0.6] - 2026-06-29

### Fixed

- **Eastern EPG pass** — sustained tvtv.us 429 no longer aborts remaining HBO 2 / Showtime / STARZ fetches; only the general gap-fill pass is skipped

## [3.0.5] - 2026-06-29

### Fixed

- **Eastern EPG prioritization** — HBO 2, Showtime, STARZ in Black, and STARZ Kids & Family fetch in a dedicated first tvtv.us pass (fixed preferred order, separate rate budget) before general cable gap-fill
- **tvtv.us rate limits** — general pass capped at 12 channels/build; stronger 429 backoff (8s→64s), 60s pause on sustained 429, skip general pass when exhausted; 6h grid JSON disk cache with stale fallback

## [3.0.4] - 2026-06-29

### Fixed

- **Eastern EPG rate limits** — tvtv.us grid fetches retry HTTP 429 with exponential backoff (5s → 10s → 20s) and a longer inter-request delay (2s) so HBO 2, Showtime, and STARZ in Black keep real programme titles instead of "Live programming" placeholders

## [3.0.3] - 2026-06-29

### Fixed

- **EPG HEAD** — rely on Ktor `respondFile` for `/epg.xml` HEAD (no manual `Content-Length`; fixes duplicate header on TiViMate EPG refresh)

## [3.0.2] - 2026-06-29

### Fixed

- **EPG HEAD** — `/epg.xml` HEAD no longer emits duplicate `Content-Length` headers (TiViMate silent EPG refresh)

## [3.0.1] - 2026-06-29

### Fixed

- **Eastern premium movie EPG** — HBO 2 HD, Showtime HD/RAW, STARZ in Black, and STARZ Kids & Family no longer use epgshare `US2` Pacific/west offsets; playlist ids (`HBO2.us`, `Showtime.us`, `StarzInBlack.us`, `StarzKidsFamily.us`) prefer tvtv.us East site rows before epgshare merge
- **Legacy playlist alias** — `/tivimate-playlist.m3u8` serves the same full catalog body as `/tivimate.m3u8` (not the diagnostic 50-channel bootstrap)
- **Special Events guide playback** — guide HLS wrappers use master-playlist `EXT-X-STREAM-INF` (fixes ExoPlayer `UnexpectedLoaderException` on TiViMate 5.x mods)
- **TiViMate x2 Premium mod** — Launch opens `com.andyhax.haxsplash.LaunchActivity`; gateway sends VIEW intents to import M3U + EPG when manual add-playlist is blocked
- **EPG HEAD** — `/epg.xml` HEAD returns a single accurate `Content-Length` (TiViMate silent EPG update failure)

## [3.0.0] - 2026-06-28

### Added

- **Stable suite release** — Gateway `3.0.0` / `versionCode` 30000 aligned with StreamVault `3.0.0`
- **App-named playlists** — canonical user URLs: `streamvault.m3u`, `tivimate.m3u`, `vlc.m3u` (+ `.m3u8` aliases); legacy setup paths kept as diagnostics
- **StreamVault embedded plugin** — `provider.m3u` returns `streamvault.m3u` + `epg.xml`; `MSG_ENSURE_GATEWAY` readiness gate
- **Special Events tiers 1–5** — alphabetical guides, language/region metadata, schedule times + EPG programmes, stream health dots (🟢🔴🟡⚪), auto lifecycle add/remove
- **Event metadata pipeline** — scraper, schedule resolver, HLS manifest probes, mirror latency tracking, dashboard status
- **Health endpoints** — `/health` and `/health?lite=1` expose special-events summary, load progress, StreamVault/TiviMate setup blocks

### Changed

- **Documentation** — `docs/GATEWAY.md`, `PLUGIN_API.md`, `TIER-RELEASES.md`, `STREAMVAULT-GATEWAY-PLAN.md`; cross-link [StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV)
- **Release signing** — `keystore.properties` + `assembleRelease` signing config for self-signed sideload APKs

## [2.0.0] - 2026-06-23

### Changed

- **Suite alignment** — unified semver `2.0.0` / `versionCode` 20000 with TiviMate Daddy patch
- **Version source** — `STEPDADDY_VERSION` at monorepo root drives Gradle `versionName` / `versionCode` and default TiviMate patch constants

## [1.0.34] - 2026-06-22

### Fixed

- **Special Events guides in TiviMate** — guide channels use HLS `.m3u8` wrappers (not direct `.mp4`) so they appear in the live TV list, not Movies/VOD
- **Event playlist titles** — scraped event titles (e.g. Bulls vs Knicks) used for stream rows; EPG blocks still use scraped start/stop times

## [1.0.33] - 2026-06-22

### Added

- **Dynamic Special Events** — prune finished events every 2 minutes; re-fetch DaddyLive + TheTvApp schedule every 15 minutes without waiting for full 6h supplement sync; playlist + EPG rebuild on each change

### Fixed

- **Special Events health** — `sportsChannels` counts `dlhd-guide` / `dlhd-event` rows; health exposes `specialEventGuides` and `dlhdEventStreams`
- **Supplement sync** — DaddyLive schedule fetch runs in parallel with TheTvApp resolver
- **Guide schedule video** — 120s MP4 slate for longer on-screen schedule cards

## [1.0.32] - 2026-06-22

### Changed

- **Logo catalog enrich** — resolve remote logos when DaddyLive/supplement catalogs refresh; persist on channel records; remove scheduled logo backfill job
- **Gateway HUD** — unified in-app chip + compact bottom overlay replaces top banner, heads-up success alerts, and `ServerReadyActivity` launches; one ready ping per boot at catalog load

## [1.0.31] - 2026-06-22

### Fixed

- **Sources stat card** — weighted per-source sync progress (sports/IPTV-org/NTV/Adult Swim milestones) so the bar advances instead of sticking at ~80% when a source has zero channels
- **D-pad stat cards** — focus chain from header through all four tiles to server controls; focus highlight and Enter opens drill-down

### Changed

- **Messages log** — level tags (`INF`, `STS`, `WRN`, etc.), richer dashboard status lines on load phase changes, supplement sync summaries

## [1.0.30] - 2026-06-22

### Added

- **Dashboard stat cards** — progress bars with % and ETA while channels, EPG, sources, or gateway are loading
- **Stat card drill-down** — tap any of the four tiles for detailed stats, actions (refresh EPG/channels/supplements), and settings links
- **`/health` loadProgress** — per-tile phase, percent, and etaSeconds for dashboard UI

### Fixed

- **Dashboard flicker** — keep last good health when a poll fails instead of blanking tiles to Starting
- **`health.starting`** — based on empty combined catalog, not `serverRunning` flag
- **EPG invalidate** — full meta reset on mapping fix (no stale programme counts in coverage)

## [1.0.29] - 2026-06-22

### Fixed

- **Dashboard scroll (launch)** — removed `fillViewport` and inner `layout_weight` stretch inside the main `ScrollView` so the header is visible on cold start (completes the 1.0.28 scroll fix)
- **Message panel** — skip auto-scroll-to-bottom on first paint so opening the dashboard does not jump to the log area

## [1.0.28] - 2026-06-22

### Changed

- **Special Events** — live sports supplement enabled by default for new installs
- **MoveOnJoy sidecar** — off by default; toggle now syncs loopback URL on save (legacy supplement URL field hidden)
- **Enable all supplements** — one-way master toggle (no longer flips off when disabling a single provider); includes Sports/Special Events

### Fixed

- **Dashboard status tile** — fixed-size Online/Starting/Loading labels (no layout jump on server state changes)
- **Dashboard scroll** — opens at top; removed weight stretch that pushed focus to the bottom panel
- **Resume UX** — cached gateway health hydrates stats when returning while the server is still running
- **Lifetime Network EPG** — correct `LifetimeNetwork.us` tvg-id; bridge cross-wire removed; stale runtime map migration + one-time EPG rebuild
- **Supplement settings** — skip-duplicate rows hide when provider is off (no layout shift)

## [1.0.27] - 2026-06-22

### Added

- **Gateway EPG toggle** — disable on-device XMLTV merge and pass external epgshare feeds to TiviMate via playlist `url-tvg` (multi-URL, comma-separated)
- **Settings → EPG** — pre-filled US2 / US_SPORTS1 / US_LOCALS1 defaults; multiline external feed editor
- **`/sports-epg.xml`** — loopback Special Events guide when gateway EPG is off
- **`EpgPlaylistUrlResolver`** — single source for playlist header, dashboard, QR, and health EPG URLs

### Changed

- **EPG cold boot** — two-phase build skips slow tvtv.us gap-fill on first pass for faster initial guide
- **XmltvParser** — growable stream buffer reduces GC during gzip feed scans
- **Supplement sync** — skips sidecar/FAST/iptv-org EPG downloads when gateway EPG disabled
- **Health / status** — reports external EPG mode instead of “Building EPG…” when gateway merge is off

### Fixed

- **Special Events EPG** — finished events removed from playlist; real event titles instead of “Live programming” placeholders
- **Guide schedule** — category guides sit above events; improved bitmap/HTML renderer and lifecycle filtering
- **Dashboard / QR** — EPG copy and QR codes no longer point at disabled `/epg.xml`

## [1.0.26] - 2026-06-22

### Added

- **Guide schedule video** — on-device 1280×720 H.264 MP4 cards for Special Events guide channels (TiviMate-compatible direct `.mp4` URLs)
- **Guide HTML schedule pages** — `/dlhd-event-guide/{slug}.html` with event times and empty/upcoming states
- **Category emoji prefixes** — guide titles use sport/category emoji (⚾ Baseball, ⛳ Golf, 🏊 Swimming, etc.)
- **`guide_fallback.mp4`** — asset fallback when schedule bitmap encode fails

### Changed

- **Special Events ordering** — each category guide channel sits directly above its event streams (Golf Schedule → Golf events, etc.)
- **Guide stream URLs** — playlist uses direct `.mp4` instead of HTML/HLS wrappers for IPTV players
- **Supplement mirror fallback** — Special Events merge retries alternate mirrors when primary fetch fails
- **Playlist sort revision** — bumped to 16 for guide/event interleave order

### Fixed

- **TiviMate guide playback** — ExoPlayer `UnexpectedLoaderException` / `UnrecognizedInputFormatException` on guide channels
- **Guide EPG** — placeholder programmes when no events; extended visibility for upcoming schedules

## [1.0.25] - 2026-06-21

### Added

- **🎟️ Special Events** — merged DaddyLive schedule (`tv.json` / `tv2.json`) + TheTvApp live events in one category
- **Category guide channels** — PPV, Tennis, Live Events, etc. with full schedule EPG from DaddyLive page titles/times
- **DaddyLive tv2 streams** — `/dlhd-event-stream/{token}` resolves `embed.st` paths at play time
- **`SpecialEventsMerger`**, **`DaddyLiveEventResolver`**, **`SpecialEventsEpgGenerator`**

### Changed

- **Adult Swim 24/7** — moved to Entertainment with `US: 24/7 : Adultswim {NAME} ᴿᴬᵂ` titles
- **Group label** — `🎟️ Special Events` (legacy `🎟️ | Special Events` aliases forward)
- **Supplement Sports toggle** — enables merged Special Events (DaddyLive + TheTvApp)

### Fixed

- **EPG HEAD** — duplicate `Content-Length` on `/epg.xml` HEAD (TiviMate playlist update failure)

## [1.0.24] - 2026-06-21

### Added

- **Playlist title style setting** — Xtream-style `US: CHANNEL HD` / `ᴿᴬᵂ` FAST titles (default) or legacy flag-suffix titles
- **`XtreamCategoryTitleFormatter`** — cable vs FAST display formatting without changing `group-title` categories

### Changed

- **TiviMate group sidebar order** — Entertainment → Movies → Local Channels → News → Sports → Kids → Documentary → Music → Extra 24/7 → International → En Español → XXX Adult; playlist emitted by group order then `tvg-chno`
- **Group sort aliases** — `Locals`, `Premium`, Adult Swim marathon, iptv-org, and TheTvApp sports map into canonical slots

## [1.0.23] - 2026-06-21

### Fixed

- **tvtv.us trailing grid chunk** — skip partial windows under 24h at the end of the 48h EPG horizon (HTTP 400 was failing every cable gap-fill channel, including Lifetime)

## [1.0.22] - 2026-06-21

### Added

- **tvtv.us cable EPG gap-fill** — on-device fetch from public Gracenote/TMS grid API (no Stalker/Xtream login); `TvtvUsEpgFetcher` + `tvtv_id_bridge.json` (~2k playlist id → site_id mappings)
- **`scripts/generate-tvtv-id-bridge.py`** — rebuild bridge from iptv-org `tvtv.us.channels.xml` + bundled playlist ids
- **`scripts/fetch-xtream-epg-crosswalk.py`** — optional crosswalk export via env credentials (research only)

### Fixed

- **Lifetime EPG mappings** — `LifetimeNetwork.us` / `LifetimeMovieNetwork.us` with tvtv.us schedules; no longer `USANetwork.us` or epgshare NCIS mis-tags
- **DaddyLive tvg-id load order** — bundled/runtime mapper overrides win over stale disk cache
- **PLEX FAST overrides** — Love & Drama and geo-blocked Movie Favorites
- **tvtv.us rate limits** — 24h grid windows, 24 channels/build cap, 1.5s request delay, main playlist prioritized

## [1.0.21] - 2026-06-21

### Added

- **EPG gap-fill + regional feeds** — lazy merge of PLEX1, DISTROTV1, BEIN1, UK1, DE1, FR1, IT1, ES1, CA2, AU1, TR1, AE1, BR1, NZ1 when primary US feeds leave gaps
- **FAST XMLTV sources** — Roku (GitHub mirror), Xumo, Tubi, LocalNow; mjh.nz Pluto/Plex/Samsung retained
- **Context-aware tvg-id resolution** — `FastChannelContext` + `FastChannelTvgIdResolver` validate provider suffixes; reject wrong id styles on FAST channels
- **DaddyLive EPG research** — `daddylive_epg_research.json`, `DaddyliveEpgResearchStore`, admin `epg-research` asset import/export
- **~1,958 bundled name overrides** — FAST repair, ntv.cx Falcon/CDN parent mapping (581), DaddyLive research merges
- **EPG research scripts** — `research-daddylive-tvg-ids.py`, `research-supplement-tvg-ids.py`, `map-ntv-falcon-cdn-epg.py`, `grab-event-epg.sh` (off-device NHL/Peacock/tvtv.us)

### Changed

- **Primary epgshare routing** — US2/US_SPORTS1/US_LOCALS1 on boot; regional/Plex/Distro deferred to gap-fill (320 MB feed cache cap)
- **EpgChannelMapper** — research store + CDN/Falcon/(MOJ) suffix stripping for override lookup
- **TvgIdResolver** — name overrides first; context-aware fuzzy rejection; fixes mismatched ids on backfill
- **iptv-org grab script** — adds `watch.whaletvplus.com`
- **generate-epg-id-bridge.py** — merge-only sync; no longer shrinks bundled overrides

## [1.0.18] - 2026-06-21

### Added

- **455 high-confidence EPG name overrides** — verified tvg-ids from XMLTV feed crosswalk (Samsung/Pluto/Plex hash ids, epgshare, iptv-org)
- **Supplement EPG override pass** — bundled name overrides now apply to iptv-org supplement channels before EPG build

## [1.0.17] - 2026-06-20

### Added

- **Unified update coordinator** — single auto-check per session, startup grace, no overlapping update/install dialogs
- **Stale update cleanup** — cached APKs older than the installed build are discarded with a short toast

### Fixed

- **Fresh-install boot** — channel fetch runs before supplement sync; empty cache triggers immediate upstream refresh (~10s vs stuck at 0 channels)
- **Misleading “starting” UI** — dashboard distinguishes server listening vs channels still loading; `/health` `starting` only when the gateway is not yet up
- **Upstream channel load** — fast logo path on live fetch (same as disk cache) to avoid long boot delays
- **Update UX collisions** — Settings no longer duplicates MainActivity auto-check; auto-download skips straight to install-ready; pending APK never auto-installs

## [1.0.16] - 2026-06-20

### Added

- **FAST EPG (mjh.nz)** — Pluto, Samsung, Distro, Plex, Xumo, Roku, Stirr guides merged at build time; name→channel-id index backfills empty iptv-org `tvg-id`s
- **EPG Phase 0–2** — bundled name overrides + epgshare ID bridge; auto `tvg-id` resolver; sports synthetic EPG; 2h placeholder programmes; `/health` `epgCoverage` metrics
- **Logo backfill** — fuzzy logo assignment, smallest playlist category first
- **Adult Swim 24/7** supplement with live HLS probe (from prior branch work)

### Fixed

- **Boot hang** — disk channel load uses exact-only logo lookup (no fuzzy Levenshtein on 1k+ channels); embedded sidecar refresh deferred until after HTTP listen
- **EPG HTTP 500 / OOM** — `/epg.xml` streams from disk instead of loading 27MB+ into memory
- **Supplement sync order** — FAST guides download before iptv-org fetch; boot path refreshes supplements before upstream channel pull
- **FastEpgCatalog OOM** — per-feed cached gzips merged at EPG build time (index-only refresh)

### Changed

- Entertainment / category network sort refinements; channel numbering updates

## [1.0.9] - 2026-06-20

### Added

- **Bundled ntv.cx catalog bootstrap** (~940 channels) when live API is unreachable on device

### Fixed

- **TiviMate playlist/EPG refresh** — HEAD responses now report real `Content-Length` (was 0, broke “update playlist”)
- **ntv.cx on Android TV** — Chrome TV user-agent + `Connection: close` for `/api/get-channels`
- **OTA invalid package** — updater prefers `*-debug.apk` and verifies package name before install

## [1.0.8] - 2026-06-20

### Fixed

- ntv.cx catalog fetch on Android TV: force **HTTP/1.1** and send `Accept: application/json` (avoids HTTP/2 *connection closed* from ntv.cx)

## [1.0.7] - 2026-06-20

### Fixed

- **ntv.cx 0 on slow devices** — catalog fetch now runs in parallel with iptv-org, retries 3× with backoff, 120s read timeout, and disk cache fallback when `get-channels` times out

## [1.0.6] - 2026-06-20

### Added

- **ntv.cx 24/7 supplement (full catalog)** — Titan (`cdnlive`, ~450) + Falcon (`hesgoales`, ~493); group **Extra | 24/7**, titles tagged CDN or Falcon
- **Falcon play-time resolve** — hesgoaler token refresh on `/ntv-stream/{id}.m3u8`

### Changed

- **ntv.cx 24/7 enabled by default** on fresh installs; merge mode stays **all channels** (supplement-only off)

### Fixed

- ntv.cx HLS playback uses direct CDN URLs (no segment proxy) for cdnlivetv and hesgoaler streams
- Channel slugs normalized to lowercase to match ntv.cx watch URLs

## [1.0.5] - 2026-06-19

### Added

- **ntv.cx CDN Live supplement** — optional ~450 CDN Live channels via Settings; play-time signed HLS refresh on `/ntv-stream/{id}.m3u8`
- **ntv.cx merge mode** — default **all channels** (labeled CDN); optional **supplement only** skips names already on the main DaddyLive list

### Fixed

- Player gateway preflight: use OkHttp loopback `/health` probe (matches dashboard) instead of `HttpURLConnection` that failed on the main thread (`PLY-NO_GATEWAY`)
- Dashboard footer no longer shows stale "Online" from persisted `serverRunning` before live `/health` poll
- EPG: retry when first build produces 0 programmes; kick build at gateway start instead of only after 45s defer
- Network guard: recognize IPv4-mapped IPv6 loopback (`::ffff:127.0.0.1`) in all access modes

## [1.0.4] - 2026-06-19

### Fixed

- In-app updater: GitHub releases now ship the **debug** APK (`com.thothassistant.stepdaddy.gateway.debug`) so sideload installs can upgrade in place
- APK download integrity: reject HTML error pages, validate ZIP magic (`PK`), verify Content-Length, retry up to 3 times, discard corrupt partial files

## [1.0.3] - 2026-06-19

### Added

- Network access modes (Default / Local / Remote) with Ktor request guard and bind enforcement
- Settings → Network: mode selector, gateway name, remote tunnel URL, access token
- Dashboard URLs and QR dialog respect active network mode
- LAN peer discovery banner (Local / Remote)
- Docs: `docs/NETWORK-MODES.md`, `docs/REMOTE-ACCESS.md`

## [1.0.2] - 2026-06-19

### Added

- Install Apps page search bar — filter catalog by name, description, or source (TV D-pad friendly)

## [1.0.1] - 2026-06-19

### Added

- README screenshot gallery (`docs/screenshots/`)

### Changed

- Install Apps page TV D-pad navigation and app metadata display
- Restored optional manifest URL override in Settings

### Fixed

- Embedded player tab reliability and compact player controls

## [1.0.0] - 2026-06-18

### Added

- Native Kotlin + Ktor gateway on port 3000 (replaces Termux/Python stack)
- TiviMate-compatible M3U playlist, per-channel HLS, XMLTV light EPG
- Foreground service, boot auto-start, startup overlay banner
- Channel mirror failover, disk cache, health endpoint
- TV settings screen with copy-paste URLs and QR codes

### Known limitations

- Release APK requires user-provided signing keystore for Play Store or signed sideload
- Upstream DaddyLive / resportz availability is third-party dependent
- Full web UI / mapping editor remains in Linux `stepdaddy-web` only

[Unreleased]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/compare/v1.0.26...HEAD
[1.0.26]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.26
[1.0.18]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.18
[1.0.17]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.17
[1.0.16]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.16
[1.0.2]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.2
[1.0.1]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.1
[1.0.0]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.0

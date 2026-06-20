# Changelog

All notable changes to **StepDaddy Gateway** (native Android) are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).  
Versioning follows [Semantic Versioning](https://semver.org/) for `versionName` in `app/build.gradle.kts`.

## [Unreleased]

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

[Unreleased]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.2
[1.0.1]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.1
[1.0.0]: https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v1.0.0

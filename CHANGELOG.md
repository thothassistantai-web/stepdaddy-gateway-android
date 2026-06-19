# Changelog

All notable changes to **StepDaddy Gateway** (native Android) are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).  
Versioning follows [Semantic Versioning](https://semver.org/) for `versionName` in `app/build.gradle.kts`.

## [Unreleased]

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

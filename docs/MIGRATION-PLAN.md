# Migration plan — StepDaddy repos

**Date:** 2026-06-18  
**Org:** https://github.com/thothassistantai-web

## Published layout (current)

| Repo | Source | Status |
|------|--------|--------|
| [stepdaddy-gateway-android](https://github.com/thothassistantai-web/stepdaddy-gateway-android) | `/home/nova/livehd/current/stepdaddy-android/` | **Primary** — native Kotlin Android gateway |
| [stepdaddy-livehd](https://github.com/thothassistantai-web/stepdaddy-livehd) | `~/Programs/stepdaddy-web` | Linux/web FastAPI gateway + Reflex UI |
| [android-tv-connect](https://github.com/thothassistantai-web/android-tv-connect) | `~/Programs/Android TV Connect/` | Linux desktop app for Android TV remote pairing |

## Retired repos (deleted 2026-06-18)

These were removed and replaced by the layout above:

- `stepdaddy-livehd-private`
- `StepDaddyLiveHD`
- `StepDaddyLiveHD-Mobile`
- `stepdaddy-lite-onn`

## Package IDs

| Package | Use |
|---------|-----|
| `com.thothassistant.stepdaddy.gateway` | Production sideload / release APK |
| `com.thothassistant.stepdaddy.gateway.debug` | Local `assembleDebug` builds |

Legacy `com.nova.stepdaddylivehd` (Termux) and `com.nova.stepdaddylivehd.gateway` are superseded — uninstall before installing the new APK. See [docs/INSTALL.md](INSTALL.md).

## Android TV Connect

Not in the `livehd/current` workspace; published from `~/Programs/Android TV Connect/`.

## Local-only artifacts

Research notes and FUSA test logs under `/home/nova/livehd/current/*.txt` remain local unless a separate `stepdaddy-dev-notes` repo is created later.

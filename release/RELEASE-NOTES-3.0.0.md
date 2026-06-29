# StepDaddy Gateway 3.0.0

**Stable suite release** — pairs with [StreamVault 3.0.0](https://github.com/thothassistantai-web/StreamVault-IPTV/releases/tag/v3.0.0).

## Highlights

- App-named playlists: `streamvault.m3u`, `tivimate.m3u`, `vlc.m3u`
- Special Events tiers 1–5 (metadata, EPG, health dots, lifecycle)
- StreamVault embedded plugin with `epg_url` and gateway wake
- `/health` and `/health?lite=1` readiness probes

## Install

Sideload `stepdaddy-gateway-3.0.0-release.apk` (`com.thothassistant.stepdaddy.gateway`).

Self-signed release — enable **Install unknown apps** for your file manager.

## Verify

```bash
adb forward tcp:3000 tcp:3000
curl -s 'http://127.0.0.1:3000/health?lite=1' | jq '{ok,version,channels}'
```

See [CHANGELOG.md](../CHANGELOG.md) for full notes.

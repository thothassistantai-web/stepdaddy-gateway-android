# Privacy Policy

**StepDaddy Gateway** (`com.thothassistant.stepdaddy.gateway`)  
**Effective date:** 2026-06-18  
**Maintainer:** [thothassistantai-web](https://github.com/thothassistantai-web)

## Summary

StepDaddy Gateway runs **entirely on your device**. We do not operate a central server that collects your viewing data, accounts, or personal identifiers.

## Data stored on your device

The app may store locally:

| Data | Purpose |
|------|---------|
| Settings (port, upstream URLs, feature toggles) | Gateway configuration |
| Channel history and EPG cache | Faster playlist/guide generation |
| Update preferences | Optional in-app update checks |
| Diagnostic logs | On-screen log panel (not uploaded automatically) |

This data stays in the app's private storage unless **you** export or share it.

## Network activity

When the gateway is running, the app:

- Fetches channel metadata, logos, and stream URLs from **third-party sources you configure**
- Serves playlists and EPG to **local** clients (e.g. TiviMate) on the same device or LAN
- May contact **GitHub Releases** or a URL you set for optional app updates

We do not sell or share this traffic with advertisers.

## Optional update checks

If enabled in Settings, the app requests a public `update-manifest.json` (GitHub Releases or a folder URL you provide). No account is required for GitHub public release assets.

**Google Drive update channel:** planned for a future release; the setting is a placeholder today. See [docs/UPDATES.md](docs/UPDATES.md).

## Permissions

| Permission | Why |
|------------|-----|
| Internet | Upstream fetches and local HTTP server |
| Boot completed | Optional auto-start |
| Install packages | Optional sideloaded app updates |
| Notifications | Foreground service while gateway runs |

## Children's privacy

This app is not directed at children under 13. We do not knowingly collect personal information from children.

## Changes

We may update this policy in the repository. Material changes will be noted in [CHANGELOG.md](CHANGELOG.md).

## Contact

Report privacy concerns via [GitHub Issues](https://github.com/thothassistantai-web/stepdaddy-gateway-android/issues) on the project repository. We do not provide email support.

# StepDaddy Gateway

**Native Android TV IPTV gateway** — Kotlin + Ktor on port **3000**, built for ONN sticks, Google TV, and sideload installs. Replaces the Termux/Python stack with a single APK that serves TiviMate-compatible playlists and XMLTV EPG on-device.

Works **standalone** (any IPTV client) or **with [TiviMate Daddy](https://github.com/thothassistantai-web/tivimate-daddy)** for auto-setup, channel tune, and bidirectional telemetry. See [docs/TWO-APP.md](docs/TWO-APP.md).

| | |
|---|---|
| **Gateway version** | 2.0.0 (`versionCode` 20000) |
| **TiviMate Daddy patch** | `2.0.0` (on TiViMate 4.6.1) |
| **Package** | `com.thothassistant.stepdaddy.gateway` |
| **License** | [MIT](LICENSE) — see [LEGAL.md](LEGAL.md) / [DISCLAIMER.md](DISCLAIMER.md) |
| **Upstream parity** | [stepdaddy-livehd](https://github.com/thothassistantai-web/stepdaddy-livehd) (Linux/web gateway) |
| **TiviMate Daddy (patch APK)** | [tivimate-daddy](https://github.com/thothassistantai-web/tivimate-daddy) — TiViMate 4.6.1 mod releases; `tivimate-daddy-v*` tags on this repo are archived |

---

## For users

### ONN stick quick start

**Fastest path:** [docs/ONN-QUICK-START.md](docs/ONN-QUICK-START.md) — install Gateway + TiviMate Daddy, enable boot + auto-launch, verify in ~5 minutes.

### Gateway only (any IPTV app)

1. Install the APK → [docs/INSTALL.md](docs/INSTALL.md)
2. Open the app → **Start server** → wait for the ready HUD
3. Add playlist + EPG URLs in your player → [docs/TUTORIAL.md](docs/TUTORIAL.md)

### Gateway + TiviMate Daddy (recommended)

1. Start Gateway first (`127.0.0.1:3000` listening)
2. Install `TiviMate-4.6.1-StepDaddy.apk` → auto-setup on first launch
3. Optional: **Launch TiviMate when ready** in Gateway settings

| URL | Purpose |
|-----|---------|
| `http://127.0.0.1:3000/tivimate-playlist.m3u8` | M3U playlist |
| `http://127.0.0.1:3000/epg.xml` | XMLTV guide |
| `http://127.0.0.1:3000/tivimate-stream/{id}.m3u8` | Per-channel HLS |
| `http://127.0.0.1:3000/health` | JSON status |
| `http://127.0.0.1:3000/tivimate-handshake` | Patch bootstrap (bidirectional) |
| `http://127.0.0.1:4617/status` | TiviMate Daddy control API (patch only) |

**Important:** Educational aggregator — does not host video. Third-party upstream may change. [DISCLAIMER.md](DISCLAIMER.md)

**Problems?** → [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) · [GitHub Issues](https://github.com/thothassistantai-web/stepdaddy-gateway-android/issues)

**Network modes:** Settings → Network — **Default** (loopback), **Local** (LAN), **Remote** (tunnel + token). [docs/NETWORK-MODES.md](docs/NETWORK-MODES.md)

**Privacy:** [PRIVACY.md](PRIVACY.md)

### Screenshots

Captured on ONN Android TV (1080p). [More capture notes](docs/SCREENSHOT-CHECKLIST.md).

| Dashboard | Install apps | Settings |
|-----------|--------------|----------|
| ![Dashboard](docs/screenshots/01-dashboard.png) | ![Install apps](docs/screenshots/02-install-apps.png) | ![Settings](docs/screenshots/03-settings.png) |

| Player tab | QR remote setup |
|------------|-----------------|
| ![Player tab](docs/screenshots/04-player-tab.png) | ![QR dialog](docs/screenshots/05-qr-dialog.png) |

---

## For developers

### Requirements

- JDK 17+
- Android SDK API 34 (`ANDROID_HOME`, e.g. `~/Android/Sdk`)
- ADB for device deploy (optional)

### Build debug

```bash
cd stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
```

| Output | Path |
|--------|------|
| **Debug APK** | `app/build/outputs/apk/debug/app-debug.apk` |
| Debug package | `com.thothassistant.stepdaddy.gateway.debug` |

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build release

```bash
./scripts/build-release.sh
```

See [docs/RELEASE.md](docs/RELEASE.md) for signing, GitHub Releases, and Play Store upload.

| Output | Path |
|--------|------|
| Release APK | `app/build/outputs/apk/release/app-release.apk` (signed) or `app-release-unsigned.apk` |
| Play bundle | `app/build/outputs/bundle/release/app-release.aab` |
| Copied artifacts | `release/stepdaddy-gateway-<version>.apk` |

### Build TiviMate Daddy patch

```bash
cd research/tivimate-apk/stepdaddy-patch
./build.sh
# → research/tivimate-apk/TiviMate-4.6.1-StepDaddy.apk
```

### Tests

```bash
./gradlew testDebugUnitTest
```

### Architecture

[ARCHITECTURE.md](ARCHITECTURE.md) — routes, EPG, boot lifecycle, bidirectional API.  
[TWO-APP.md](docs/TWO-APP.md) — independent vs combined deployment, version matrix.

### Project layout

```
stepdaddy-android/
  app/src/main/kotlin/.../gateway/
    ServerService.kt          # Foreground service
    GatewayServer.kt          # Ktor routing
    GatewayHud.kt             # Ready HUD + TiviMate auto-launch
    TiviMateController.kt     # Patch HTTP :4617 + intents
    routes/                   # Health, playlist, stream, EPG, tivimate-*
    epg/                      # Light EPG builder
    upstream/                 # DaddyLive + resportz + Special Events
    ui/                       # TV dashboard, player, settings
  docs/                       # Install, tutorial, two-app, ONN quick start
  scripts/                    # build-release.sh, FUSA boot tests
research/tivimate-apk/stepdaddy-patch/   # TiviMate Daddy smali patch
```

### Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md)

---

## Features

- Embedded Ktor HTTP server with network modes (Default / Local / Remote)
- DaddyLive channel API + mirror failover + resportz HLS resolution
- Special Events (DaddyLive schedule + guides) with TiviMate-compatible HLS wrappers
- Light EPG (epgshare + iptv-org), channel logos
- Boot auto-start, HUD overlay, **Launch TiviMate when ready**, boot-tune via patch
- Bidirectional API: `POST /tivimate-events`, `GET /tivimate-state`, handshake
- Built-in player, QR remote URLs, optional GitHub/Drive updates
- `/content/` segment proxy for TiviMate HLS compatibility

---

## Documentation index

| Doc | Description |
|-----|-------------|
| [docs/ONN-QUICK-START.md](docs/ONN-QUICK-START.md) | Fastest ONN stick setup |
| [docs/TWO-APP.md](docs/TWO-APP.md) | Gateway + TiviMate Daddy architecture |
| [docs/INSTALL.md](docs/INSTALL.md) | APK install, TiviMate options, permissions |
| [docs/TUTORIAL.md](docs/TUTORIAL.md) | TiviMate setup walkthrough |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Boot, EPG, stream, patch diagnostics |
| [docs/RELEASE.md](docs/RELEASE.md) | Version bump, signing, GitHub release |
| [docs/NETWORK-MODES.md](docs/NETWORK-MODES.md) | Default / Local / Remote access |
| [docs/REMOTE-ACCESS.md](docs/REMOTE-ACCESS.md) | Cloudflare Tunnel, Tailscale, access token |
| [.cursor/skills/tivimate-control/SKILL.md](.cursor/skills/tivimate-control/SKILL.md) | Agent skill — patch + bidirectional API |
| [research/tivimate-apk/stepdaddy-patch/README.md](../research/tivimate-apk/stepdaddy-patch/README.md) | Patch build, HTTP :4617, version history |
| [PRIVACY.md](PRIVACY.md) | Privacy policy |
| [BENCHMARKS.md](BENCHMARKS.md) | ONN stick performance notes |

---

## Related projects

| Platform | Repository |
|----------|------------|
| Linux / web gateway | [stepdaddy-livehd](https://github.com/thothassistantai-web/stepdaddy-livehd) |
| Android TV remote (Linux) | [android-tv-connect](https://github.com/thothassistantai-web/android-tv-connect) |
| TiviMate Daddy patch | `research/tivimate-apk/stepdaddy-patch/` |

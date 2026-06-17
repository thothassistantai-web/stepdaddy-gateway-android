# StepDaddy LiveHD — Native Android Gateway

Gateway-only Android TV app that replaces the Termux/Python stack. Runs an embedded HTTP server on port **3000** for TiviMate on the same device (ONN stick / `fusa`).

**Upstream reference:** StepDaddy LiveHD v0.1.0-beta (`/home/nova/livehd/current/stepdaddy-app`)

## What it does

- Foreground service hosts Ktor HTTP server on `0.0.0.0:3000`
- Fetches channel list from DaddyLive `/api/channels`
- Resolves HLS via resportz relay (same chain as Python `step_daddy.py`)
- Serves TiviMate-compatible URLs identical to Termux plan:

| URL | Purpose |
|-----|---------|
| `http://127.0.0.1:3000/tivimate-playlist.m3u8` | M3U playlist |
| `http://127.0.0.1:3000/epg.xml` | XMLTV (light EPG — epgshare US feeds) |
| `http://127.0.0.1:3000/tivimate-stream/{id}.m3u8` | Per-channel HLS manifest |
| `http://127.0.0.1:3000/health` | JSON health |

## Build

Requirements: JDK 17+, Android SDK 34 (`~/Android/Sdk`).

```bash
cd /home/nova/livehd/current/stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Sideload to ONN stick (fusa)

Device serial: `FUSA2541006925` (adb alias: `fusa` if configured in `~/.android/adb_usb.ini`).

```bash
adb -s FUSA2541006925 install -r app/build/outputs/apk/debug/app-debug.apk
# or, if aliased:
adb -s fusa install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **StepDaddy Gateway** on the stick → **Start server** → add playlist URL in TiviMate.

### UX expectations

| Topic | What to expect |
|-------|----------------|
| **Cold boot** | After reboot, allow **~60–80s** before the gateway is fully ready on the ONN stick (channel preload + EPG build). |
| **Startup banner** | A brief home-screen overlay when the server starts; one optional re-show at +40s if missed. |
| **TiviMate** | **One-time setup** — paste playlist + EPG URLs from the app. TiviMate has no gateway status UI; use the banner or open StepDaddy Gateway. |
| **Streams** | First channel load can take several seconds (upstream resportz chain); repeat plays are faster. |

## Start on boot

The gateway can start automatically after the ONN stick reboots — no need to open the app.

- **Default:** Start on boot is **ON** (toggle on the main screen).
- **BootReceiver** listens for `BOOT_COMPLETED` (plus `QUICKBOOT_POWERON` / `LOCKED_BOOT_COMPLETED` on some TV firmware).
- When enabled, `ServerService` starts as a **foreground service** with a persistent notification.
- Toggle **Start on boot** on the main screen; changes save immediately to SharedPreferences.

### Verify boot auto-start

```bash
# Install (or reinstall) the debug APK
adb -s FUSA2541006925 install -r app/build/outputs/apk/debug/app-debug.apk

# Grant notification permission (API 33+; works via adb on TV)
adb -s FUSA2541006925 shell pm grant com.nova.stepdaddylivehd.gateway.debug android.permission.POST_NOTIFICATIONS

# Simulate boot (no full reboot required)
adb -s FUSA2541006925 shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.nova.stepdaddylivehd.gateway.debug

# Wait ~30–60s for channel preload, then health check from LAN
curl -s http://10.237.74.181:3000/health | jq .

# Confirm foreground notification
adb -s FUSA2541006925 shell dumpsys notification --noredact | grep -A5 stepdaddy
```

Or reboot the stick and repeat the health/notification checks after ~60s.

## Permissions

### Manifest (declared)

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Upstream DaddyLive + local HTTP server |
| `ACCESS_NETWORK_STATE` | Network reachability checks |
| `ACCESS_WIFI_STATE` | LAN / Wi-Fi status on TV stick |
| `FOREGROUND_SERVICE` | Background gateway process |
| `FOREGROUND_SERVICE_DATA_SYNC` | FGS type for HTTP sync workload |
| `POST_NOTIFICATIONS` | Persistent “gateway running” notification (API 33+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `WAKE_LOCK` | Brief wake lock during boot start |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt to exempt app from Doze |

Leanback launcher: `LEANBACK_LAUNCHER` category is declared on `MainActivity`.

### Runtime (first launch)

- **POST_NOTIFICATIONS** — requested on API 33+ when the app opens.
- **Battery optimization** — system dialog opened once if not already exempt (recommended on TV sticks).

### ADB grants (deploy script)

```bash
PKG=com.nova.stepdaddylivehd.gateway.debug   # release: com.nova.stepdaddylivehd.gateway
adb -s FUSA2541006925 shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

`RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, and `FOREGROUND_SERVICE*` are **install-time** grants — no user action.

### Manual TV settings (if boot start fails)

Some OEM TV builds restrict background starts. If health does not respond after boot:

1. **Settings → Apps → StepDaddy Gateway → Notifications** — enable all.
2. **Settings → Apps → StepDaddy Gateway → Battery** — set to **Unrestricted** / disable optimization.
3. Open the app once after install so runtime permission dialogs can appear.
4. Ensure **Start on boot** toggle is ON in the app.

Notifications while running show: `StepDaddy Gateway running — N channels` (N from cached/upstream channel list).

## Legacy Termux/Python app (port conflict)

The older `com.nova.stepdaddylivehd` package (uvicorn on port 3000) **conflicts** with this native gateway. Only one process can bind `:3000`.

```bash
# Stop legacy backend, then uninstall (safe once this gateway is installed):
adb -s FUSA2541006925 shell am force-stop com.nova.stepdaddylivehd
adb -s FUSA2541006925 uninstall com.nova.stepdaddylivehd
```

Health check distinguishes them: legacy returns `"version":"0.1.0-beta.11"` (uvicorn); gateway returns `"version":"0.2.0-gateway-mvp"`.

## Light EPG

Background-built XMLTV from epgshare US feeds (US2, US_SPORTS1, US_LOCALS1). Channel mapping is bundled from `stepdaddy-app` via `scripts/export-epg-mapping.sh` → `app/src/main/assets/channel_epg_map.json`.

```bash
# Refresh mapping asset from Linux stepdaddy-app API
./scripts/export-epg-mapping.sh

# Verify on stick (after first background build, up to ~5 min)
curl -I http://10.237.74.181:3000/epg.xml
curl -s http://10.237.74.181:3000/health | jq '{epgReady,epgProgrammeCount,epgAgeSeconds}'
```

Served from `files/epg/epg.xml` on device. First build downloads ~55MB US_LOCALS1 feed and takes up to ~3 min. Stale cache returns HTTP 200 while rebuilding; 503 only when no cache exists yet.

## TiviMate setup

1. Open **StepDaddy Gateway** on the stick and copy the URLs (or type them in TiviMate).
2. Playlist: `http://127.0.0.1:3000/tivimate-playlist.m3u8`
3. EPG: `http://127.0.0.1:3000/epg.xml`
4. No `adb reverse` needed when TiviMate runs on the same device.
5. **TiviMate does not show gateway status** — check the home-screen banner or this app to confirm the gateway is running.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for Python → Kotlin route mapping.

## MVP vs TODO

### MVP (this fork)

- Kotlin + Ktor CIO embedded server
- OkHttp upstream (channels + resportz)
- Direct-stream mode (`TIVIMATE_DIRECT_STREAMS=TRUE` equivalent — no `/content/` proxy)
- Channel disk cache, mirror failover (daddylive.org / .li / .eu)
- Foreground service + boot receiver
- TV settings activity with copy-paste URLs

### TODO (phase 2+)

- `/content/` and `/key/` segment proxy routes
- Auto proxy/direct probing (`TIVIMATE_PROXY_MODE=auto`)
- Non-DaddyLive mirror HTML scrape (`24-7-channels.php`)
- Channel logos (default SVG at `/ui/default-channel.svg`; upstream `meta.json` resolver TODO)
- Watchdog / health-driven restart
- SOCKS5 proxy support
- Release signing + Play sideload updates

## Project layout

```
stepdaddy-android/
  app/src/main/kotlin/.../gateway/
    ServerService.kt          # Foreground service
    GatewayServer.kt          # Ktor routing
    routes/                   # Health, Playlist, Stream, Epg
    epg/                      # Light EPG (config, store, parser, builder, manager)
    upstream/                 # DaddyLiveClient, ResportzParser, M3U builders
    model/
    ui/MainActivity.kt        # TV settings screen
```

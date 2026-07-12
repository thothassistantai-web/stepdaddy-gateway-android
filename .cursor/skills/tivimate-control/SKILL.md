# TiViMate Control Skill

Use when controlling TiViMate from StepDaddy Gateway, writing `TiviMateController`/`TiviMateWatch`/`GatewayHud` code, debugging playback on ONN sticks, or answering questions about TiViMate intents/activities.

**Architecture:** [docs/TWO-APP.md](../../docs/TWO-APP.md) · **ONN setup:** [docs/ONN-QUICK-START.md](../../docs/ONN-QUICK-START.md)

## Two-app model

| App | Package | Port | Works alone? |
|-----|---------|------|--------------|
| StepDaddy Gateway | `com.thothassistant.stepdaddy.gateway` | 3000 | ✅ Any IPTV client |
| DaddyLive TV (patch, 2.3.0+) | `com.thothassistant.daddylive` | 4617 | ❌ Needs gateway for playlists |
| TiviMate Daddy (legacy ≤2.0.0) | `ar.tvplayer.tv` | 4617 | ❌ Needs gateway; cannot coexist with stock TiviMate |

Together: auto-setup, boot-tune, bidirectional events. Stock official TiviMate (`ar.tvplayer.tv`) = gateway playlist only (no `:4617`); can run beside DaddyLive TV 2.3.0+.

## Version tracking

| Component | Current | Check |
|-----------|---------|-------|
| Gateway | `1.0.34` (`versionCode` 37) | `GET /health` → `version` |
| Patch | `1.2.1-boot-tune-safe` | `GET :4617/status` → `patchVersion` |
| TiViMate base (DaddyLive) | `4.6.1` (4610) | `dumpsys package com.thothassistant.daddylive` (legacy: `ar.tvplayer.tv`) |

### Patch version milestones

| `patchVersion` | Notes |
|----------------|-------|
| `1.2.1-boot-tune-safe` | 5 s boot-tune defer — **use on fleet sticks** |
| `1.2.0-boot-fast` | Faster tune; Room/SQLite crash on cold boot |
| `1.1.0-bidir` | `/state`, `/channels`, POST events to gateway |

Always probe: `adb shell dumpsys package com.thothassistant.daddylive | grep versionName` (legacy: `ar.tvplayer.tv`)  
Patch probe: `TiviMateController.probeHttpControl()` or `curl -s http://127.0.0.1:4617/status`

## Canonical identifiers

| Item | Value |
|------|-------|
| Player package (2.3.0+) | `com.thothassistant.daddylive` |
| Player package (legacy ≤2.0.0) | `ar.tvplayer.tv` |
| Companion package | `ar.tvplayer.companion` (premium licensing only — **not** LAN remote) |
| Launcher component | `com.thothassistant.daddylive/ar.tvplayer.tv.ui.MainActivity` (legacy: `ar.tvplayer.tv/.ui.MainActivity`) |
| StepDaddy bridge activity | `ar.tvplayer.tv.stepdaddy.StepDaddyBridgeActivity` |
| StepDaddy command receiver | `ar.tvplayer.tv.stepdaddy.StepDaddyCommandReceiver` |
| StepDaddy playlist | `http://127.0.0.1:3000/tivimate-playlist.m3u8` |
| Setup API | `GET http://127.0.0.1:3000/tivimate-setup` |
| Handshake API | `GET http://127.0.0.1:3000/tivimate-handshake` |
| Events ingest | `POST http://127.0.0.1:3000/tivimate-events` |
| Events buffer | `GET http://127.0.0.1:3000/tivimate-events?since=` |
| State proxy | `GET http://127.0.0.1:3000/tivimate-state` |
| Patched APK (build output) | `research/tivimate-apk/TiviMate-4.6.1-StepDaddy.apk` |

Build patched player: `cd research/tivimate-apk/stepdaddy-patch && ./build.sh`

## TiviMate install options

| Option | APK | Control surface |
|--------|-----|-----------------|
| **Daddy** | `TiviMate-4.6.1-StepDaddy.apk` | Full patch APIs below |
| **Mod** (4.6.1 ONN) | `tivimate-usb.apk` | Manual playlist only |
| **Official** (5.3.x) | `files.tivimate.com/tivimate.apk` | `TiviMateLauncher.launch()` + ADB keyevents |

## Gateway auto-launch settings

| Pref | Default | UI label |
|------|---------|----------|
| `launch_tivimate_on_ready` | on | Launch TiviMate when ready |
| `start_on_boot` | on | Start on boot |
| `auto_start_on_launch` | on | Auto-start server on app open |
| `tivimate_boot_tune_channel` | `51` | No dashboard UI yet — admin API / CLI |

Flow (`GatewayHud`): catalog ready → optional HUD → **+2.5 s** → `TiviMateLauncher.launch()` → poll `GET :4617/boot-tune/{n}` every 500 ms (max 24). Patch applies tune **+5 s** after `MainActivity.onResume`.

## StepDaddy patch APIs (4.6.1 Daddy only)

### Deep links (`stepdaddy://`)

| URI | Effect |
|-----|--------|
| `stepdaddy://setup` | Auto-add gateway playlist (default base `http://127.0.0.1:3000`) |
| `stepdaddy://setup?base=http://127.0.0.1:3000` | Setup with explicit gateway base |
| `stepdaddy://channel/{n}` | Tune channel number |
| `stepdaddy://stream?url={encoded}` | Open arbitrary HLS/stream URL |
| `stepdaddy://status` | Ensure loopback HTTP service is running |

### Broadcast actions (DaddyLive TV 2.3.0+; legacy fleet uses `ar.tvplayer.tv.action.*`)

| Action | Extras |
|--------|--------|
| `com.thothassistant.daddylive.action.STEPDADDY_SETUP` | `gateway_base` (optional) |
| `com.thothassistant.daddylive.action.STEPDADDY_TUNE` | `channel` and/or `channel_id` |
| `com.thothassistant.daddylive.action.STEPDADDY_STREAM` | `stream_url` |
| `com.thothassistant.daddylive.action.STEPDADDY_EPG` | — |
| `com.thothassistant.daddylive.action.STEPDADDY_HTTP_START` | Start loopback HTTP |
| `com.thothassistant.daddylive.action.STEPDADDY_HTTP_STOP` | Stop loopback HTTP |

### Loopback HTTP (port 4617)

Started automatically when patched MainActivity launches.

| Endpoint | Effect |
|----------|--------|
| `GET /status` | JSON: `ok`, `package`, `gateway`, `setupDone`, `port`, `patchVersion` |
| `GET /state` | Player snapshot: `setupDone`, `wizardPhase`, `currentChannelNo`, `isPlaying`, `playerMode`, `channelCount`, `patchVersion`, … |
| `GET /setup` | Start playlist auto-setup |
| `GET /tune/{n}` | Tune channel `n` |
| `GET /stream/{n}` | Open `…/tivimate-stream/{n}.m3u8` from saved gateway base |
| `GET /channel/up` | Channel up |
| `GET /channel/down` | Channel down |
| `GET /pause` | Pause playback |
| `GET /play` | Resume playback |
| `GET /search?q={name}` | Tune by channel name search |
| `GET /channels?limit=50` | List channels: `id`, `tvg_ch_no`, `name` |
| `GET /boot-tune/{n}` | Save boot channel pref; tune on next MainActivity resume (+5 s defer) |
| `GET /epg` | Open EPG overlay |
| `GET /launch` | Bring MainActivity to foreground |

```bash
curl -s http://127.0.0.1:4617/status
curl -s http://127.0.0.1:4617/state
curl -s 'http://127.0.0.1:4617/channels?limit=10'
adb shell am start -a android.intent.action.VIEW -d 'stepdaddy://channel/51' com.thothassistant.daddylive
```

## Gateway bidirectional API (patch ↔ gateway)

Patched TiViMate POSTs telemetry to the gateway; the gateway proxies player state for LAN/adb-forward clients.

### `POST /tivimate-events` (patch → gateway)

Patch sends `type` + `detail`; gateway normalizes to `event` + `message`.

```json
{
  "type": "CHANNEL_CHANGED",
  "channelNo": 36,
  "channelName": "ESPN",
  "channelId": 12345,
  "detail": "tuned via remote",
  "timestamp": 1719158400000,
  "patchVersion": "1.2.1-boot-tune-safe"
}
```

Event types: `PLAYBACK_STARTED`, `PLAYBACK_ERROR`, `CHANNEL_CHANGED`, `WIZARD_STEP`, `SETUP_COMPLETE`.

Gateway stores last **100** events in-memory. Response: `{"ok":true,"buffered":42}`

### `GET /tivimate-events?since={ms}`

```json
{"events":[...],"count":3,"since":1719158400000}
```

### `GET /tivimate-handshake`

```json
{
  "deviceId": "abc123",
  "gatewayVersion": "1.0.34",
  "bootChannel": null,
  "features": ["events", "state"],
  "gatewayBase": "http://127.0.0.1:3000",
  "eventsUrl": "http://127.0.0.1:3000/tivimate-events",
  "stateUrl": "http://127.0.0.1:3000/tivimate-state"
}
```

> `bootChannel` in handshake is **planned** to mirror `tivimateBootTuneChannel` — currently `null`.

### `GET /tivimate-state`

Proxies `GET http://127.0.0.1:4617/state`.

```json
{
  "reachable": true,
  "statusCode": 200,
  "state": {
    "setupDone": true,
    "currentChannelNo": 36,
    "patchVersion": "1.2.1-boot-tune-safe"
  }
}
```

`GET /health` includes `tivimateEvents.buffered`, `lastEvent`, `lastTimestamp`.

```bash
adb forward tcp:3000 tcp:3000
adb forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:3000/tivimate-handshake | jq .
curl -s http://127.0.0.1:3000/tivimate-state | jq .
curl -s http://127.0.0.1:4617/state | jq .
```

### Kotlin (gateway `TiviMateController`)

| Method | Purpose |
|--------|---------|
| `launch(context)` | Stock explicit MainActivity launch |
| `triggerSetup(context, gatewayBase?)` | Patched auto-setup |
| `tuneChannel(context, channelNumber)` | Patched channel tune |
| `setBootTuneChannel(context, channel)` | `GET /boot-tune/{n}` |
| `openStream(context, channelOrUrl)` | Patched stream URL or channel number |
| `openEpg(context)` | Patched EPG overlay |
| `probeHttpControl()` | `GET :4617/status` — detects patch |
| `probeState()` | `GET :4617/state` — parsed player snapshot |
| `getEvents(since?)` | Read gateway event ring buffer |
| `channelUp()` / `channelDown()` | `GET /channel/up` / `/channel/down` |
| `pause()` / `play()` | `GET /pause` / `/play` |
| `search(name)` | `GET /search?q=…` |
| `getChannels(limit?)` | `GET /channels?limit=…` |
| `probe(context)` | Install info + `httpControlReachable` |

## DaddyLive mirrors (gateway)

Default order in `DaddyLiveClient`: `dlhdBaseUrl` then `mirrorUrls`.

- Primary: `https://daddylive.org`
- Mirrors: `daddylive.org`, `daddylive.li`, `daddylive.eu`

Settings → Upstream. Failover is automatic; edit list if domains rotate.

## What works on stock TiViMate (automate these)

```bash
adb shell am start -n ar.tvplayer.tv/.ui.MainActivity
adb shell am force-stop ar.tvplayer.tv
adb shell input keyevent 82   # MENU (EPG)
adb shell input keyevent 12 8 66   # channel 51 + ENTER
```

## What does NOT work on stock TiViMate

- `ACTION_VIEW` with M3U playlist URL
- Deep link to channel / stream (patch required)
- `am start` on `SettingsActivity`, `PlaylistActivity` (not exported)
- Loopback HTTP :4617

## Debugging playback

```bash
BASE=http://$(adb shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1):3000
curl -s $BASE/health | jq '{version, channels}'
curl -s http://127.0.0.1:4617/status | jq '{patchVersion, setupDone}'
curl -s http://127.0.0.1:3000/tivimate-events | jq .
adb logcat -d | rg -iE "ExoPlayer|StepDaddyBridge|GatewayHud|Boot-tune" | tail -30
```

## Full RE reference

- `research/tivimate-apk/RE-DEEP-DIVE.md` — manifest, DexProtector, automation tiers
- `research/tivimate-apk/stepdaddy-patch/README.md` — patch operator docs
- Patch sources: `research/tivimate-apk/stepdaddy-patch/src/ar/tvplayer/tv/stepdaddy/`

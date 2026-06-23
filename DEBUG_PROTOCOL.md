# StepDaddy Gateway — Debug Protocol

Run this checklist when validating a release on FUSA (ONN stick) or any test device.

## Device setup

```bash
export DEV=FUSA2541006925
adb -s $DEV shell ip -4 addr show wlan0 | grep inet
```

## 1. Install & version

```bash
cd stepdaddy-android
./gradlew assembleDebug
adb -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $DEV shell dumpsys package com.thothassistant.stepdaddy.gateway.debug | grep versionName
curl -sf http://<device-ip>:3000/health | jq '.version, .providers, .topCategories'
```

Expected: `version` matches `BuildConfig.VERSION_NAME`, `providers.adult` ≥ 15 (DaddyLive adult sites).

## 2. Auto-start on launch

1. Force-stop app and server
2. Launch MainActivity from launcher
3. Within 30s: `curl -sf http://<ip>:3000/health` returns 200

```bash
adb -s $DEV shell am force-stop com.thothassistant.stepdaddy.gateway.debug
adb -s $DEV shell monkey -p com.thothassistant.stepdaddy.gateway.debug -c android.intent.category.LAUNCHER 1
sleep 25 && curl -sf http://192.168.1.157:3000/health | jq '.ok, .channels'
```

## 3. XXX Adult category in playlist

```bash
curl -sf http://<ip>:3000/tivimate-playlist.m3u8 | grep -c 'group-title="XXX Adult"'
```

Expected: ≥ 15 lines with `group-title="XXX Adult"`.

## 4. Channel logos

```bash
curl -sf http://<ip>:3000/tivimate-playlist.m3u8 -o /tmp/pl.m3u8
grep -oP 'tvg-logo="[^"]*"' /tmp/pl.m3u8 | grep -c '/logo/'
```

Expected: majority of DaddyLive rows use `/logo/` proxy URLs (not letter placeholders).

## 5. Playlist performance

```bash
time curl -sf --max-time 120 http://<ip>:3000/tivimate-playlist.m3u8 -o /dev/null
```

Expected: < 30s cold, < 10s warm (cached).

## 6. TiviMate launch toggle

With **Launch TiviMate when ready** enabled on dashboard:

```bash
adb -s $DEV logcat -c
adb -s $DEV shell am force-stop com.thothassistant.stepdaddy.gateway.debug
adb -s $DEV shell monkey -p com.thothassistant.stepdaddy.gateway.debug -c android.intent.category.LAUNCHER 1
sleep 90
adb -s $DEV logcat -d | rg "GatewayHud|TiviMateLauncher|Boot-tune"
```

Expect: `Launched TiviMate` after catalog ready (+2.5 s); `Boot-tune N saved via patch HTTP` when Daddy patch installed.

## 6b. Boot-tune / patch version (Daddy mod)

```bash
adb -s $DEV forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/status | jq '{patchVersion, setupDone}'
# Require patchVersion >= 1.2.1-boot-tune-safe for cold-boot stability
./scripts/fusa-first-stream-timer.sh   # optional end-to-end timing
```

## 7. Boot reliability

```bash
./scripts/fusa-boot-test.sh
```

## 8. Logcat tags

```bash
adb -s $DEV logcat -d -t 30m | rg "GatewayServer|ServerService|PlaylistCache|DaddyLive|LogoResolver|TiviMateLauncher"
```

## 9. UI / DPAD

- MainActivity: status panel top-right shows health, providers, categories
- Settings: port, mirrors, supplements editable
- All buttons focusable with D-pad (min 52dp height)

## 11. iptv-org supplement EPG

```bash
# Regenerate bundled guide (dev machine, ~10–20 min for 4 sites)
./scripts/grab-iptv-org-fast-epg.sh

# After rebuild + sideload, verify programmes for Pluto/Plex channels
curl -sf http://<ip>:3000/epg.xml | grep -c 'ABCNewsLive.us@SD'
```

Expected: iptv-org supplement channels with `tvg-id` like `ABCNewsLive.us@SD` show guide data in TiviMate.

**Note:** Samsung, FireTV, Roku iptv-org streams often lack `tvg-id` or have no iptv-org/epg site — EPG covers Pluto/Plex/Xumo/Distro only.

See `.cursor/agents/README.md` — start with `android-debug-orchestrator.md`.

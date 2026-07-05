# Installation

StepDaddy Gateway runs on **Android TV**, **Google TV sticks** (e.g. ONN), and Android phones/tablets with API 24+.

For the full two-app flow see [ONN-QUICK-START.md](ONN-QUICK-START.md) and [TWO-APP.md](TWO-APP.md).

## Requirements

| Item | Minimum |
|------|---------|
| Android | 7.0 (API 24) |
| Storage | ~50 MB app + EPG cache (varies) |
| Network | Internet for upstream channel/EPG fetch |
| Client app | TiviMate Daddy, mod, official, or any M3U/XMLTV player |

---

## Install StepDaddy Gateway

### Debug build (development)

```bash
cd /path/to/stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **Package:** `com.thothassistant.stepdaddy.gateway.debug`

Debug and release share port 3000. Installing debug alongside release is supported for testing, but only one should run at a time — the app stops the sibling gateway service on startup. For a clean device, uninstall release before installing debug:

```bash
adb uninstall com.thothassistant.stepdaddy.gateway
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build (production)

```bash
./scripts/build-release.sh
```

Signed output paths are documented in [RELEASE.md](RELEASE.md).

- **Package:** `com.thothassistant.stepdaddy.gateway`

### Enable unknown sources

On Android TV: **Settings → Security & restrictions → Unknown sources** → allow your file manager or installer.

---

## Install TiviMate — three options

**DaddyLive TV** (2.3.0+) uses package `com.thothassistant.daddylive` and coexists with stock TiviMate. **Mod** and **official** use `ar.tvplayer.tv` — only one `ar.tvplayer.tv` app at a time. Legacy Daddy (≤2.0.0 published) also used `ar.tvplayer.tv`.

| Option | APK source | Package | StepDaddy control | Best for |
|--------|------------|---------|-------------------|----------|
| **DaddyLive TV** (recommended) | `research/tivimate-apk/TiviMate-4.6.1-StepDaddy.apk` | `com.thothassistant.daddylive` | Auto-setup, tune, `:4617` HTTP, bidirectional events | ONN fleet sticks |
| **Mod** (4.6.1 ONN) | `research/tivimate-apk/tivimate-usb.apk` | `ar.tvplayer.tv` | Manual playlist URLs only | RE / testing without patch |
| **Official** (5.3.x) | `https://files.tivimate.com/tivimate.apk` | `ar.tvplayer.tv` | Gateway playlist only; no tune API | Play Store parity, premium |

### Build & install DaddyLive TV

```bash
cd research/tivimate-apk/stepdaddy-patch
./build.sh

DEV=<serial>
adb -s $DEV uninstall com.thothassistant.daddylive || true
# Legacy fleet (≤2.0.0): adb -s $DEV uninstall ar.tvplayer.tv || true
adb -s $DEV install -r ../TiviMate-4.6.1-StepDaddy.apk
```

**Gateway must be running on `127.0.0.1:3000` before first TiviMate launch** so auto-setup can fetch `/tivimate-setup`.

### Install mod or official

```bash
adb -s $DEV uninstall ar.tvplayer.tv || true
adb -s $DEV install -r research/tivimate-apk/tivimate-usb.apk
# or
curl -sL https://files.tivimate.com/tivimate.apk -o /tmp/tivimate.apk
adb -s $DEV install -r /tmp/tivimate.apk
```

Add playlist URLs manually in TiviMate (see [TUTORIAL.md](TUTORIAL.md)).

### Signature conflicts

| Error | Fix |
|-------|-----|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall com.thothassistant.daddylive` (or `ar.tvplayer.tv` for mod/official/legacy) then reinstall |
| Switching DaddyLive ↔ mod/official | Uninstall conflicting `ar.tvplayer.tv` app first (different signing keys) |

---

## First launch (Gateway)

1. Open **StepDaddy Gateway** from the TV launcher.
2. Grant **notifications** when prompted (API 33+).
3. Accept **battery optimization** exemption (recommended on TV sticks).
4. Recommended toggles:
   - **Start on boot** — auto-start after reboot
   - **Launch TiviMate when ready** — open player when catalog loads (requires TiviMate installed)
5. Press **Start server** (or rely on auto-start on launch).
6. Wait for channel preload (~**60–80 s** on cold boot).

For Daddy mod: first TiviMate launch runs auto-setup. For manual clients: copy URLs from dashboard.

---

## ADB grants (recommended for TV sticks)

```bash
PKG=com.thothassistant.stepdaddy.gateway          # release
# PKG=com.thothassistant.stepdaddy.gateway.debug  # debug

adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
```

---

## DaddyLive mirrors (optional)

Defaults (Settings → Upstream):

- Primary: `https://daddylive.org`
- Mirrors: `https://daddylive.org,https://daddylive.li,https://daddylive.eu`

Change if upstream rotates domains. Active mirror appears in `/health` after channel fetch.

---

## Legacy Termux app conflict

The older Python gateway (`com.nova.stepdaddylivehd`) also binds port **3000**. Uninstall it before using the native gateway:

```bash
adb shell am force-stop com.nova.stepdaddylivehd
adb uninstall com.nova.stepdaddylivehd
```

---

## Verify installation

```bash
IP=$(adb shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1)
curl -s "http://${IP}:3000/health" | jq '{version, channels, epgReady}'
```

Expect HTTP 200 with `"channels"` > 0 after warm-up.

**With TiviMate Daddy:**

```bash
adb forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/status | jq '{patchVersion, setupDone}'
```

### In-app updates (About screen)

Step-by-step examples (TiviMate **Settings → About → Check for new version**, Gateway About, ADB): [`docs/TIVIMATE-UPDATE-EXAMPLES.md`](../../docs/TIVIMATE-UPDATE-EXAMPLES.md).

Open **About** from the dashboard management card or **Settings → About**. The gateway checks GitHub for the latest `tivimate-daddy-v*` release on `thothassistantai-web/tivimate-daddy`, compares your installed `patchVersion` from `:4617/status`, and offers **Update now** when a newer manifest is published. Release assets include `update-manifest.json` with `versionCode`, `versionName`, and `apkUrl`.

---

## Play Store

Gateway not yet published. TiviMate official is on Play Store; Daddy patch is sideload-only.

See [PLAY_STORE_LISTING.md](../PLAY_STORE_LISTING.md) for planned gateway store listing.

---

## Related platforms

| Platform | Location |
|----------|----------|
| Linux / Docker gateway | [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD) |
| Termux mobile (legacy) | [StepDaddyLiveHD-Mobile](https://github.com/thothassistantai-web/StepDaddyLiveHD-Mobile) |
| TiviMate Daddy patch docs | [tivimate-daddy](https://github.com/thothassistantai-web/tivimate-daddy) |

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

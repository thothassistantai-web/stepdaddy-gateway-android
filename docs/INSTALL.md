# Installation

StepDaddy Gateway runs on **Android TV**, **Google TV sticks** (e.g. ONN), and Android phones/tablets with API 24+.

## Requirements

| Item | Minimum |
|------|---------|
| Android | 7.0 (API 24) |
| Storage | ~50 MB app + EPG cache (varies) |
| Network | Internet for upstream channel/EPG fetch |
| Client app | TiviMate or any M3U/XMLTV IPTV player (optional) |

## Install from APK (sideload)

### Debug build (development)

```bash
cd /path/to/stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **Package:** `com.thothassistant.stepdaddy.gateway.debug`

### Release build (production)

```bash
./scripts/build-release.sh
```

Signed output paths are documented in [RELEASE.md](RELEASE.md). Unsigned fallback: `app/build/outputs/apk/release/app-release-unsigned.apk`.

- **Package:** `com.thothassistant.stepdaddy.gateway`

### Enable unknown sources

On Android TV: **Settings → Security & restrictions → Unknown sources** → allow your file manager or installer.

## First launch

1. Open **StepDaddy Gateway** from the TV launcher.
2. Grant **notifications** when prompted (API 33+).
3. Accept **battery optimization** exemption (recommended on TV sticks).
4. Tap **Start server** (or enable **Start on boot** for automatic startup).
5. Wait for channel preload (~30–80s on cold boot).
6. Copy playlist and EPG URLs into TiviMate (see [TUTORIAL.md](TUTORIAL.md)).

## ADB grants (recommended for TV sticks)

```bash
PKG=com.thothassistant.stepdaddy.gateway          # release
# PKG=com.thothassistant.stepdaddy.gateway.debug  # debug

adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
```

## Legacy Termux app conflict

The older Python gateway (`com.nova.stepdaddylivehd`) also binds port **3000**. Uninstall it before using the native gateway:

```bash
adb shell am force-stop com.nova.stepdaddylivehd
adb uninstall com.nova.stepdaddylivehd
```

## Verify installation

```bash
IP=$(adb shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1)
curl -s "http://${IP}:3000/health" | head -c 500
```

Expect HTTP 200 with `"channels"` > 0 after warm-up.

## Play Store

Not yet published. See [PLAY_STORE_LISTING.md](../PLAY_STORE_LISTING.md) for planned store listing.

## Related platforms

| Platform | Location |
|----------|----------|
| Linux / Docker gateway | [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD) |
| Termux mobile (legacy) | [StepDaddyLiveHD-Mobile](https://github.com/thothassistantai-web/StepDaddyLiveHD-Mobile) |
| Linux source (dev) | `~/Programs/stepdaddy-web` (symlink from `~/livehd/current/stepdaddy-web`) |

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

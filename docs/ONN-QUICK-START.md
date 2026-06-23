# ONN stick quick start

Fastest path to live TV on a **Google TV / ONN streaming stick** (tested on `FUSA2541006925`).

**Time budget:** ~5 min hands-on + **60–80 s** first boot for channel preload.

---

## What you need

| Item | Notes |
|------|-------|
| ONN 4K or Full HD stick | Android TV 11+, API 24+ |
| Wi‑Fi | Upstream fetch requires internet |
| ADB (optional) | USB or wireless — speeds up install |
| Two APKs | Gateway + TiviMate Daddy (recommended) |

---

## 1. Install Gateway

```bash
cd stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug

DEV=<your-serial>   # adb devices
adb -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk
```

Or install a release APK from `release/stepdaddy-gateway-*.apk`.

**ADB grants (recommended):**

```bash
PKG=com.thothassistant.stepdaddy.gateway.debug   # or .gateway for release
adb -s $DEV shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb -s $DEV shell appops set $PKG SYSTEM_ALERT_WINDOW allow
```

---

## 2. Install TiviMate Daddy

```bash
# Install from GitHub Releases (recommended)
# https://github.com/thothassistantai-web/tivimate-daddy/releases/latest

# Or build from source (monorepo research tree or tivimate-daddy repo)
cd research/tivimate-apk/stepdaddy-patch   # or clone tivimate-daddy
./build.sh

# Install — uninstall old TiviMate first if signatures differ
adb -s $DEV uninstall ar.tvplayer.tv || true
adb -s $DEV install -r ../TiviMate-4.6.1-StepDaddy.apk
```

See [INSTALL.md](INSTALL.md) for **mod** vs **official** alternatives.

---

## 3. Configure Gateway (one time)

1. Open **StepDaddy Gateway** from the launcher.
2. Enable:
   - **Start on boot** — gateway after every reboot
   - **Launch TiviMate when ready** — auto-open player when catalog loads
3. Press **Start server** (or rely on auto-start).
4. Wait for HUD: *"StepDaddy Gateway running — N channels"* (~60–80 s on cold boot).

Default boot-tune channel is **51** (ESPN-class slot in DaddyLive ordering). Change via admin API / CLI if needed — dashboard UI pending.

---

## 4. First TiviMate launch

**Gateway must be listening on `127.0.0.1:3000` before first TiviMate open.**

```bash
adb -s $DEV shell am start -n ar.tvplayer.tv/.ui.MainActivity
```

The Daddy patch will:

1. Start HTTP control on port **4617**
2. Fetch `http://127.0.0.1:3000/tivimate-setup`
3. Walk the playlist wizard (auto-advances URL step)
4. After reboot + auto-launch: tune saved boot channel after **~7.5 s** total defer (2.5 s gateway + 5 s patch)

---

## 5. Verify

```bash
IP=$(adb -s $DEV shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1)

# Gateway
curl -s "http://${IP}:3000/health" | jq '{version, channels, epgReady}'

# Patch (device-local; forward for host curls)
adb -s $DEV forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/status | jq '{patchVersion, setupDone}'

# Bidirectional
curl -s "http://${IP}:3000/tivimate-handshake" | jq .
```

Play a channel in TiviMate. First stream may take **3–15 s** (upstream cold resolve).

---

## 6. Reboot test (optional)

```bash
cd stepdaddy-android
ADB_SERIAL=$DEV ./scripts/fusa-boot-test.sh
```

Expect: gateway health **200** within ~60 s, overlay banner, TiviMate auto-launch if enabled, boot-tune without crash (`patchVersion` ≥ `1.2.1-boot-tune-safe`).

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Empty TiviMate playlist | Start gateway first; force setup: `adb shell am broadcast -a ar.tvplayer.tv.action.STEPDADDY_SETUP` |
| TiviMate crash on boot | Upgrade to patch `1.2.1-boot-tune-safe` |
| Port 3000 in use | Uninstall legacy Termux `com.nova.stepdaddylivehd` |
| No overlay banner | `appops set … SYSTEM_ALERT_WINDOW allow` |

Full guide: [TROUBLESHOOTING.md](TROUBLESHOOTING.md) · [TWO-APP.md](TWO-APP.md)

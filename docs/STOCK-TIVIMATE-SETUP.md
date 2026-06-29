# Stock TiviMate + StepDaddy Gateway

**Recommended ship path** — use stock (or store) TiviMate with the StepDaddy Gateway app. No patched wizard required.

Tested on **ONN / Google TV sticks** (e.g. `FUSA2541006925`). Gateway **2.3.3-debug** reaches readiness in **~30–60 s** with **~4850+ channels** (full DaddyLive catalog + supplements, MOJ removed).

---

## What you need

| Item | Notes |
|------|-------|
| Android TV device | ONN 4K/Full HD or similar, API 24+ |
| Wi‑Fi | Upstream fetch requires internet |
| **StepDaddy Gateway** APK | `com.thothassistant.stepdaddy.gateway` (or `.gateway.debug`) |
| **Stock TiviMate** | `ar.tvplayer.tv` from Play Store or sideloaded premium build |
| ADB (optional) | Speeds up install and verification |

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

**Recommended grants:**

```bash
PKG=com.thothassistant.stepdaddy.gateway.debug   # or .gateway for release
adb -s $DEV shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb -s $DEV shell appops set $PKG SYSTEM_ALERT_WINDOW allow
```

---

## 2. Start Gateway and wait for catalog

1. Open **StepDaddy Gateway** from the launcher.
2. Enable **Start on boot** (optional but recommended).
3. Press **Start server** if it is not already running.
4. Wait until the HUD shows something like *"StepDaddy Gateway running — N channels"*.

**Timing:** allow **30–60 seconds** on cold boot (FUSA ~33 s with warm disk cache). Do not add the playlist in TiviMate until the channel count is non-zero.

**Verify from a host (optional):**

```bash
adb -s $DEV forward tcp:3000 tcp:3000
curl -s http://127.0.0.1:3000/health?lite=1 | jq '{ok, starting, channels, supplementChannels}'
```

Expect `ok: true`, `starting: false`, and `channels + supplementChannels > 0`.

---

## 3. Add playlist in TiviMate (manual)

In **stock TiviMate**:

1. **Settings → Playlists → Add playlist**
2. Choose **M3U playlist**
3. Enter the playlist URL (canonical user path):

   | Where TiviMate runs | Playlist URL |
   |---------------------|--------------|
   | Same device as Gateway | `http://127.0.0.1:3000/tivimate.m3u` |
   | Another device on LAN | `http://<gateway-lan-ip>:3000/tivimate.m3u` |

   Legacy URLs (`/tivimate-setup-playlist.m3u8`, `/tivimate-playlist.m3u8`) still work. The setup path is diagnostic-only (50-channel bootstrap for FUSA probes).

   To find the LAN IP:

   ```bash
   adb -s $DEV shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1
   ```

4. Confirm and let TiviMate import. First import may take **1–3 minutes** for the full catalog (~4800+ channels).

Default fresh-install gateway settings serve the **full catalog** on `/tivimate.m3u` (and `/tivimate-playlist.m3u8`). `/tivimate-setup-playlist.m3u8` is capped at 50 channels for diagnostics only.

---

## 4. EPG (optional)

If gateway EPG is ready, add the XMLTV URL from setup:

```bash
curl -s http://127.0.0.1:3000/tivimate-setup | jq '{epg, playlist}'
```

In TiviMate: **Settings → EPG → Add EPG source** and paste the `epg` URL (or use the value shown in gateway Settings / admin).

EPG may lag catalog readiness by ~20 s on cold boot (`epgReady` can be `false` at first gate pass — that is normal).

---

## 5. Verify playback

1. Open the live TV guide in TiviMate — groups should populate.
2. Tune a known channel (e.g. ESPN-class slot).
3. First stream may take **3–15 s** (upstream cold resolve).

```bash
# Gateway health
curl -s "http://127.0.0.1:3000/health?lite=1" | jq '{version, channels, epgReady}'

# Setup payload (playlist + EPG hints)
curl -s "http://127.0.0.1:3000/tivimate-setup" | jq .
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Empty playlist in TiviMate | Start gateway first; wait until `/health` shows channels > 0 |
| `127.0.0.1` fails from another device | Use the stick's LAN IP instead |
| Port 3000 in use | Uninstall legacy Termux `com.nova.stepdaddylivehd` |
| Very slow cold start (>90 s) | Upgrade to gateway ≥ 2.3.3 (async CSV init, 60 s component timeout) |
| No overlay / notifications | `appops set … SYSTEM_ALERT_WINDOW allow` |

See also: [ONN-QUICK-START.md](ONN-QUICK-START.md) (patched TiviMate path), [TROUBLESHOOTING.md](TROUBLESHOOTING.md), [TWO-APP.md](TWO-APP.md).

---

## Patched TiviMate (experimental)

The **TiviMate Daddy** patched player (`com.thothassistant.daddyliveTV`) automates setup but import is still flaky on some devices. For production installs, prefer **this stock TiviMate + gateway** flow until patch import is stable.

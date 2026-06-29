# Troubleshooting

Common issues when running StepDaddy Gateway + TiviMate on Android TV / ONN sticks.

See also [TWO-APP.md](TWO-APP.md) for deployment modes and version checks.

## Quick diagnostics

```bash
DEV=<your-adb-serial>
IP=$(adb -s $DEV shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1)

curl -s -m 5 "http://${IP}:3000/health" | jq '{version, channels, starting, epgReady}'
curl -s -o /dev/null -w "%{http_code}" "http://${IP}:3000/tivimate-playlist.m3u8"
curl -s -o /dev/null -w "%{http_code}" "http://${IP}:3000/epg.xml"

# TiviMate Daddy only
adb -s $DEV forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/status | jq '{patchVersion, setupDone, gateway}'
```

| `/health` signal | Meaning |
|------------------|---------|
| `"starting": true`, `"channels": 0` | Disk cache loading — wait 10–30s |
| `"channels": 1100+` (example) | Gateway ready |
| Connection refused | Server not running or wrong IP |
| Old `"version":"0.1.0-beta.11"` | Legacy Termux app on port 3000 |

| `patchVersion` | Meaning |
|----------------|---------|
| `1.2.1-boot-tune-safe` | Current — deferred boot-tune (crash fix) |
| `1.2.0-boot-fast` | Faster tune; may crash on cold boot — upgrade |
| `1.1.0-bidir` | Bidirectional API; no safe boot-tune — upgrade |
| missing / connection refused | Stock TiviMate or patch HTTP not started (open TiviMate) |

---

## Server won't start

| Symptom | Fix |
|---------|-----|
| Port in use | Uninstall legacy `com.nova.stepdaddylivehd` Termux gateway |
| Immediate crash | `adb logcat -d \| rg GatewayServer\|ServerService` |
| "Starting gateway…" forever | Check internet; DaddyLive mirrors may be down — try Settings mirrors |
| Notification blocked | Grant `POST_NOTIFICATIONS`; FGS may be killed on API 33+ |

---

## Boot auto-start fails

1. **Start on boot** toggle ON in app.
2. Open app once after install (runtime permissions).
3. **Settings → Apps → StepDaddy Gateway → Battery** → Unrestricted.
4. Grant overlay: `adb shell appops set <package> SYSTEM_ALERT_WINDOW allow`
5. Do not `force-stop` before reboot testing — use brief launch + HOME, or `am kill`.

See `scripts/fusa-boot-test.sh` for automated boot verification.

**Expected timing:** HTTP **20–60 s**; full channels **60–80 s** cold on ONN release builds.

---

## Launch TiviMate when ready

| Symptom | Fix |
|---------|-----|
| TiviMate never opens | Enable toggle on dashboard; confirm DaddyLive TV (`com.thothassistant.daddylive`) or legacy Daddy (`ar.tvplayer.tv`) installed |
| Opens too early / empty guide | Normal on first seconds — patch boot-tune waits +5 s after resume |
| Opens every time you open Gateway | Only once per boot (`tivimateLaunchedThisBoot`) |
| Boot-tune wrong channel | Default is 51; change `tivimate_boot_tune_channel` via admin API (UI pending) |

Logcat: `adb logcat -d | rg GatewayHud\|TiviMateLauncher\|Boot-tune`

---

## TiviMate crash on cold boot (boot-tune)

**Symptom:** TiviMate dies shortly after auto-launch following stick reboot.

**Cause:** Patch `1.2.0-boot-fast` (or older) tuned before Room/SQLite WAL recovery finished.

**Fix:**

1. Rebuild/install patch with `patchVersion` **`1.2.1-boot-tune-safe`**
2. Confirm: `curl -s http://127.0.0.1:4617/status | jq .patchVersion`
3. Gateway side already waits 2.5 s before launch + polls `/boot-tune`

```bash
cd research/tivimate-apk/stepdaddy-patch && ./build.sh
adb install -r ../TiviMate-4.6.1-StepDaddy.apk
```

---

## TiviMate — empty playlist or EPG

| Cause | Fix |
|-------|-----|
| Gateway not ready | Wait for HUD / health `channels` > 0 |
| Gateway started after TiviMate | Restart TiviMate or broadcast `com.thothassistant.daddylive.action.STEPDADDY_SETUP` (legacy: `ar.tvplayer.tv.action.STEPDADDY_SETUP`) |
| Wrong URL | Use `127.0.0.1:3000` on same device |
| Stock TiviMate | Add URLs manually — no auto-setup |
| EPG 503 | First EPG build up to ~5 min; stale cache returns 200 when available |

---

## TiviMate Daddy HTTP :4617 unreachable

| Cause | Fix |
|-------|-----|
| TiviMate not in foreground | Open TiviMate — service starts on `MainActivity` |
| Stock/official build | Install Daddy patch APK |
| Wrong package | `adb shell pm path com.thothassistant.daddylive` (legacy: `ar.tvplayer.tv`) |

```bash
adb forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/status
adb -s $DEV shell 'ss -lntp | grep 4617'
```

---

## Bidirectional API issues

| Symptom | Fix |
|---------|-----|
| `GET /tivimate-state` 502 | TiviMate not running or patch HTTP down |
| Empty `/tivimate-events` | Tune/play a channel to generate events |
| Events stop after force-stop | Normal — in-memory ring buffer resets with gateway process |

```bash
curl -s http://127.0.0.1:3000/tivimate-handshake | jq .
curl -s http://127.0.0.1:3000/tivimate-state | jq .
```

---

## Streams buffer or fail

| Cause | Fix |
|-------|-----|
| Upstream outage | DaddyLive mirrors rotate — edit Settings → mirrors |
| Cold channel | First play slow (resportz chain); normal |
| CDN geo block | Upstream limitation |
| Special Events in Movies | Upgrade gateway ≥ 1.0.34 (HLS wrapper fix for guides) |

---

## EPG missing or wrong guide data

1. Check health: `epgReady`, `epgProgrammeCount`, `epgAgeSeconds`.
2. Gateway EPG toggle in Settings (external vs on-device merge).
3. Large first download (~55 MB US locals) — wait on slow sticks.

---

## High memory / ANR on boot

Channel parse runs off main thread in current builds. Reinstall latest APK if boot loops persist.

---

## DaddyLive mirror failures

1. Settings → **DaddyLive URL** and **Mirror URLs** (comma-separated).
2. Defaults: `daddylive.org`, `daddylive.li`, `daddylive.eu`.
3. `/health` shows active upstream after successful fetch.

---

## Updates not detected

| Check | Action |
|-------|--------|
| Manifest URL empty | Set in Settings or build-time default |
| GitHub rate limit | Use release asset `update-manifest.json` |
| Drive folder | Must be publicly readable |

---

## ADB install errors

| Error | Fix |
|-------|-----|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall <package>` then reinstall |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Uninstall or bump `versionCode` |
| TiviMate signature clash | Uninstall before switching Daddy/mod/official |

---

## Logs

```bash
adb logcat -d -t 10m | rg "GatewayServer|ServerService|BootReceiver|GatewayHud|StepDaddyBridge|TiviMateEvent"
```

---

## Still stuck?

1. [INSTALL.md](INSTALL.md) — permissions and legacy conflict
2. [ONN-QUICK-START.md](ONN-QUICK-START.md) — clean setup sequence
3. [stepdaddy-patch README](../../research/tivimate-apk/stepdaddy-patch/README.md) — patch API
4. GitHub issue with `/health` JSON, `patchVersion`, Android version, logcat excerpt

---

## Educational / legal

Upstream sources change without notice. See [DISCLAIMER.md](../DISCLAIMER.md).

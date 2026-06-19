# Troubleshooting

Common issues when running StepDaddy Gateway on Android TV / ONN sticks.

## Quick diagnostics

```bash
DEV=<your-adb-serial>
IP=$(adb -s $DEV shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+' | head -1)

curl -s -m 5 "http://${IP}:3000/health"
curl -s -o /dev/null -w "%{http_code}" "http://${IP}:3000/tivimate-playlist.m3u8"
curl -s -o /dev/null -w "%{http_code}" "http://${IP}:3000/epg.xml"
```

| `/health` signal | Meaning |
|------------------|---------|
| `"starting": true`, `"channels": 0` | Disk cache still loading — wait 10–30s |
| `"channels": 1140` (example) | Gateway ready |
| Connection refused | Server not running or wrong IP |
| Old `"version":"0.1.0-beta.11"` | Legacy Termux app still on port 3000 |

## Server won't start

| Symptom | Fix |
|---------|-----|
| Port in use | Uninstall legacy `com.nova.stepdaddylivehd` Termux gateway |
| Immediate crash | `adb logcat -d \| rg GatewayServer\|ServerService` |
| "Starting gateway…" forever | Check internet; upstream mirrors may be down |
| Notification blocked | Grant `POST_NOTIFICATIONS`; FGS may be killed on API 33+ |

## Boot auto-start fails

1. **Start on boot** toggle ON in app.
2. Open app once after install (runtime permissions).
3. **Settings → Apps → StepDaddy Gateway → Battery** → Unrestricted.
4. Grant overlay: `adb shell appops set <package> SYSTEM_ALERT_WINDOW allow`
5. Do not `force-stop` before reboot testing — use brief launch + HOME, or `am kill`.

See `scripts/fusa-boot-test.sh` for automated boot verification.

## TiviMate — empty playlist or EPG

| Cause | Fix |
|-------|-----|
| Server not ready | Wait for banner / health `channels` > 0 |
| Wrong URL | Use `127.0.0.1:3000` on same device |
| Stale playlist | TiviMate → update playlist |
| EPG 503 | First EPG build can take up to ~5 min; stale cache still returns 200 when available |

## Streams buffer or fail

| Cause | Fix |
|-------|-----|
| Upstream outage | DaddyLive mirrors rotate; retry later |
| Cold channel | First play is slow (resportz chain); normal |
| CDN geo block | Not fixable in gateway — upstream limitation |
| `/content/` proxy errors | Check logcat `StreamRoutes`; compare with Linux gateway parity |

## EPG missing or wrong guide data

1. Check health: `epgReady`, `epgProgrammeCount`, `epgAgeSeconds`.
2. Refresh mapping asset: `./scripts/export-epg-mapping.sh` (dev builds).
3. iptv-org EPG toggle in Settings if using supplement feeds.
4. Large first download (~55 MB US locals feed) — wait on slow sticks.

## High memory / ANR on boot

Fixed in recent builds (channel parse moved off main thread). Reinstall latest APK if you see boot loops or "App isn't responding".

## Updates not detected

| Check | Action |
|-------|--------|
| Manifest URL empty | Set in Settings or `DEFAULT_UPDATE_MANIFEST_URL` at build time |
| GitHub rate limit | Use release asset `update-manifest.json` |
| Drive folder | Must be publicly readable; see [GITHUB-SETUP-NEEDED.md](GITHUB-SETUP-NEEDED.md) |

## ADB install errors

| Error | Fix |
|-------|-----|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall <package>` then reinstall |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Uninstall or bump `versionCode` |
| Unauthorized device | Accept RSA fingerprint on TV |

## Logs

```bash
adb logcat -d -t 10m | rg "GatewayServer|ServerService|BootReceiver|LightEpg"
```

## Still stuck?

1. [INSTALL.md](INSTALL.md) — permissions and legacy conflict
2. [ARCHITECTURE.md](../ARCHITECTURE.md) — expected behavior
3. Open a GitHub issue with `/health` JSON, Android version, and logcat excerpt (no secrets)

## Educational / legal

Upstream sources change without notice. This app aggregates third-party metadata — see [DISCLAIMER.md](../DISCLAIMER.md).

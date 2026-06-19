# Screenshot & graphics checklist

Assets for Google Play Console, GitHub README, and TV store listings.

## Required dimensions (Google Play)

| Asset | Size | Format | Status |
|-------|------|--------|--------|
| App icon | 512×512 | PNG 32-bit | [ ] Export from `@mipmap/ic_launcher` |
| Feature graphic | 1024×500 | PNG/JPG | [ ] Create — dashboard + logo composite |
| Phone screenshots | 2–8, min 320px short side | PNG/JPG | [ ] Optional (phone layout) |
| **TV screenshots** | 1920×1080 or 1280×720 | PNG/JPG | [x] Captured in `docs/screenshots/` |
| TV banner | 1280×720 | PNG | [ ] From `@drawable/tv_banner` |

## Recommended TV screenshots (capture order)

Capture on ONN stick or emulator at **1080p** via `adb exec-out screencap -p`.

| # | Screen | What to show | File name suggestion |
|---|--------|--------------|----------------------|
| 1 | Main dashboard | Server running, channel count, playlist card | `01-dashboard.png` |
| 2 | Install apps | TV catalog with D-pad focus | `02-install-apps.png` |
| 3 | Settings | Updates, toggles, version info | `03-settings.png` |
| 4 | Player tab | Embedded player on dashboard | `04-player-tab.png` |
| 5 | QR dialog | Remote setup QR codes | `05-qr-dialog.png` |
| 6 | Startup overlay | Home-screen banner "Ready for TiviMate" | `02-boot-overlay.png` |
| 7 | Channel browser | Sidebar + channel list | `05-channel-browser.png` |
| 8 | Fullscreen player | Live playback (generic channel) | `06-player.png` |
| 9 | EPG card | EPG status / programme count | `07-epg-status.png` |
| 10 | Updates | In-app update screen (optional) | `08-updates.png` |

## Capture commands

```bash
DEV=FUSA2541006925   # or emulator serial
OUT=docs/store-assets
mkdir -p "$OUT"

adb -s $DEV exec-out screencap -p > "$OUT/01-dashboard-running.png"
# Navigate on device, repeat for each screen
```

### Emulator (Android TV)

```bash
# Android Studio → Device Manager → Android TV (1080p)
adb -s emulator-5554 exec-out screencap -p > docs/store-assets/emulator-dashboard.png
```

## Visual guidelines

- Use **dark theme** screenshots (matches `Theme.StepDaddyGateway`)
- Hide personal Wi-Fi names / LAN IPs — use `10.0.2.2` or blur in post
- Show **real channel count** (credibility) but avoid copyrighted network logos in feature graphic if possible
- Consistent 16:9 framing for TV listings

## Feature graphic ideas

- Left: app icon + "StepDaddy Gateway"
- Right: stylized TV stick + TiviMate logo placeholder (do not use trademarked logos without permission)
- Tagline: *Self-hosted IPTV gateway for Android TV*

## GitHub README

TV screenshots #1–#5 are committed under `docs/screenshots/` and embedded in `README.md` (max width follows GitHub default).

## Pre-submission checklist

- [ ] All PNGs under 8 MB each
- [ ] No ADB serial or home address visible
- [ ] LEGAL/DISCLAIMER accessible from app Settings or About
- [ ] Screenshots match **release** build (`com.thothassistant.stepdaddy.gateway`)
- [ ] Feature graphic and TV banner uploaded to Play Console
- [ ] At least **2 TV screenshots** uploaded (Google minimum)

## Storage location

```
stepdaddy-android/docs/screenshots/   ← README gallery (committed)
stepdaddy-android/docs/store-assets/  ← optional Play Console extras (gitignored)
```

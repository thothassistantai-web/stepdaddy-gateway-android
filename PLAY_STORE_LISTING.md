# Google Play Store listing (draft)

**App name:** StepDaddy Gateway  
**Package:** `com.thothassistant.stepdaddy.gateway`  
**Category:** Tools (or Entertainment → consider policy review for IPTV aggregators)  
**Status:** Draft — not submitted

---

## Short description (80 chars max)

```
Local IPTV gateway for Android TV — M3U playlists & EPG for TiviMate on-device.
```

## Full description (4000 chars max)

```
StepDaddy Gateway turns your Android TV stick or phone into a self-hosted IPTV gateway — no Termux, no command line.

WHAT IT DOES
• Runs a lightweight HTTP server on your device (port 3000)
• Builds TiviMate-compatible M3U playlists and XMLTV EPG
• Auto-starts after reboot — ready for your IPTV client
• Works with TiviMate, VLC, and other M3U/XMLTV players on the same device

PERFECT FOR ANDROID TV & ONN STICKS
• TV-optimized dashboard with D-pad navigation
• Copy-paste or QR-code playlist setup
• Home-screen status overlay when the gateway is running
• Battery and boot optimizations for always-on sticks

FEATURES
• 1000+ live channel metadata from configurable upstream sources
• Light EPG with US sports, locals, and iptv-org enrichment
• Built-in channel browser and preview player
• Health endpoint for home automation and monitoring
• Optional in-app updates via GitHub Releases

IMPORTANT — PLEASE READ
StepDaddy Gateway is an educational, self-hosted aggregator. It does NOT host video content. Streams are resolved from third-party sources at runtime. You are responsible for complying with laws in your region. See in-app legal notice.

REQUIREMENTS
• Android 7.0 or later
• Internet connection for channel list and guide data
• IPTV player app (e.g. TiviMate) for full-screen live TV

NOT A SUBSCRIPTION SERVICE
No accounts, no fees, no content packages sold. Open-source MIT licensed gateway software.

Support: [your support email]
Source: https://github.com/thothassistantai-web/stepdaddy-gateway-android
```

---

## TV-specific notes

- Declare **Android TV** form factor in Play Console
- Provide **TV banner** (1280×720) — reuse `tv_banner` asset
- Leanback launcher already declared in manifest

## Content rating questionnaire

Expect questions about:

- User-generated / streamed content → *No hosted content; links to third-party streams*
- Unrestricted internet → *Yes*
- Location → *No*

Complete IARC honestly; aggregator apps may require extra review.

## Data safety (draft)

| Data type | Collected | Shared |
|-----------|-----------|--------|
| App activity (crash logs) | Optional (if Firebase added later) | No |
| Device IDs | No | No |
| Personal info | No | No |

Network: app contacts third-party upstream URLs and optional update manifest URL configured by user.

## Privacy policy

**Required before publish.** Host at a stable URL and paste here:

```
Privacy policy URL: _______________
```

Minimum content: no personal data collection; local gateway only; third-party upstream contacts.

## Pricing

Free, no in-app purchases.

## Countries

Start with countries where you have confirmed legal clarity for personal use.

## Screenshots

See [docs/SCREENSHOT-CHECKLIST.md](docs/SCREENSHOT-CHECKLIST.md).

## Release track strategy

1. **Internal testing** — maintainer devices
2. **Closed testing** — ONN stick beta users
3. **Open testing** — optional
4. **Production** — after 2+ weeks stable sideload

## Policy risks (review before submit)

- Google may restrict apps that facilitate access to unlicensed streams
- Emphasize *self-hosted*, *no hosted content*, *user-configured upstream* in review notes
- Include link to LEGAL.md / DISCLAIMER.md in store listing or support site

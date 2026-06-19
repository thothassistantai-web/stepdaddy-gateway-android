# Tutorial — StepDaddy Gateway on Android TV

This guide walks through a typical **ONN stick + TiviMate** setup. The same URLs work for VLC, IPTV Smarters, or any M3U/XMLTV client on the same device.

## Overview

```
┌─────────────────┐     loopback      ┌──────────────────┐
│  TiviMate       │ ────────────────► │ StepDaddy Gateway │
│  (IPTV client)  │   127.0.0.1:3000  │  (this app)       │
└─────────────────┘                   └────────┬─────────┘
                                             │ HTTPS
                                             ▼
                                    Third-party upstream
                                    (channels + HLS CDN)
```

StepDaddy Gateway does **not** play video itself (unless you use the built-in preview player). It exposes playlist and EPG URLs for your IPTV app.

## Step 1 — Install and start

1. Install the APK ([INSTALL.md](INSTALL.md)).
2. Launch **StepDaddy Gateway**.
3. Enable **Start on boot** if you want the gateway after every reboot.
4. Press **Start server** and wait for the home-screen banner: *"StepDaddy Gateway running — N channels"*.

**Cold boot timing:** After a full device reboot, allow **60–80 seconds** before channels and EPG are fully ready.

## Step 2 — Copy URLs

From the main dashboard or **Settings**:

| Field | URL |
|-------|-----|
| Playlist | `http://127.0.0.1:3000/tivimate-playlist.m3u8` |
| EPG | `http://127.0.0.1:3000/epg.xml` |

Use the on-screen **Copy** buttons or scan the **QR code** for remote setup from a phone (when remote URL is configured in Settings).

## Step 3 — Configure TiviMate

1. Open **TiviMate**.
2. **Settings → Playlists → Add playlist**.
3. Paste the playlist URL above.
4. When asked for EPG, paste the EPG URL.
5. Wait for playlist refresh (may take a minute on first load).

### TiviMate tips

- **Groups sorting:** Settings → Playlists → Manage Groups → *By order in playlist* respects `group-title` order from the gateway.
- **No gateway status in TiviMate** — use the StepDaddy overlay or reopen this app to confirm the server is running.
- **Same-device loopback** — no `adb reverse` needed when TiviMate and the gateway share one stick.

## Step 4 — Play a channel

1. Pick any channel in TiviMate.
2. **First play** may take 3–15 seconds while upstream HLS is resolved; repeats are faster.
3. If playback fails, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Optional — Built-in player

The dashboard includes a channel browser and fullscreen player for quick tests without leaving the gateway app.

## Optional — Remote access (LAN)

The gateway binds `0.0.0.0:3000`. Other devices on your Wi-Fi can use:

```
http://<stick-lan-ip>:3000/tivimate-playlist.m3u8
```

Set **Remote gateway URL** in Settings if you generate QR codes for phones on the same network. **Do not port-forward to the public internet** unless you understand the security implications.

## Optional — App updates

Settings → **Updates**:

- **Manifest URL** — JSON or GitHub Releases API URL
- **Google Drive folder** — public folder with `update-manifest.json` + APK

Leave blank to disable remote update checks.

## Optional — Supplement channels

Advanced settings allow an embedded sidecar or supplement base URL for extra playlist sources. Defaults are suitable for most users; see [ARCHITECTURE.md](../ARCHITECTURE.md).

## Next steps

- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — boot, EPG, and stream issues
- [ARCHITECTURE.md](../ARCHITECTURE.md) — technical design
- Linux full UI: [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD)

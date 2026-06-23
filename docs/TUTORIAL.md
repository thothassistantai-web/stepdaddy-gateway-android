# Tutorial — StepDaddy Gateway on Android TV

Walkthrough for **ONN stick + TiviMate**. Same loopback URLs work for any M3U/XMLTV client.

**Fast path:** [ONN-QUICK-START.md](ONN-QUICK-START.md) · **Architecture:** [TWO-APP.md](TWO-APP.md)

## Overview

```
┌─────────────────┐     loopback      ┌──────────────────┐
│  TiviMate       │ ────────────────► │ StepDaddy Gateway │
│  (IPTV client)  │   127.0.0.1:3000  │  (this app)       │
└────────┬────────┘                   └────────┬─────────┘
         │ POST /tivimate-events              │ HTTPS
         └────────────────────────────────────┤
              (TiviMate Daddy patch only)     ▼
                                    Third-party upstream
```

Gateway does **not** require TiviMate. TiviMate Daddy **requires** the gateway for playlists.

---

## Path A — Gateway + TiviMate Daddy (recommended)

### Step 1 — Install both apps

1. Install Gateway → [INSTALL.md](INSTALL.md)
2. Build/install `TiviMate-4.6.1-StepDaddy.apk`
3. Start Gateway **before** first TiviMate open

### Step 2 — Gateway settings

On the dashboard:

| Toggle | Purpose |
|--------|---------|
| **Start on boot** | Gateway after every reboot |
| **Launch TiviMate when ready** | Auto-open player when channels load |
| **Keep gateway alive** | Recovery when TiviMate is active |

Cold boot: allow **60–80 seconds** for full channel catalog.

### Step 3 — First TiviMate launch

Open TiviMate. The patch:

1. Fetches `GET http://127.0.0.1:3000/tivimate-setup`
2. Launches playlist wizard with returned URL
3. Auto-advances the URL step for gateway playlists

Force setup if needed:

```bash
adb shell am broadcast -a ar.tvplayer.tv.action.STEPDADDY_SETUP \
  --es gateway_base 'http://127.0.0.1:3000' ar.tvplayer.tv
```

### Step 4 — Boot behavior

After reboot (with toggles enabled):

1. Gateway starts (~20–60 s to HTTP, ~60–80 s to full catalog)
2. Gateway launches TiviMate (+2.5 s settle)
3. Patch tunes boot channel (+5 s defer, default channel **51**)

Verify patch version ≥ `1.2.1-boot-tune-safe` if you see crashes on cold boot.

---

## Path B — Gateway + stock TiviMate (manual)

### Step 1 — Install and start

1. Install Gateway APK only.
2. Enable **Start on boot** if desired.
3. Press **Start server**; wait for ready HUD.

### Step 2 — Copy URLs

| Field | URL |
|-------|-----|
| Playlist | `http://127.0.0.1:3000/tivimate-playlist.m3u8` |
| EPG | `http://127.0.0.1:3000/epg.xml` |

Use **Copy** buttons or QR code from Settings.

### Step 3 — Configure TiviMate

1. Open **TiviMate** (official 5.x or 4.6.1 mod).
2. **Settings → Playlists → Add playlist**.
3. Paste playlist URL; add EPG when prompted.
4. Wait for refresh (~1 min first load).

### TiviMate tips

- **Groups:** Settings → Playlists → Manage Groups → *By order in playlist*
- **No gateway status in TiviMate** — use Gateway HUD or reopen StepDaddy app
- **Loopback** — no `adb reverse` when both apps share one device

---

## Step — Play a channel

1. Pick any channel in TiviMate.
2. First play may take **3–15 s** (upstream HLS resolve).
3. Failures → [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## Optional — Bidirectional API

With TiviMate Daddy running:

```bash
curl -s http://127.0.0.1:3000/tivimate-handshake | jq .
curl -s http://127.0.0.1:3000/tivimate-state | jq .
curl -s http://127.0.0.1:3000/tivimate-events | jq .
```

Tune from host (with adb forward):

```bash
adb forward tcp:4617 tcp:4617
curl -s http://127.0.0.1:4617/tune/51
```

---

## Optional — Built-in player

Gateway dashboard includes a channel browser and fullscreen player for tests without TiviMate.

---

## Optional — Remote access (LAN)

Gateway binds `0.0.0.0:3000`. Other Wi‑Fi devices:

```
http://<stick-lan-ip>:3000/tivimate-playlist.m3u8
```

See [NETWORK-MODES.md](NETWORK-MODES.md). Do not port-forward publicly without understanding security implications.

---

## Optional — Supplement channels

Settings → Supplements: Special Events (sports), iptv-org FAST, ntv.cx, etc. Defaults suit most users.

---

## Next steps

- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — boot, EPG, streams, patch HTTP
- [ARCHITECTURE.md](../ARCHITECTURE.md) — technical design
- [stepdaddy-patch README](../../research/tivimate-apk/stepdaddy-patch/README.md) — HTTP :4617 reference

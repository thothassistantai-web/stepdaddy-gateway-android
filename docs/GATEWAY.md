# StepDaddy Gateway HTTP API

Native Android TV gateway on **port 3000** (loopback `127.0.0.1:3000` by default). Serves M3U playlists, XMLTV EPG, per-channel HLS proxies, and companion-app integration endpoints.

**Stable release:** `3.0.27` (`versionCode` 30027)  
**Package:** `com.thothassistant.stepdaddy.gateway` (release) · `com.thothassistant.stepdaddy.gateway.debug` (debug)

## User playlist URLs

Paste these into IPTV clients. Each serves the **full catalog** with client-specific stream line formatting.

| Client | Playlist | Alias |
|--------|----------|-------|
| **StreamVault** | `http://127.0.0.1:3000/streamvault.m3u` | `/streamvault.m3u8` |
| **TiviMate Daddy** | `http://127.0.0.1:3000/tivimate.m3u` | `/tivimate.m3u8` |
| **VLC / generic** | `http://127.0.0.1:3000/vlc.m3u` | `/vlc.m3u8` |

**EPG:** `http://127.0.0.1:3000/epg.xml` (also referenced in M3U `#EXTM3U url-tvg` header)

**LAN:** replace host with the gateway device's LAN IP when Network mode is **Local**.

### Diagnostic / legacy paths

Kept for FUSA probes and existing bookmarks (same catalog, diagnostic label only):

| Path | Notes |
|------|-------|
| `/tivimate-setup-playlist.m3u8` | 50-channel bootstrap for TiviMate wizard |
| `/streamvault-setup-playlist.m3u8` | Legacy StreamVault bootstrap |
| `/tivimate-playlist.m3u8` | Legacy alias → `tivimate.m3u` |
| `/streamvault-playlist.m3u8` | Legacy alias → `streamvault.m3u` |

Implementation: `app/.../routes/PlaylistPaths.kt`, `PlaylistRoutes.kt`.

## Health endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Full JSON status (channels, EPG, supplements, special events, load progress, TiviMate/StreamVault setup) |
| `GET /health?lite=1` | Fast probe — `ok`, `starting`, `channels`, `supplementChannels`, `loadProgress`, mirror stats |

Example:

```bash
curl -s 'http://127.0.0.1:3000/health?lite=1' | jq '{ok,starting,version,channels,supplementChannels}'
```

Expect `ok: true`, `starting: false`, and `channels > 0` before importing playlists in StreamVault or TiviMate.

### Special Events (tier 1–5, v3.0.0)

Full health includes `specialEventGuides`, `dlhdEventStreams`, and `specialEvents` summary (language/region metadata, schedule times, stream health dots 🟢🔴🟡⚪, lifecycle add/remove). DaddyLive schedule only — TheTvApp/xyzstreams removed.

Live backups also expose `freeTvChannels` / `freeTvEnabled` (Free-TV/IPTV USA/CA/UK) and `duloCxChannels` / `duloCxEnabled` (dulo.cx Live TV). See [FMHY-STREAMING-EVAL.md](FMHY-STREAMING-EVAL.md).

## Stream proxies

| Pattern | Use |
|---------|-----|
| `/tivimate-stream/{id}.m3u8` | TiviMate pipe-suffixed lines |
| `/stream/{id}.m3u8` | Plain proxy (StreamVault default) |
| `/dlhd-event-stream/{id}.m3u8` | Special event live streams |
| `/dlhd-event-guide/{id}.m3u8` | Special event guide slates |

## Companion setup

| Endpoint | Client |
|----------|--------|
| `GET /tivimate-setup` | TiviMate Daddy auto-setup JSON |
| `GET /streamvault-setup` | StreamVault plugin / manual pairing JSON |
| `POST /tivimate-events` | TiviMate telemetry |
| `GET /tivimate-handshake` | Patch bootstrap |

## StreamVault plugin

Gateway ships an embedded StreamVault plugin (`com.thothassistant.stepdaddy.gateway.streamvault`) that returns `streamvault.m3u` + `epg.xml` via plugin IPC. See [PLUGIN_API.md](PLUGIN_API.md).

## Related docs

| Doc | Topic |
|-----|-------|
| [TWO-APP.md](TWO-APP.md) | Gateway + TiviMate Daddy |
| [STOCK-TIVIMATE-SETUP.md](STOCK-TIVIMATE-SETUP.md) | Stock TiviMate + StreamVault ship path |
| [FMHY-STREAMING-EVAL.md](FMHY-STREAMING-EVAL.md) | FMHY Streaming research → removals + Free-TV integration |
| [TIER-RELEASES.md](TIER-RELEASES.md) | Special Events tier rollout (completed in 3.0.0) |
| [STREAMVAULT-GATEWAY-PLAN.md](STREAMVAULT-GATEWAY-PLAN.md) | StreamVault integration plan |
| [RELEASE.md](RELEASE.md) | Version bump and GitHub releases |

**StreamVault client:** [StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV) · [docs/GATEWAY.md](https://github.com/thothassistantai-web/StreamVault-IPTV/blob/master/docs/GATEWAY.md)

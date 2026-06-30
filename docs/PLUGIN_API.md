# StreamVault Plugin API (Gateway implementation)

StepDaddy Gateway **3.0.0** embeds a StreamVault companion plugin that exposes the local M3U catalog and XMLTV EPG without a separate APK.

**Host:** [StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV) — full plugin contract in [PLUGIN_API.md](https://github.com/thothassistantai-web/StreamVault-IPTV/blob/master/docs/PLUGIN_API.md).

## Embedded plugin identity

| Field | Value |
|-------|-------|
| Plugin ID | `com.thothassistant.stepdaddy.gateway.streamvault` |
| Service action | `com.streamvault.plugin.API` |
| Service class | `com.thothassistant.stepdaddy.gateway.streamvault.StreamVaultPluginService` |
| Capabilities | `provider.m3u`, `playback.prepare`, `configuration.schema` |

## Provider URLs (`MSG_GET_PROVIDER_URL` = 4)

When enabled, the plugin returns:

| Key | Value |
|-----|-------|
| `url` | `{gatewayBase}/streamvault.m3u` |
| `epg_url` | `{gatewayBase}/epg.xml` |
| `provider_name` | `StepDaddy Gateway` |

`gatewayBase` defaults to `http://127.0.0.1:3000` or LAN IP when **LAN mode** is enabled in plugin settings.

## Gateway wake (`MSG_ENSURE_GATEWAY` = 11)

StreamVault sends this before plugin sync or gateway-managed playback. The plugin calls `GatewayStartHelper.ensureGatewayReady()` and blocks until `/health?lite=1` reports a non-empty catalog.

Cross-app wake from StreamVault also targets `ServerService` via `com.thothassistant.stepdaddy.gateway.action.START` — see StreamVault `GatewayConstants.kt`.

## Playback prepare (`MSG_PREPARE_PLAYBACK` = 5)

Handles loopback URLs under `/tivimate-stream/`, `/stream/`, `/dlhd-event-stream/`, etc. Ensures gateway HTTP is ready and returns enriched headers when needed.

When gateway audio settings are configured, the response may include:

| Key | Value |
|-----|-------|
| `audio_json` | `{"volumeNormalization":false,"amplificationGainDb":0.0}` |

Companion players (StreamVault host) can read this to apply loudness normalization and gain. The gateway HTTP proxy does not decode audio.

## Configuration schema

Host-rendered fields:

| Key | Type | Purpose |
|-----|------|---------|
| `gatewayBaseUrl` | url | Loopback or LAN gateway base |
| `lanMode` | boolean | Substitute device LAN IP |
| `status` | info | Last health probe label |
| `volumeNormalization` | info | Read-only mirror of gateway Settings → Audio |
| `amplificationGainDb` | info | Read-only gain label (e.g. `0 dB`, `+6 dB`) |

Audio preferences are edited in **StepDaddy Gateway → Settings → Audio**, not in the StreamVault plugin form.

Action `testConnection` runs `GET /health?lite=1` and refreshes status.

## Implementation files

```
app/.../streamvault/
  StreamVaultPluginContract.kt   # IPC constants (mirrors StreamVault host)
  StreamVaultPluginService.kt    # Messenger handlers
  StreamVaultPluginSupport.kt    # Manifest, URLs, health probe
  StreamVaultPluginPlaybackPrepare.kt
  StreamVaultPluginPrefs.kt
```

## Related

- [GATEWAY.md](GATEWAY.md) — HTTP routes and health
- [STREAMVAULT-GATEWAY-PLAN.md](STREAMVAULT-GATEWAY-PLAN.md) — integration phases

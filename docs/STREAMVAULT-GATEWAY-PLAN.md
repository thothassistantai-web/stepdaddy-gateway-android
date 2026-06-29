# StreamVault × StepDaddy Gateway Integration Plan

**Fork:** [thothassistantai-web/StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV)  
**Gateway:** [stepdaddy-gateway-android](https://github.com/thothassistantai-web/stepdaddy-gateway-android) **v3.0.0**  
**StreamVault:** **v3.0.0** (gateway plugin, caches, resume toggle, special-event display names)  
**Updated:** 2026-06-28

---

## Stable v3.0.0 alignment

Both apps ship **3.0.0** with matched gateway contracts:

| Feature | Gateway | StreamVault |
|---------|---------|-------------|
| Playlist | `/streamvault.m3u` | Plugin + manual M3U import |
| EPG | `/epg.xml` | Auto from `epg_url` + M3U header |
| Plugin API | Embedded `StreamVaultPluginService` | `GatewayLifecycleManager`, `MSG_ENSURE_GATEWAY` |
| Special event dots/metadata | M3U title prefixes 🟢🔴🟡⚪ | `M3uChannelDisplayName` strips dots for browse |
| Resume last live channel | N/A | Settings → Playback toggle |
| Browse caches | Playlist/EPG server caches | `LiveChannelBrowseCache`, `GuideSessionCache`, etc. |
| Health | `/health`, `/health?lite=1` | Pre-import readiness gate |

## Quick setup (same device)

1. Install **StepDaddy Gateway** release APK (`com.thothassistant.stepdaddy.gateway`).
2. Start gateway; wait until HUD shows channel count ready.
3. Install **StreamVault** release APK.
4. **Plugins** → enable **StepDaddy Gateway** → **Test connection** → **Add provider**.

Manual URL: `http://127.0.0.1:3000/streamvault.m3u` + `http://127.0.0.1:3000/epg.xml`

## Phase status

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Manual M3U URL paste | **Done** |
| 1 | Debug `m3u.dev.url` seed | **Done** |
| 2 | Gateway discovery UI card | **Done** (Plugins screen) |
| 3 | Auto-start + recovery worker | **Done** (`GatewayRecoveryWorker`) |
| 4 | Playback prepare for gateway URLs | **Done** |
| 5 | Tier 1–5 special events in guide | **Done** (gateway 3.0.0) |

## Implementation map (StreamVault)

| Area | Files |
|------|-------|
| Constants | `app/.../plugins/GatewayConstants.kt` |
| Lifecycle | `GatewayLifecycleManager.kt`, `GatewayRecoveryWorker.kt` |
| Plugin host | `StreamVaultPluginManager.kt`, `StreamVaultPluginContract.kt` |
| Display names | `domain/.../M3uChannelDisplayName.kt` |
| Resume | `LiveResumeSupport.kt`, Settings playback section |
| Caches | `ui/screens/home/*Cache.kt`, `GuideSessionCache.kt`, `EpgQueryCache.kt` |

## Implementation map (Gateway)

| Area | Files |
|------|-------|
| Playlists | `routes/PlaylistPaths.kt`, `PlaylistRoutes.kt` |
| Plugin | `streamvault/StreamVaultPluginService.kt` |
| Special events | `upstream/EventLifecycleManager.kt`, `EventTitleHealthDots.kt` |
| Health | `routes/HealthRoutes.kt` |

## Related docs

- Gateway: [docs/GATEWAY.md](GATEWAY.md), [docs/PLUGIN_API.md](PLUGIN_API.md), [docs/TIER-RELEASES.md](TIER-RELEASES.md)
- StreamVault: [docs/GATEWAY.md](https://github.com/thothassistantai-web/StreamVault-IPTV/blob/master/docs/GATEWAY.md), [docs/PLUGIN_API.md](https://github.com/thothassistantai-web/StreamVault-IPTV/blob/master/docs/PLUGIN_API.md)

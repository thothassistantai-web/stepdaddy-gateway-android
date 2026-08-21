# Gateway health audit (FUSA2541006925)

**Date:** 2026-06-25  
**Gateway package on device:** `com.thothassistant.stepdaddy.gateway.debug` v2.3.3-debug  
**TiviMate package:** `com.thothassistant.daddyliveTV` patch 3.1.14

## Executive summary

| Check | Result | Notes |
|-------|--------|-------|
| Cold-start `/health?lite=1` + `/tivimate-setup` | **PASS** (~76–82s) | Stable 2/2 probes; ~4864 channels |
| `playerInstalled` in setup payload | **PASS** | `true` on live probe |
| TiviMate playlist import (setup gate) | **FAIL** (intermittent) | `channelCount=0` after 1200s in runs 6–9 |
| Manifest `<queries>` for TiviMate | **OK** | daddyliveTV + stepdaddy scheme declared |

Gateway HTTP and catalog readiness work on FUSA but cold start is **too slow** (~76s) because component init exceeded the 25s watchdog and retried twice before CIO bound.

---

## FUSA test log review (`tivimate-daddy/debug-3.1.x/`)

| Run | Gateway ready | Overall |
|-----|---------------|---------|
| fusa-setup-gate-319-run11 | 78s, 4864 ch | In progress (import slow) |
| fusa-setup-gate-319-run10 | >84s (timeout log) | — |
| fusa-setup-gate-319-run9 | 82s PASS | **FAIL** (import 1200s) |
| fusa-setup-gate-319-run6–8 | ~68–82s | **FAIL** (import) |

**Patterns observed**

- Gateway not ready for **68–82s** after `force-stop` → `start-foreground-service ServerService`.
- Readiness gate needs: `ok=true`, `starting=false`, `channels+supplementChannels>0`, `/tivimate-setup` playlist URL present.
- No `playerInstalled:false` in recent gate runs; earlier critical-path logs showed `setup_payload_missing` on the **TiviMate** side while gateway was up.
- Import failures (`wizard=importing`, `channelCount=0`) are downstream of gateway readiness — patched player not finishing M3U ingest within timeout.

---

## Live probe (2026-06-25)

```
force-stop → start ServerService → adb forward :3000
READY t=76s channels=4863 playerInstalled=True
  DaddyLive: 1159 | Supplement: 3704
```

**Logcat root cause (pre-fix build):**

```
ServerService: Gateway components init timed out after 25000ms; will retry
(twice, ~50s wasted)
GatewayServer: Listening on 127.0.0.1:3000
ServerService: Gateway listening (1159 channels)
```

`GatewayApp.initComponents()` blocked on synchronous parse of `channels_db_cache.csv` (~3.6 MB / 39k rows) inside `IptvOrgNameIndex` init. FUSA CPU needs ~50s for full component graph; 25s timeout caused double retry before HTTP bind.

---

## Issues found

### 1. Slow cold start — **fixed in tree**

- **Cause:** `IptvOrgNameIndex` parsed CSV on calling thread; `COMPONENT_INIT_MAX_WAIT_MS=25s` too low for FUSA.
- **Fix:** Async CSV load in `IptvOrgNameIndex`; raise init wait to 60s.
- **Expected:** HTTP listening in ~15–25s on warm disk cache (verify after install).

### 2. Readiness semantics — informational

- `GatewayHealth.probeReadiness` treats `starting=true` when `totalChannels==0`. Disk-cached supplements (3704) + DaddyLive cache (1159) satisfy readiness once HTTP is up — no upstream fetch required for gate PASS.
- `epgReady=false` at gate time is expected; EPG build deferred 20s (`BOOT_EPG_BUILD_DEFER_MS`).

### 3. TiviMate import timeout — open (player patch)

- Setup gate FAILs are `importPass=false` with `channelCount=0` after 1200s while gateway reports 4864 channels.
- Not a gateway HTTP bug; track in `tivimate-daddy` patch (`setup_payload` / import worker).

### 4. Sleep / freeze / FGS — OK

- `GatewayStartHelper` uses alarm fallbacks (not WM) during boot ANR window.
- `ServerService` self-heal every 30s if CIO drops while FGS alive.
- `tivimateWatch` keep-alive every 5m when enabled.

### 5. Manifest / queries — OK

- `<queries>` includes `com.thothassistant.daddyliveTV`, legacy packages, `stepdaddy://` VIEW intent.
- `TiviMateController.isPackageInstalled` has installed-packages fallback if queries fail.

---

## FUSA-verified default settings

Fresh install / clear-data loads **full DaddyLive catalog** plus **all supplements** (Special Events, iptv-org, Free-TV, Dulo Live, ntv.cx, Adult Swim) with `CONSOLIDATE_FALLBACKS` import modes (smart backups). MoveOnJoy / TheTvApp / xyzstreams / TVPass were removed.

| Setting | Value |
|---------|-------|
| port | 3000 |
| network_access_mode | DEFAULT |
| dlhd_base_url | `https://daddylive.eu` |
| mirror_urls | `dlstreams.st`, `daddylive.li`, `dlhd.st` |
| supplement: sports / iptv-org / ntv.cx / adult-swim | all **true** |
| supplement import modes | **CONSOLIDATE_FALLBACKS** (opt-in `FULL_CATALOG` / `SKIP_DUPLICATES` per provider in Settings) |
| setup playlist (`/tivimate-setup-playlist.m3u8`) | **diagnostic** — 50-channel bootstrap (`SETUP_BOOTSTRAP_MAX_CHANNELS`); user catalog at `/tivimate.m3u` |
| user playlists | `/tivimate.m3u`, `/streamvault.m3u`, `/vlc.m3u` (+ `.m3u8` aliases); `X-Playlist-Kind: user` header |
| gateway_epg_enabled | true |
| external_epg_url | epgshare01 US2 + US_SPORTS1 + US_LOCALS1 (gz) |
| iptv_org_epg_enabled | true |
| playlist_title_style | **XTREAM_CATEGORY** |
| start_on_boot / auto_start_on_launch / auto_launch_tivimate / tivimate_watch | all **true** |
| auto_check_updates | true |
| auto_download_updates | false |

Existing installs are unchanged (prefs only read when present). Fresh install / clear-data uses these baked-in defaults.

---

## Files touched (this audit)

- MOJ removal: sidecar package, `SupplementSource`, health/UI/admin models, settings layout
- Full-catalog defaults: `PlaylistBuilder.tivimateSetupPlaylist`, `build.gradle.kts`
- Prior boot fixes: `IptvOrgNameIndex.kt` async CSV load, `ServerService.kt` init timeout 60s

# StepDaddy Gateway 2.3.3

**Status:** Published — see GitHub release `v2.3.3`.

**Package:** `com.thothassistant.stepdaddy.gateway`  
**Debug package:** `com.thothassistant.stepdaddy.gateway.debug`  
**FUSA gate:** PASS — cold start ~33 s, ~4853 channels at readiness

---

## Highlights

- **Full catalog defaults** — fresh install / clear-data enables DaddyLive + all supplements (Sports, iptv-org, ntv.cx, Adult Swim) with `FULL_CATALOG` import modes
- **Setup playlist** — `/tivimate-setup-playlist.m3u8` serves the full merged catalog (no 50-channel wizard cap)
- **MOJ removed** — MoveOnJoy sidecar discontinued; provider code and settings UI removed
- **Faster cold start** — async `IptvOrgNameIndex` CSV load; component init timeout raised to 60 s (was 25 s double-retry on FUSA)
- **Stock TiviMate path** — documented in `docs/STOCK-TIVIMATE-SETUP.md` as the recommended ship workflow

---

## FUSA verification (2026-06-26)

| Check | Result |
|-------|--------|
| Cold-start `/health?lite=1` | PASS ~33 s |
| Channel count at ready | ~4853 (DaddyLive + supplements) |
| `/tivimate-setup` playlist URL | Present |
| `playerInstalled` | `true` when patched player installed |
| Default supplements | All on, `FULL_CATALOG` modes |
| MOJ sidecar | Removed |

---

## Default settings (fresh install)

| Setting | Value |
|---------|-------|
| port | 3000 |
| dlhd_base_url | `https://daddylive.eu` |
| mirror_urls | `daddylive.li`, `daddylive.org` |
| supplements | sports, iptv-org, ntv.cx, adult-swim — all **true** |
| supplement import modes | **FULL_CATALOG** |
| gateway_epg_enabled | true |
| external_epg_url | epgshare01 US2 + US_SPORTS1 + US_LOCALS1 (gz) |
| iptv_org_epg_enabled | true |
| playlist_title_style | **XTREAM_CATEGORY** |
| start_on_boot / auto_start / auto_launch_tivimate | **true** |

Existing installs keep saved preferences; only fresh install / clear-data gets these defaults.

---

## Upgrade notes

- No migration required for MOJ removal — toggle simply disappears; no crash on upgrade
- If cold start was ~76–82 s on 2.3.2, expect ~30–60 s after this build on the same device
- Patched TiviMate import issues are tracked separately in `tivimate-daddy`; gateway HTTP is not the blocker

---

## Publish command (when authenticated)

```bash
cd stepdaddy-android
./scripts/build-release.sh
gh release create v2.3.3 \
  --title "StepDaddy Gateway 2.3.3" \
  --notes-file release/RELEASE-NOTES-2.3.3.md \
  release/stepdaddy-gateway-2.3.3.apk
```

If `gh auth status` fails, leave this file and the signed APK in `release/` for manual upload.

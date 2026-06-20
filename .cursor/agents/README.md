# StepDaddy Android Gateway — Cursor Subagents

Project-level debug and specialist agents for `stepdaddy-android/`. Check into git so the team shares the same specialists.

**Package:** `com.thothassistant.stepdaddy.gateway.debug`  
**Default device:** `FUSA2541006925` (ONN stick)  
**Gateway port:** `3000`

---

## Full debug pipeline (start here)

When symptoms are unclear or span multiple areas, run the orchestrator first:

```
Use android-debug-orchestrator to run a full debug sweep on FUSA2541006925.
```

| Agent | Role |
|-------|------|
| [`android-debug-orchestrator`](android-debug-orchestrator.md) | **Master coordinator** — phased health/playlist/stream/EPG/TiviMate sweep + delegates |

---

## Core gateway debug agents (by subsystem)

| Agent | When to invoke |
|-------|----------------|
| [`gateway-http-debugger`](gateway-http-debugger.md) | `/health` fails, connection refused, Ktor not listening |
| [`gateway-boot-lifecycle-debugger`](gateway-boot-lifecycle-debugger.md) | Boot ANR, FGS crash, gateway won't start on boot |
| [`gateway-channel-upstream-debugger`](gateway-channel-upstream-debugger.md) | 0 channels, stale list, DaddyLive mirror errors |
| [`gateway-playlist-debugger`](gateway-playlist-debugger.md) | TiviMate "update all playlists" fails, playlist timeout, `PlaylistCache` slow |
| [`gateway-supplement-debugger`](gateway-supplement-debugger.md) | Supplement count 0, sidecar/sports sync failures |
| [`gateway-iptv-org-debugger`](gateway-iptv-org-debugger.md) | iptv-org missing, wrong category, provider tags |
| [`gateway-stream-debugger`](gateway-stream-debugger.md) | Stream manifest 502/504, resportz/CDN failures |
| [`gateway-epg-debugger`](gateway-epg-debugger.md) | EPG empty, stale, `epgProgrammeCount` 0 |
| [`gateway-logo-meta-debugger`](gateway-logo-meta-debugger.md) | Logos broken, meta tags / grouping wrong |
| [`gateway-performance-profiler`](gateway-performance-profiler.md) | Slow builds, GC/OOM, playlist >30s |
| [`gateway-build-deploy-debugger`](gateway-build-deploy-debugger.md) | Gradle fail, sideload, post-deploy verify |
| [`gateway-ota-debugger`](gateway-ota-debugger.md) | OTA invalid APK, wrong package, GitHub release asset mismatch |

---

## FUSA / device specialists

| Agent | When to invoke |
|-------|----------------|
| [`fusa-tivimate-debugger`](fusa-tivimate-debugger.md) | TiviMate spinner, black screen, ExoPlayer errors |
| [`fusa-log-auditor`](fusa-log-auditor.md) | Time-windowed logcat incident timelines |
| [`fusa-boot-verifier`](fusa-boot-verifier.md) | Cold boot → gateway auto-start verification |
| [`fusa-boot-ux-tester`](fusa-boot-ux-tester.md) | Boot UX, notifications, ready banner |
| [`fusa-first-stream-timer`](fusa-first-stream-timer.md) | Time-to-first-playable stream |
| [`fusa-multi-stream-perf`](fusa-multi-stream-perf.md) | Concurrent stream / memory stress |

---

## Implementation & parity (not pure debug)

| Agent | When to invoke |
|-------|----------------|
| [`gateway-stream-healer`](gateway-stream-healer.md) | **Implement** self-healing streams, cache invalidation |
| [`gateway-full-resolver`](gateway-full-resolver.md) | End-to-end resolver / architecture work |
| [`linux-gateway-parity`](linux-gateway-parity.md) | Kotlin vs Python `stepdaddy-web` behavior gaps |
| [`epg-mapping-auditor`](epg-mapping-auditor.md) | EPG tvg-id mapping quality audit |
| [`iptv-provider-sort-research`](iptv-provider-sort-research.md) | Provider sort / FiOS-Spectrum numbering research |
| [`thetvapp-token-flow-investigator`](thetvapp-token-flow-investigator.md) | TVApp2 token / proxy sidecar issues |
| [`tvapp2-integration-strategist`](tvapp2-integration-strategist.md) | Sidecar integration planning |

---

## Symptom → agent quick reference

| Symptom | Agent(s) |
|---------|----------|
| Anything unclear | `android-debug-orchestrator` |
| Playlist update / timeout | `gateway-playlist-debugger` → `gateway-performance-profiler` |
| Won't play / spinner | `fusa-tivimate-debugger` → `gateway-stream-debugger` |
| Gateway won't start | `gateway-boot-lifecycle-debugger` → `fusa-boot-verifier` |
| Missing iptv-org channels | `gateway-iptv-org-debugger` → `gateway-supplement-debugger` |
| Wrong groups / HBO in wrong category | `gateway-iptv-org-debugger` |
| 0 DaddyLive channels | `gateway-channel-upstream-debugger` |
| EPG blank | `gateway-epg-debugger` → `epg-mapping-auditor` |
| After code change | `gateway-build-deploy-debugger` → orchestrator |
| Logos / Glide errors | `gateway-logo-meta-debugger` |
| OOM / 4min playlist build | `gateway-performance-profiler` → `gateway-playlist-debugger` |

---

## Example delegations

```text
Use android-debug-orchestrator for a full sweep after the playlist cache fix.

Use gateway-playlist-debugger — TiviMate update all playlists still failing.

Use fusa-tivimate-debugger and gateway-stream-debugger for channel 51 spinner.

Use gateway-build-deploy-debugger, then gateway-performance-profiler on FUSA.
```

---

## Key source map

| Area | Primary paths |
|------|----------------|
| HTTP server | `GatewayServer.kt`, `routes/` |
| Playlist | `PlaylistRoutes.kt`, `PlaylistCache.kt`, `PlaylistBuilder.kt` |
| Channels | `DaddyLiveClient.kt` |
| Supplements | `SupplementSource.kt`, `IptvOrgStreamsSource.kt` |
| Streams | `StreamRoutes.kt`, `ResportzParser.kt`, `M3u8Rewriter.kt` |
| EPG | `epg/EpgManager.kt` |
| Boot / FGS | `ServerService.kt`, `GatewayStartHelper.kt` |
| Tests | `app/src/test/kotlin/...` |

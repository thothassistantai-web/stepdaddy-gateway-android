# StepDaddy Gateway 3.0.16

## Merge fallbacks import mode

### Added

- **Import mode: Merge fallbacks** — third option per supplement provider (iptv-org, ntv.cx, xyz, Adult Swim). Same playlist row count as "Skip dupes", but overlapping supplement streams attach as automatic failover mirrors on the matching DaddyLive channel.
- **Multi-variant HLS masters** — DaddyLive rows with fallbacks expose index 0 = DaddyLive resolve, 1+ = supplement mirrors via `/daddy-fallback/{channelId}/{index}.m3u8` and `/tivimate-stream/{id}.m3u8`.
- **Internal iptv-org dedup mirrors** — kept supplement rows with duplicate tvg-ids route through `/supplement-stream/{id}/master.m3u8`.
- **Settings UI** — 3-way toggle per provider: All channels / Skip dupes / Merge fallbacks.
- **Persistence** — fallback map saved to `daddy_fallbacks.json` and restored on disk recovery.

Sideload `stepdaddy-gateway-3.0.16-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. `./gradlew test` — all unit tests pass
2. Settings → ntv.cx → **Merge fallbacks** → save → supplement sync completes
3. Re-import playlist — overlapping ntv.cx names absent as separate rows; DaddyLive ESPN (etc.) has multi-variant master
4. Play DaddyLive channel with fallbacks — player switches variant on failure
5. iptv-org internal dupes — supplement row URL ends in `/supplement-stream/{id}/master.m3u8`
6. `curl http://127.0.0.1:3000/health` — version `3.0.16`

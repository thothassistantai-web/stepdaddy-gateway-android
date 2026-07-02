# StepDaddy Gateway 3.0.15

## Sync reliability + phone UI

### Fixed

- **Supplement sync lifecycle** — publish merged catalog before logo enrich; clear stuck `syncInFlight`; refresh callbacks after flag clear
- **Health during iptv-org fetch** — incremental sync stats; iptv-org counts from cache or in-flight merge
- **Special Events health** — no false "syncing" during unrelated supplement refresh
- **Xtream parity** — iptv-org uses `GroupTitleResolver` in live categories; series episodes include `direct_source`
- **Xtream auth** — `/movie/` and `/series/` redirects require valid credentials
- **Settings** — supplement/EPG toggle save triggers refresh without full service restart

### Changed

- **Channel numbering** — iptv-org supplements always resolve group via `GroupTitleResolver`
- **Phone layouts** — portrait stacked dashboard with 2×2 stats; compact landscape for phones under 600dp sw; player rotates on phones (TV stays landscape)

Sideload `stepdaddy-gateway-3.0.15-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. `./gradlew test` — all unit tests pass
2. Phone portrait — dashboard cards stack; stats 2×2; footer readable
3. Phone landscape — server/management side-by-side; content + sidebar split
4. TV stick — default multi-column dashboard unchanged
5. `curl http://127.0.0.1:3000/health` — version `3.0.15`, iptv-org sync completes without stuck flag
6. TiviMate → Update playlist after supplement sync

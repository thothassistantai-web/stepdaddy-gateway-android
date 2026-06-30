# StepDaddy Gateway 3.0.10

## Fixed

- **Special Events parser errors** — error HLS manifests no longer emit fake `#EXTINF` / `unavailable.ts` segments that trigger ExoPlayer `ParserException` on TiViMate 5.x
- **HBO 2 Eastern EPG** — bundled `tvtv_bundled_grids/HBO2.us.json` fallback when tvtv.us returns HTTP 429 (real titles like "How to Make a Killing" instead of "Live programming")
- **View gateway release** — About → View gateway release opens `stepdaddy-gateway-android` GitHub releases (not `tivimate-daddy`)
- **TiViMate x2 mod UI** — dashboard/About copy targets `ar.tvplayer.tv` x2 Premium mod; Launch uses `com.andyhax.haxsplash.LaunchActivity`

Sideload `stepdaddy-gateway-3.0.10-release.apk` (`com.thothassistant.stepdaddy.gateway`).

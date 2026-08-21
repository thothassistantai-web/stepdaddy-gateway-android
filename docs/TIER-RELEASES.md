# Tiered Special Events releases

Staged rollout for **Special Events** playlist/EPG work on **StepDaddy Gateway**. Tiers 1–5 shipped in **stable v3.0.0** (`versionCode` 30000).

## Policy

| Rule | Detail |
|------|--------|
| **Tier 1 device** | `FUSA2541006925` (ONN Full HD Streaming Device) |
| **Transport** | **USB only** for tier debug deploys — do not `adb install` over Wi‑Fi/LAN |
| **Production APK** | Signed release `com.thothassistant.stepdaddy.gateway` via `assembleRelease` |
| **Sign-off** | Human confirms on-device behavior before promoting tier work to stable |

## Tier map (v3.0.0)

| Tier | Scope | Status |
|------|--------|--------|
| **1** | Alphabetical Special Events guides; guide-then-events grouping; dedupe by upstream URL | **Shipped** |
| **2** | Language + region metadata on event titles | **Shipped** |
| **3** | Event start/end times + EPG programmes | **Shipped** |
| **4** | Stream health probes + 🟢🔴🟡⚪ title dots | **Shipped** |
| **5** | Auto add/remove lifecycle from schedule | **Shipped** |
| **6** | Full metadata scraper, dashboard polish, integration tests | Ongoing |

## User-facing playlists (v3.0.0)

| Client | URL |
|--------|-----|
| StreamVault | `http://127.0.0.1:3000/streamvault.m3u` |
| TiviMate (fast) | `http://127.0.0.1:3000/tivimate.m3u` |
| TiviMate Smart (backups) | `http://127.0.0.1:3000/tivimate-smart.m3u` |
| VLC | `http://127.0.0.1:3000/vlc.m3u` |
| EPG | `http://127.0.0.1:3000/epg.xml` |

Legacy `*-setup-playlist.m3u8` paths remain for diagnostics.

## Health verification

```bash
curl -s 'http://127.0.0.1:3000/health?lite=1' | jq '{ok,starting,version,channels,specialEventGuides,dlhdEventStreams}'
curl -s 'http://127.0.0.1:3000/health' | jq '.specialEvents'
```

## Tier debug deploy (FUSA USB)

```bash
cd stepdaddy-android
adb devices -l   # FUSA2541006925 must show usb:…
TIER=5 ./scripts/deploy-tier-fusa.sh
```

## Production release (v3.0.0+)

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk release/stepdaddy-gateway-3.0.0-release.apk
```

See [RELEASE.md](RELEASE.md) for signing and GitHub Releases.

## Related docs

- [GATEWAY.md](GATEWAY.md) — routes and health fields
- [STOCK-TIVIMATE-SETUP.md](STOCK-TIVIMATE-SETUP.md) — StreamVault + TiviMate ship path
- [STREAMVAULT-GATEWAY-PLAN.md](STREAMVAULT-GATEWAY-PLAN.md) — StreamVault client integration

**StreamVault:** [StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV)

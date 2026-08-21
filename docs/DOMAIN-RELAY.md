# Domain relay

When DaddyLive catalog/stream hosts move or go down, maintainers can publish a **data-only** JSON file so installed gateways pick up new mirrors without waiting for an APK release.

This is **not** remote code execution — only hostnames/URLs and a short message.

## File location

| Channel | URL |
|---------|-----|
| Repo (raw `main`) | `https://raw.githubusercontent.com/thothassistantai-web/stepdaddy-gateway-android/main/release/domain-relay.json` |
| GitHub Releases asset | `https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/domain-relay.json` |

Pinned in the app via `BuildConfig.DEFAULT_DOMAIN_RELAY_URL` / `DEFAULT_DOMAIN_RELAY_RELEASE_URL` (this repo only).

Checked into git: [`release/domain-relay.json`](../release/domain-relay.json). Published as a release asset alongside `update-manifest.json` and `aftv-codes.json`.

## Schema

```json
{
  "version": 1,
  "minAppVersion": "3.0.0",
  "forceUpdateAfter": null,
  "message": "Temporary backup source — update soon or streams may stop.",
  "sources": {
    "daddylive": {
      "primary": "https://daddylive.eu",
      "mirrors": ["https://dlstreams.st", "https://daddylive.li", "https://dlhd.st"],
      "blocked": ["daddylive.org"],
      "relayHosts": ["https://dlstreams.st", "https://dlhd.st", "https://dlhd.pk"],
      "embedHosts": ["https://dlstreams.st", "https://dlhd.st"]
    }
  }
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `version` | Yes | Monotonic integer; older than cached is ignored |
| `minAppVersion` | No | If newer than installed app, file is ignored |
| `forceUpdateAfter` | No | ISO-8601 date (`YYYY-MM-DD`); past date strengthens the update banner |
| `message` | No | Shown on dashboard banner (fallback string if blank) |
| `sources.daddylive.primary` | No | Catalog primary base URL |
| `sources.daddylive.mirrors` | No | Mirror list (https URLs) |
| `sources.daddylive.blocked` | No | Hostnames excluded from rotation |
| `sources.daddylive.relayHosts` | No | Stream relay bases (`DLHD_RELAY_HOSTS`) |
| `sources.daddylive.embedHosts` | No | Embed fetch hosts |

Validation: JSON size ≤ ~32 KB; hosts must be valid hostnames; only `http`/`https` URLs.

## Precedence

1. **User Settings** (DaddyLive base URL / mirror URLs if the user has saved custom values)
2. **Relay overrides** (from the last-good fetched file)
3. **Compiled defaults** (`BuildConfig` / `GatewayConfig`)

Blocked / relay / embed hosts always come from relay when a file is applied (users do not edit those in Settings).

## App behavior

- Fetch on dashboard startup (with the OTA update check) and when upstream outage opens after mirrors fail.
- Cache last-good relay on disk; apply cache on boot before network returns.
- `relayOverridesActive` / health fields: `domainRelayActive`, `domainRelayVersion`, `domainRelaySource`, `domainRelayFetchedAtMs`.
- Persistent bottom banner when relay is active — tap opens in-app update check. Includes AFTV codes **4860686** (release) / **1401588** (debug). Does not block playback.

## Outage playbook (maintainers)

1. Confirm new working primary/mirrors (browser or `curl`).
2. Edit `release/domain-relay.json` — bump `version`, update `sources.daddylive.*`.
3. Commit + push to `main` (raw URL updates immediately for apps that try raw first).
4. Optionally run `./scripts/build-release.sh` + `./scripts/publish-github-release.sh` (or `--assets-only` on an existing tag) so the Releases asset matches.
5. Devices refresh on next dashboard open or outage trigger; no APK bump required for domain-only changes.

For permanent defaults, also bump compiled `GatewayConfig` / `GatewayEnvironment` defaults in a normal app release.

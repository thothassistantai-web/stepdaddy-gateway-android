# Channel backups & consolidation matching

How StepDaddy decides when a Free-TV / iptv-org / ntv / dulo stream is the **same channel** as a DaddyLive row, and how to fix mistakes.

## Defaults (3.0.38+)

- Import mode defaults to **All channels** (`FULL_CATALOG`) for Free-TV, iptv-org, ntv, dulo, and Adult Swim — every provider row is a separate playlist entry.
- **Merge fallbacks** (`CONSOLIDATE_FALLBACKS`) and **Skip dupes** remain selectable per provider in Settings.
- Automatic backups only attach when Merge fallbacks is chosen (high-confidence score ≥ 70, region/language aware).
- Smart playlist **`/tivimate-smart`** remains available for opt-in ExoPlayer failover across consolidate backups; recommended default playlist stays **`/tivimate`** (fast).

### Upgrade migration (import_mode_defaults_version = 3)

On first launch of 3.0.38+:

- If a provider’s stored mode is still the previous auto-default (`CONSOLIDATE_FALLBACKS` / unset) **and** the user never explicitly saved an import-mode choice (`*_import_mode_user_set` = false), it flips to **All channels**.
- If you already chose Skip dupes, Merge fallbacks, or saved Full catalog after touching the toggle, that choice is kept.
- To enable automatic backups again: **Settings →** provider → **Merge fallbacks** → Save, then point TiviMate at `/tivimate-smart.m3u` if you want failover.

## Why old matching failed

Previously, overlap used:

1. Exact `normalizeName()` equality, or
2. Exact `tvg-id` (with `@suffix` stripped)

`normalizeName()` stripped tokens like `usa` / `uk` / `hd`, so **US and UK cousins** shared the same key (e.g. Discovery USA ≈ Discovery UK). Short brands and language editions (`ESPN` vs `ESPN Deportes`, `CNN` vs `CNN Türk`) could also collide when labels collapsed.

## New scorer (score ≥ 70)

`SupplementMatchScorer` builds signals per side:

| Signal | Examples |
|--------|----------|
| Core name | Quality + region words stripped; language edition words recorded separately |
| Region | `#us` / 🇺🇸 / `tvg-id` `.us` / playlist `playlist_usa.m3u8` / name `USA` |
| Language markers | `deportes`, `türk`, `español`, … |

Rules of thumb:

- **Exact tvg-id** (including `@HD`) scores 100 first — authoritative even when a candidate country hint is `INT`/`WW`
- **`INT` / `WW`** (and aliases `WORLD` / `GLOBAL` → `WW`) are wildcard regions compatible with any concrete region
- **Region conflict → never match** (concrete regions only)
- **Language marker mismatch → never match** (skipped for exact tvg-id hits)
- **Core names must be identical** (no fuzzy substring; avoids `CNN` ⊂ `CNN Türk` after stripping)
- **Short cores** (≤4 letters) need region agreement or an exact quality-stripped label

Skip-dupes and Merge-fallbacks both use this scorer.

## Manual corrections (Settings → Channel backups…)

TV/Fire Stick friendly flow:

1. Open **Settings → Channel backups…**
2. Search a DaddyLive channel (or pick one that already has backups)
3. On the detail screen:
   - **Remove this backup** — drop it now
   - **Remove + never auto-match again** — also denylist the pair across refreshes
   - **Suggested matches** — Accept / Reject with confidence %
   - **Add a backup** — search Free-TV / iptv-org / ntv / dulo and attach manually

Overrides live in `files/supplement/consolidation_overrides.json` (manual attachments + denylist) and are re-applied after every supplement sync.

**Tip:** Wrong automatic backup → Channel backups → remove or block. You do not need to turn off Merge fallbacks.

## Soft dashboard note

When backups are attached, health subtitle may show `Backups: auto · N channels` (informational only).

## How backups reach TiviMate (3.0.36+)

Consolidate matching still runs the same way. Exposure to the player is **opt-in**:

| Playlist | Path | Stream resolve |
|----------|------|----------------|
| **TiviMate (fast)** — recommended | `/tivimate.m3u` (aliases `/tivimate`, `/tivimate.m3u8`) | Direct DaddyLive media playlist via `/tivimate-stream/{id}.m3u8` — snappy zaps; **no** multi-variant master |
| **TiviMate Smart (backups)** | `/tivimate-smart.m3u` (aliases `/tivimate-smart`, `/tivimate-smart.m3u8`) | Channels with backups use `/tivimate-smart-stream/{id}.m3u8` multi-variant master (`#EXT-X-STREAM-INF` → `/daddy-fallback/...`) |

Default dashboard / setup / QR copy remains the **fast** URL. Use Smart only when you want ExoPlayer failover across consolidate backups.

## Admin API (optional)

When the gateway is running:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/backups?channelId=` | List attached backups for a DaddyLive id |
| POST | `/api/v1/backups/attach` | Body: `{ "daddyChannelId", "supplementId" }` |
| POST | `/api/v1/backups/remove` | Body: `{ "daddyChannelId", "fingerprint", "deny": true\|false }` |

## Related code

- `SupplementMatchScorer.kt` — scoring
- `SupplementImportMatcher.kt` — indexes + resolve
- `SupplementImportModeMigration.kt` — default flip for untouched installs
- `ConsolidationOverrideStore.kt` — persistence + apply
- `ChannelBackupsActivity` / `ChannelBackupDetailActivity` — UI

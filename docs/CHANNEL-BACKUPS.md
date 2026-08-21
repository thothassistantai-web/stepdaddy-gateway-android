# Channel backups & consolidation matching

How StepDaddy decides when a Free-TV / iptv-org / ntv / dulo stream is the **same channel** as a DaddyLive row, and how to fix mistakes.

## Defaults

- Import mode stays **All channels** (full catalog) unless you change it per provider in Settings.
- **Merge fallbacks** is optional. It uses the new scorer — it does **not** silently turn itself on for existing installs.

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

- **Region conflict → never match**
- **Language marker mismatch → never match**
- **Core names must be identical** (no fuzzy substring; avoids `CNN` ⊂ `CNN Türk` after stripping)
- **Short cores** (≤4 letters) need region agreement or an exact quality-stripped label
- **Exact tvg-id** (including `@HD`) scores 100 when regions are compatible

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
- `ConsolidationOverrideStore.kt` — persistence + apply
- `ChannelBackupsActivity` / `ChannelBackupDetailActivity` — UI

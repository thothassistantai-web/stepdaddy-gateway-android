# StepDaddy Gateway 3.0.38

versionCode: 30038

## Highlights

- **Full catalog is the default again** — supplement providers import every row as separate playlist entries. Merge fallbacks stays available in Settings; use Smart playlist `/tivimate-smart` when you want backup failover.
- **Optional vs mandatory updates** — normal releases stay optional (dismissible). Maintainers can mark emergency builds `updateType: "mandatory"` in `update-manifest.json`.
- Keeps **3.0.37** scorer fixes (exact `tvg-id`, `INT`/`WW` wildcards) — do not regress to an older scorer by installing a clobbered 3.0.37.

## Migration

Untouched installs that were auto-flipped to Merge fallbacks (no `*_import_mode_user_set`) move back to Full catalog. Explicit Merge / Skip / Full choices are preserved.

## Maintainer: mandatory emergency APK

```json
{
  "updateType": "mandatory",
  "mandatory": true,
  "title": "Security update required",
  "message": "Please install this build to continue.",
  "minSupportedVersionCode": 30038
}
```

Or: `UPDATE_TYPE=mandatory ./scripts/build-release.sh`

This release publishes with `"updateType": "optional"`.

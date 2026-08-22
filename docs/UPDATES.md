# In-app updates

StepDaddy Gateway can check for newer APKs without the Play Store. Distribution is via **GitHub Releases** today; **Google Drive** is stubbed for a future release.

## Signing key migration (3.0.28)

The previous **release** signing key was lost. Android **cannot** update `com.thothassistant.stepdaddy.gateway` in-place when the APK signer changes.

| Device state | Can OTA? | What to do |
|--------------|----------|------------|
| Old release (lost cert `ede8ca7d…`) | **No** | Uninstall release → install newly signed `*-release.apk`, **or** install debug for continued OTA |
| Debug (`…gateway.debug`) | **Yes** (debug→debug) | Keep using OTA, or **Settings → Graduate to Release** |
| New signed release (cert `94:91:41:8C…`) | **Yes** (release→release) | Normal OTA forever after |

```bash
# Old stranded release → new signed release
adb uninstall com.thothassistant.stepdaddy.gateway
adb install -r stepdaddy-gateway-3.0.28-release.apk
```

Debug and release are **different applicationIds**. Graduating is uninstall/side-by-side + open the release app — not a silent PackageManager conversion. Settings/data do not transfer between packages.

Maintainer keystore backup: [KEYSTORE-BACKUP.md](KEYSTORE-BACKUP.md).

## Update manifest

The app reads a JSON file with this shape:

```json
{
  "versionCode": 30038,
  "versionName": "3.0.38",
  "apkUrl": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v3.0.38/stepdaddy-gateway-3.0.38-release.apk",
  "apkUrlDebug": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v3.0.38/stepdaddy-gateway-3.0.38-debug.apk",
  "apkSha256": "<sha256-of-release-apk>",
  "apkSha256Debug": "<sha256-of-debug-apk>",
  "releaseNotes": "Full catalog default + optional/mandatory updates.",
  "updateType": "optional",
  "mandatory": false
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `versionCode` | Yes | Must be greater than the installed build to prompt |
| `versionName` | Yes | Shown in the update dialog |
| `apkUrl` | Yes | Stable / release package (`com.thothassistant.stepdaddy.gateway`) |
| `apkUrlDebug` | No | Debug package (`com.thothassistant.stepdaddy.gateway.debug`); used when the installed app is a debug build |
| `apkSha256` | No | SHA-256 checksum for `apkUrl` (release) |
| `apkSha256Debug` | No | SHA-256 checksum for `apkUrlDebug` |
| `releaseNotes` | No | Markdown/plain text appended in the dialog |
| `updateType` | No | `"optional"` (default) or `"mandatory"` |
| `mandatory` | No | Legacy boolean; `true` forces mandatory (same as `updateType: "mandatory"`) |
| `minSupportedVersionCode` | No | If installed `versionCode` is below this, treat as mandatory even when optional |
| `minVersionCode` | No | Legacy alias of `minSupportedVersionCode` |
| `title` | No | Dialog title override |
| `message` | No | Dialog body override (release notes still appended when different) |

Example file in repo: [release/update-manifest.example.json](../release/update-manifest.example.json).

### Optional vs mandatory (user experience)

| Policy | User sees | Can dismiss? |
|--------|-----------|--------------|
| **Optional** (default) | Dashboard footer “Optional update…”, dismissible dialog with **Later** | Yes — check again next launch / periodic |
| **Mandatory** | Footer “Required update…”, non-cancelable dialog, install dialog without Later | Soft-block: dialog reappears on resume until installed |

Boot + dashboard resume + 6h periodic check (when auto-check is on) all use `AppUpdateCoordinator`.

### Maintainer: ship a mandatory emergency APK

1. Build/publish as usual (`scripts/build-release.sh` / `scripts/publish-github-release.sh`).
2. Edit `release/update-manifest.json` (or set `UPDATE_TYPE=mandatory` before `build-release.sh`) so the published asset includes:

```json
{
  "updateType": "mandatory",
  "mandatory": true,
  "title": "Security update required",
  "message": "Please install this build to keep the gateway working.",
  "minSupportedVersionCode": 30038
}
```

3. `minSupportedVersionCode` alone also forces mandatory for older installs even if `updateType` stays `"optional"`.
4. Normal releases should leave `"updateType": "optional"` / `"mandatory": false` (the release script default).

See [RELEASE.md](RELEASE.md) for the full publish checklist.

## Primary channel — GitHub Releases

1. Settings → **Update manifest URL** → paste the GitHub API releases URL or latest release page API endpoint, e.g.  
   `https://api.github.com/repos/thothassistantai-web/stepdaddy-gateway-android/releases/latest`
2. The app parses release assets: prefers `update-manifest.json`, else the first matching `.apk` asset (`*-release.apk` on stable builds, `*-debug.apk` on debug builds) plus `versionCode` from the release body (`versionCode: 2`) or tag (`v1.0.0+2`).

See [RELEASE.md](RELEASE.md) for maintainer release steps.

## Fallback channel — Google Drive (stub)

Settings exposes **Google Drive folder URL** (`update_drive_folder_url`). Behavior today:

1. If the primary manifest URL fails or is empty, the app tries `{folderUrl}/update-manifest.json`.
2. **No Google Drive API integration yet** — the folder must be world-readable (anyone with the link) and host a static `update-manifest.json` at that path.

### Planned (next app update)

`AppUpdateRepository` will gain a Drive API path for private folders:

- OAuth or service-account flow (TBD)
- List folder → find latest `update-manifest.json` and APK
- Documented setup in this file

Until then, use GitHub Releases or a public static URL.

## Related remote config

- [DOMAIN-RELAY.md](DOMAIN-RELAY.md) — DaddyLive host overlay (`domain-relay.json`)
- [VOD-CATALOG-RELAY.md](VOD-CATALOG-RELAY.md) — live movie/show overlay (`vod-catalog-relay.json`)

## User settings

| Setting | Default | Description |
|---------|---------|-------------|
| Auto-check updates | On | Periodic check when dashboard opens |
| Auto-download | Off | Download APK in background; user still confirms install |
| Manifest URL | Empty | Primary update source |
| Drive folder URL | Empty | Fallback stub |
| Graduate to Release | Debug only | Download signed release APK (different package) |

## Security

- Only install updates from sources you trust.
- Release APKs from **3.0.28+** are signed with the new keystore documented in [KEYSTORE-BACKUP.md](KEYSTORE-BACKUP.md).
- If a GitHub token was ever pasted into chat or committed, **rotate it immediately** in GitHub → Settings → Developer settings.

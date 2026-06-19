# In-app updates

StepDaddy Gateway can check for newer APKs without the Play Store. Distribution is via **GitHub Releases** today; **Google Drive** is stubbed for a future release.

## Update manifest

The app reads a JSON file with this shape:

```json
{
  "versionCode": 2,
  "versionName": "1.0.0",
  "apkUrl": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v1.0.0/stepdaddy-gateway-1.0.0-debug.apk",
  "releaseNotes": "Initial public release."
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `versionCode` | Yes | Must be greater than the installed build to prompt |
| `versionName` | Yes | Shown in the update dialog |
| `apkUrl` | Yes | Direct HTTPS link to an `.apk` |
| `releaseNotes` | No | Markdown/plain text in the dialog |

Example file in repo: [release/update-manifest.example.json](../release/update-manifest.example.json).

## Primary channel — GitHub Releases

1. Settings → **Update manifest URL** → paste the GitHub API releases URL or latest release page API endpoint, e.g.  
   `https://api.github.com/repos/thothassistantai-web/stepdaddy-gateway-android/releases/latest`
2. The app parses release assets: prefers `update-manifest.json`, else the first `.apk` asset plus `versionCode` from the release body (`versionCode: 2`) or tag (`v1.0.0+2`).

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

## User settings

| Setting | Default | Description |
|---------|---------|-------------|
| Auto-check updates | On | Periodic check when dashboard opens |
| Auto-download | Off | Download APK in background; user still confirms install |
| Manifest URL | Empty | Primary update source |
| Drive folder URL | Empty | Fallback stub |

## Security

- Only install updates from sources you trust.
- APKs from this project are **debug/unsigned** until you configure your own release keystore.
- If a GitHub token was ever pasted into chat or committed, **rotate it immediately** in GitHub → Settings → Developer settings.

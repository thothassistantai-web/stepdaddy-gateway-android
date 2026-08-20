# AFTV Downloader codes

Enter these **numeric codes** in the [Downloader](https://www.aftvnews.com/downloader/) app (AFTVnews) on Fire TV / Android TV. Do **not** type the full URL unless Downloader asks for one.

| Build | Downloader code | Package | Stable APK URL |
|-------|-----------------|---------|----------------|
| **Release** (production) | `TBD (register once — see below)` | `com.thothassistant.stepdaddy.gateway` | [stepdaddy-gateway-release.apk](https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-release.apk) |
| **Debug** (dev / OTA bridge) | `TBD (register once — see below)` | `com.thothassistant.stepdaddy.gateway.debug` | [stepdaddy-gateway-debug.apk](https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-debug.apk) |

Short links (optional): release `(pending)` · debug `(pending)`

## How it works

1. Each GitHub release uploads **versionless** assets (`stepdaddy-gateway-release.apk`, `stepdaddy-gateway-debug.apk`) alongside the versioned APKs.
2. GitHub’s `/releases/latest/download/<name>` always redirects to those assets on the newest release.
3. AFTVnews shortener codes are created **once** pointing at those stable URLs (AFTVnews cannot edit destinations and has no public API / captcha gate).
4. This file + `release/aftv-codes.json` are republished in every release body so themes / install docs stay current.

Machine-readable: [`aftv-codes.json`](./aftv-codes.json) · Docs: [`docs/AFTV-DOWNLOADER.md`](../docs/AFTV-DOWNLOADER.md)

## One-time registration (maintainer)

Only needed when codes are empty or you intentionally rotate to new stable URLs:

1. Open https://go.aftvnews.com/
2. Shorten: `https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-release.apk` → copy the numeric code
3. Shorten: `https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-debug.apk` → copy the numeric code
4. Save them:

```bash
bash scripts/aftv-shortener.sh --set-codes <RELEASE_CODE> <DEBUG_CODE>
git add release/aftv-codes.json release/AFTV-CODES.md
git commit -m "docs: record permanent AFTV Downloader codes"
```

Do **not** re-shorten the same URL (AFTVnews returns the existing code). Do **not** point codes at versioned asset URLs.

## Verify stable downloads

```bash
bash scripts/aftv-shortener.sh --verify
```

# Release process

How to cut a **StepDaddy Gateway** Android release for sideload, GitHub Releases, or Play Store upload.

## Version bump

Edit [`STEPDADDY_VERSION`](../STEPDADDY_VERSION) (canonical in this repo; monorepo checkouts may also use `../STEPDADDY_VERSION`), then mirror values in `app/build.gradle.kts` defaults if needed:

```kotlin
versionCode = 30000    // monotonic integer — required for updates (see STEPDADDY_VERSION)
versionName = "3.0.0"  // user-visible semver
```

Verify alignment: `./scripts/verify-stepdaddy-version.sh`

Update [CHANGELOG.md](../CHANGELOG.md) — move `Unreleased` items into a dated section.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android SDK | API 34 (`ANDROID_HOME` set) |
| Release keystore | Required for signed APK/AAB (not in repo) |

Optional: `keystore.properties` at project root (gitignored):

```properties
storeFile=/secure/path/stepdaddy-release.jks
storePassword=<secret>
keyAlias=stepdaddy
keyPassword=<secret>
```

## Build commands

### All-in-one script

```bash
cd stepdaddy-android
export ANDROID_HOME=~/Android/Sdk
./scripts/build-release.sh
```

Produces:

| Artifact | Path |
|----------|------|
| Release APK (unsigned if no keystore) | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Signed APK (if keystore configured) | `app/build/outputs/apk/release/app-release.apk` |
| Release AAB (Play Store) | `app/build/outputs/bundle/release/app-release.aab` |
| Update manifest (optional) | `release/update-manifest.json` |

### Manual Gradle

```bash
./gradlew clean testReleaseUnitTest assembleRelease bundleRelease
```

## Signing

### Option A — `keystore.properties` (recommended)

Active keystore after the 3.0.28 migration: see [KEYSTORE-BACKUP.md](KEYSTORE-BACKUP.md). Never regenerate unless you accept stranding all existing release installs.

```bash
# Restore passwords from keyring (optional)
source scripts/keystore-from-keyring.sh
```

`scripts/build-release.sh` reads `keystore.properties` and configures signing via Gradle.

### Option B — Post-sign unsigned APK

```bash
zipalign -v -p 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks keystore/stepdaddy-release.jks --out app-release-signed.apk aligned.apk
apksigner verify --print-certs app-release-signed.apk
```

**Never commit** `.jks` files or passwords. **Never lose** the current keystore.

## TiviMate Daddy APK signing (suite note)

Gateway releases ship a catalog URL to **TiviMate Daddy** APKs built in [tivimate-daddy](https://github.com/thothassistantai-web/tivimate-daddy). That patch uses a **separate** self-signed keystore at `stepdaddy-patch/out/stepdaddy.keystore` (not the Gateway release keystore above).

- **2.0.0** TiviMate Daddy was signed with a **new** patch keystore. Fleet sticks on older StepDaddy TiviMate must **uninstall** `ar.tvplayer.tv` before installing 2.0.0+.
- Patch maintainers must **reuse** the same `stepdaddy.keystore` across releases and **back it up** (gitignored — see tivimate-daddy `docs/RELEASE.md`).

When updating `install_apps_catalog.json` for a new TiviMate release, confirm signing-key notes are reflected in that repo's GitHub release body.

## GitHub Release

Preferred (builds notes + uploads versioned **and** versionless APKs + AFTV section):

```bash
./scripts/build-release.sh
./scripts/publish-github-release.sh
```

Manual equivalent:

1. Tag: `git tag -a v1.0.1 -m "StepDaddy Gateway 1.0.1"`
2. Push tag: `git push origin v1.0.1`
3. Create release via `gh release create` with assets:
   - `stepdaddy-gateway-<version>-release.apk` (**required** for stable sideload / in-app updater — matches `com.thothassistant.stepdaddy.gateway`)
   - `stepdaddy-gateway-<version>-debug.apk` (dev package `com.thothassistant.stepdaddy.gateway.debug`)
   - `stepdaddy-gateway-release.apk` / `stepdaddy-gateway-debug.apk` (**versionless** — required for permanent AFTV Downloader codes via `/releases/latest/download/…`)
   - `update-manifest.json` (for in-app updater; `apkUrl` → release APK, `apkUrlDebug` → debug APK)
   - `AFTV-CODES.md` / `aftv-codes.json` (Downloader numeric codes)
   - Optional: `app-release.aab` for Play Console

### AFTV Downloader codes

Permanent Fire TV short codes point at the versionless latest URLs. AFTVnews has **no API** (reCAPTCHA + immutable destinations), so register codes **once**. See [AFTV-DOWNLOADER.md](AFTV-DOWNLOADER.md) and [release/AFTV-CODES.md](../release/AFTV-CODES.md).

### `update-manifest.json` schema

```json
{
  "versionCode": 30038,
  "versionName": "3.0.38",
  "apkUrl": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v3.0.38/stepdaddy-gateway-3.0.38-release.apk",
  "apkUrlDebug": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v3.0.38/stepdaddy-gateway-3.0.38-debug.apk",
  "apkSha256": "<sha256-of-release-apk>",
  "apkSha256Debug": "<sha256-of-debug-apk>",
  "releaseNotes": "Boot reliability fixes.",
  "updateType": "optional",
  "mandatory": false
}
```

- Default releases: `"updateType": "optional"` (script default).
- Emergency force-update: set `"updateType": "mandatory"` and/or `"mandatory": true`, optionally with `minSupportedVersionCode`, `title`, and `message`. See [UPDATES.md](UPDATES.md).

The in-app updater also accepts GitHub Releases API responses when `DEFAULT_UPDATE_MANIFEST_URL` points at `.../releases/latest`.

## Pre-release checklist

- [ ] `./gradlew testReleaseUnitTest` passes
- [ ] Version code/name bumped
- [ ] CHANGELOG updated
- [ ] Signed APK tested on target stick (install, boot, TiviMate playlist)
- [ ] `/health` returns expected version
- [ ] LEGAL.md / DISCLAIMER.md unchanged or reviewed
- [ ] No secrets in APK or release assets

## Debug vs release packages

| Build | Package ID | APK path |
|-------|------------|----------|
| Debug | `com.thothassistant.stepdaddy.gateway.debug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `com.thothassistant.stepdaddy.gateway` | `app/build/outputs/apk/release/app-release.apk` |

Debug and release can coexist only if package IDs differ (they do).

## Play Store

Upload `app-release.aab` to Play Console internal testing first. See [PLAY_STORE_LISTING.md](../PLAY_STORE_LISTING.md) and [SCREENSHOT-CHECKLIST.md](SCREENSHOT-CHECKLIST.md).

## Linux / web sibling releases

Desktop gateway releases are cut from [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD) or local `stepdaddy-web`:

```bash
cd ~/Programs/stepdaddy-web
./scripts/build-release.sh beta-YYYYMMDD
# → release/stepdaddy-livehd-beta-YYYYMMDD.tar.gz
```

Deb packaging notes: [LINUX-PACKAGING.md](LINUX-PACKAGING.md).

## Rollback

Ship previous APK via GitHub Release asset; users with in-app updates need `versionCode` higher than broken build or manual reinstall.

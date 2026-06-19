# Release process

How to cut a **StepDaddy Gateway** Android release for sideload, GitHub Releases, or Play Store upload.

## Version bump

Edit `app/build.gradle.kts`:

```kotlin
versionCode = 3        // monotonic integer — required for updates
versionName = "1.0.1"  // user-visible semver
```

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

Create keystore once:

```bash
keytool -genkey -v -keystore stepdaddy-release.jks \
  -alias stepdaddy -keyalg RSA -keysize 2048 -validity 10000
```

`scripts/build-release.sh` reads `keystore.properties` and configures signing via Gradle.

### Option B — Post-sign unsigned APK

```bash
zipalign -v -p 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks stepdaddy-release.jks --out app-release-signed.apk aligned.apk
apksigner verify app-release-signed.apk
```

**Never commit** `.jks` files or passwords.

## GitHub Release

1. Tag: `git tag -a v1.0.1 -m "StepDaddy Gateway 1.0.1"`
2. Push tag: `git push origin v1.0.1`
3. Create release via `gh release create` with assets:
   - `stepdaddy-gateway-<version>-debug.apk` (**required** for sideload / in-app updater — matches `com.thothassistant.stepdaddy.gateway.debug`)
   - `update-manifest.json` (for in-app updater; `apkUrl` must point at the debug APK above)
   - Optional: signed `app-release.apk` / `app-release.aab` for Play Console

### `update-manifest.json` schema

```json
{
  "versionCode": 3,
  "versionName": "1.0.1",
  "apkUrl": "https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/download/v1.0.4/stepdaddy-gateway-1.0.4-debug.apk",
  "releaseNotes": "Boot reliability fixes.",
  "mandatory": false
}
```

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

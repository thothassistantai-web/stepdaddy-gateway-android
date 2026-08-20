# StepDaddy Gateway 3.0.28

**Signing key migration release** — versionCode: 30028

## Highlights

- **New release signing certificate** (previous release key lost). Fresh signed `*-release.apk` restores OTA for **new** release installs only.
- **Debug OTA unchanged** for `com.thothassistant.stepdaddy.gateway.debug` (bridge channel).
- **Graduate to Release** in Settings / About on debug builds.
- Old release installs on the lost cert **cannot** OTA — uninstall then install this APK (or use debug).

## Honest Android constraints

- Same package + different signer → install failure (not recoverable via OTA).
- Debug (`…gateway.debug`) ≠ release (`…gateway`) — PackageManager will not convert one into the other.

## Migration paths

1. **Old release (stranded):** `adb uninstall com.thothassistant.stepdaddy.gateway` then install `stepdaddy-gateway-3.0.28-release.apk`.
2. **Debug bridge:** Keep OTA on debug, or use **Graduate to Release**.
3. **New signed release:** Future OTA works with this keystore — back it up (`docs/KEYSTORE-BACKUP.md`).

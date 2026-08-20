# Release signing keystore

Self-signed release keystores are **not committed** (see `.gitignore`).

## Current key (do not lose)

After the 2026-08-20 migration, the active release keystore is:

- Path: `keystore/stepdaddy-release.jks`
- Alias: `stepdaddy`
- SHA-256: `94:91:41:8C:31:B1:A9:A3:60:84:D8:BD:97:F2:E0:80:E4:1E:92:6C:46:0A:DE:D5:5E:F2:2F:E4:6E:C3:39:75`

Full backup / restore / GitHub secrets checklist: **[docs/KEYSTORE-BACKUP.md](../docs/KEYSTORE-BACKUP.md)**.

## Create (historical — only if starting fresh)

```bash
keytool -genkeypair -v \
  -keystore keystore/stepdaddy-release.jks \
  -alias stepdaddy -keyalg RSA -keysize 2048 -validity 10000
```

Then add `keystore.properties` at the repo root (gitignored):

```properties
storeFile=keystore/stepdaddy-release.jks
storePassword=<secret>
keyAlias=stepdaddy
keyPassword=<secret>
```

Restore passwords from the Linux keyring: `source scripts/keystore-from-keyring.sh`

Build signed release: `./gradlew :app:assembleRelease` or `./scripts/build-release.sh`.

# Release signing keystore

Self-signed release keystores are **not committed** (see `.gitignore`). Create once per maintainer machine:

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

Build signed release: `./gradlew :app:assembleRelease` or `./scripts/build-release.sh`.

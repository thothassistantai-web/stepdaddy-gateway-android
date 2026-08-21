# Release keystore backup & recovery

**Critical:** Losing this keystore permanently strands all `com.thothassistant.stepdaddy.gateway` installs signed with it. Android **cannot** OTA-update the same package with a different signing certificate. Treat this file like a root CA.

## Current key (2026-08-20 migration)

| Field | Value |
|-------|--------|
| File (local) | `keystore/stepdaddy-release.jks` (gitignored) |
| Alias | `stepdaddy` |
| Algorithm | RSA 2048, SHA256withRSA |
| Validity | ~10000 days from generation |
| **SHA-256 fingerprint** | `94:91:41:8C:31:B1:A9:A3:60:84:D8:BD:97:F2:E0:80:E4:1E:92:6C:46:0A:DE:D5:5E:F2:2F:E4:6E:C3:39:75` |

Old release key (`ede8ca7d…`) is **lost** and will never sign again. Existing installs on that cert need uninstall + reinstall (see [UPDATES.md](UPDATES.md#signing-key-migration-308)).

## What is stored where

| Location | Contents | Notes |
|----------|----------|-------|
| `keystore/stepdaddy-release.jks` | Private key + cert | Mode `600`; never commit |
| `keystore.properties` (repo root) | Paths + passwords | Gitignored; mode `600` |
| Linux keyring (`secret-tool`) | store/key passwords + alias | Attributes below |
| `$HOME/.config/stepdaddy-gateway/release-keystore.env` | Passwords + path | Mode `600`; machine-local |
| GitHub Actions secrets | Base64 keystore + passwords | See checklist |

### Linux keyring recovery

```bash
secret-tool lookup application stepdaddy-gateway attribute store-password
secret-tool lookup application stepdaddy-gateway attribute key-password
secret-tool lookup application stepdaddy-gateway attribute key-alias
```

For **dulo.cx Live TV** account/JWT recovery (separate keyring attrs under `service=dulo.cx`), see [DULO-AUTH.md](DULO-AUTH.md). Never store those secrets in this file.

Re-store:

```bash
printf '%s' "$STORE_PASSWORD" | secret-tool store --label='StepDaddy Gateway release keystore password' \
  application stepdaddy-gateway service android-release-keystore attribute store-password
```

### GitHub secrets (set 2026-08-20)

| Secret | Purpose |
|--------|---------|
| `ANDROID_KEYSTORE_BASE64` / `KEYSTORE_BASE64` | `base64 -w0 keystore/stepdaddy-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` / `STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_PASSWORD` / `KEY_PASSWORD` | Key password (same as store for PKCS12) |
| `ANDROID_KEY_ALIAS` / `KEY_ALIAS` | `stepdaddy` |

Verify names only: `gh secret list -R thothassistantai-web/stepdaddy-gateway-android`

## Offline backup checklist

- [ ] Copy `stepdaddy-release.jks` to encrypted offline media (USB / password manager attachment)
- [ ] Record SHA-256 fingerprint (above) in the same vault
- [ ] Confirm GitHub secrets exist (not values)
- [ ] Confirm `keystore.properties` and `*.jks` are gitignored
- [ ] Never paste passwords into chat, issues, or commits

## Restore on a new machine

```bash
cd /path/to/stepdaddy-gateway-android
# From GH secret or offline backup:
echo "$KEYSTORE_BASE64" | base64 -d > keystore/stepdaddy-release.jks
chmod 600 keystore/stepdaddy-release.jks

cat > keystore.properties <<EOF
storeFile=keystore/stepdaddy-release.jks
storePassword=<from vault>
keyAlias=stepdaddy
keyPassword=<from vault>
EOF
chmod 600 keystore.properties

keytool -list -v -keystore keystore/stepdaddy-release.jks -alias stepdaddy \
  | grep SHA256
# Must match 94:91:41:8C:…:39:75
```

Then: `./scripts/build-release.sh`

## Verify a release APK

```bash
apksigner verify --print-certs release/stepdaddy-gateway-*-release.apk
```

Signer SHA-256 must match the fingerprint above (colon format may vary by tool).

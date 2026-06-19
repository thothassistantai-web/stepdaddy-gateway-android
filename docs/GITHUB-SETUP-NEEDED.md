# GitHub & credentials setup

**Action required from repository owner.** Provide the items below so CI, releases, and optional Google Drive updates can be configured. Do **not** paste secrets in public issues — use GitHub Actions secrets or a password manager share.

---

## 1. GitHub organization access

**Organization:** https://github.com/thothassistantai-web

Please confirm:

- [ ] You have **Owner** or **Admin** role on `thothassistantai-web`
- [ ] A dedicated machine or CI runner can authenticate as a bot user or via PAT

### Personal Access Token (PAT)

Create at: https://github.com/settings/tokens (classic) or fine-grained token scoped to the org.

| Scope | Why |
|-------|-----|
| `repo` (full) | Push tags, create releases, upload APK assets |
| `workflow` | Trigger and update GitHub Actions |
| `read:org` | List org repos, verify membership |
| `write:packages` | Optional — if using GHCR for build caches |

**Fine-grained alternative:** Repository access on the new gateway repo only, with Contents + Actions + Metadata read/write.

**Please provide:**

```
PAT holder GitHub username: _______________
PAT expiration date: _______________
Token stored in: [ ] gh auth login  [ ] GitHub Actions secret GH_PAT  [ ] other: _______
```

---

## 2. Repositories to create / map

Existing org repos (verified 2026-06-18):

| Repo | Current role | Suggested action |
|------|--------------|------------------|
| [stepdaddy-livehd-private](https://github.com/thothassistantai-web/stepdaddy-livehd-private) | Empty / Docker stub | Archive or repurpose for private configs |
| [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD) | Linux + Docker gateway | Keep — desktop releases |
| [StepDaddyLiveHD-Mobile](https://github.com/thothassistantai-web/StepDaddyLiveHD-Mobile) | Termux / FastAPI mobile | Keep — legacy; link to native gateway |
| [stepdaddy-lite-onn](https://github.com/thothassistantai-web/stepdaddy-lite-onn) | ONN lite Python deploy | Keep or merge docs into gateway repo |

**New repo suggestion for this project:**

| Field | Value |
|-------|-------|
| Name | `stepdaddy-gateway-android` |
| Visibility | Public (or private until 1.0 QA) |
| Description | Native Android TV IPTV gateway (Kotlin/Ktor) — TiviMate playlists, light EPG, boot auto-start |
| Default branch | `main` |

**Please confirm:**

- [ ] Create `stepdaddy-gateway-android` (yes/no)
- [ ] Preferred repo name if different: _______________
- [ ] Push local `stepdaddy-android/` git history to new repo (yes/no)
- [ ] Mark `StepDaddyLiveHD-Mobile` as deprecated in README (yes/no)

See [MIGRATION-PLAN.md](MIGRATION-PLAN.md) for full mapping.

---

## 3. GitHub Actions secrets (CI)

When adding `.github/workflows/android-release.yml`:

| Secret name | Contents |
|-------------|----------|
| `ANDROID_KEYSTORE_BASE64` | Base64 of `stepdaddy-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | e.g. `stepdaddy` |
| `ANDROID_KEY_PASSWORD` | Key password |
| `GH_PAT` | PAT with `repo` + `workflow` for release upload |

**Please provide (secure channel only):**

- [ ] Keystore file generated (yes/no) — if no, run `keytool` per [RELEASE.md](RELEASE.md)
- [ ] Secrets added to repo Settings → Secrets → Actions (yes/no)

---

## 4. Android release signing keystore

Required for Play Store and trusted sideload updates.

```bash
keytool -genkey -v -keystore stepdaddy-release.jks \
  -alias stepdaddy -keyalg RSA -keysize 2048 -validity 10000
```

**Please provide:**

| Question | Your answer |
|----------|-------------|
| Keystore already exists? | yes / no |
| Safe backup location | _______________ |
| `keyAlias` | _______________ |
| Same key for all future releases? | yes (required for Play) |

Store locally as `keystore.properties` (gitignored) — see [RELEASE.md](RELEASE.md).

---

## 5. Google Play Console (optional)

| Item | Needed |
|------|--------|
| Google Play Developer account ($25 one-time) | yes / no |
| App package `com.thothassistant.stepdaddy.gateway` reserved | yes / no |
| Internal testing track email list | _______________ |

---

## 6. Google Drive API (optional — in-app updates)

The gateway can fetch `update-manifest.json` from a **public** Drive folder (`AppUpdateRepository`).

**Option A — Public folder (no API key)**

- Upload `update-manifest.json` + `app-release.apk` to a folder
- Set folder sharing: *Anyone with the link → Viewer*
- Paste folder URL in app Settings → Updates

**Option B — Drive API (private folder)**

| Credential | Purpose |
|------------|---------|
| Google Cloud project | Enable Drive API v3 |
| Service account JSON | CI upload to folder |
| Folder ID | `DEFAULT_UPDATE_DRIVE_FOLDER_URL` |

**Please provide:**

- [ ] Use GitHub Releases only (simplest)
- [ ] Use public Drive folder — folder URL: _______________
- [ ] Use Drive API — service account email: _______________

---

## 7. Play Store listing assets

See [PLAY_STORE_LISTING.md](../PLAY_STORE_LISTING.md) and [SCREENSHOT-CHECKLIST.md](SCREENSHOT-CHECKLIST.md).

**Please provide:**

- [ ] Feature graphic 1024×500
- [ ] TV banner 1280×720 (may reuse `@drawable/tv_banner`)
- [ ] Privacy policy URL (required for Play): _______________
- [ ] Support email: _______________

---

## 8. Device / test credentials (optional)

Not stored in repo. For maintainer QA only:

| Item | Value |
|------|-------|
| ONN stick ADB serial | _______________ |
| Test Wi-Fi SSID (if needed) | _______________ |

---

## 9. What we will NOT do without explicit PAT + confirmation

- Delete or rename existing org repos
- Force-push to `main` / `master`
- Publish to Play Store on your behalf without Console access

---

## Quick reply template

Copy, fill, and send securely:

```
1. PAT: stored in gh auth / Actions secret (yes/no)
2. New repo name: stepdaddy-gateway-android
3. Keystore: exists (yes/no), backup at _______
4. Updates via: GitHub Releases / Drive / both
5. Play Console: yes/no, privacy URL _______
6. Deprecate StepDaddyLiveHD-Mobile README: yes/no
```

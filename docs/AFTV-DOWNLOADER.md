# AFTV Downloader codes (Fire TV / Android TV)

Sideload **StepDaddy Gateway** without typing a long GitHub URL: install [Downloader by AFTVnews](https://www.aftvnews.com/downloader/) from the Amazon Appstore / Play Store, then enter a short **numeric code**.

## Codes (permanent)

| Build | Enter in Downloader | Package |
|-------|---------------------|---------|
| **Release** (production) | See [release/AFTV-CODES.md](../release/AFTV-CODES.md) | `com.thothassistant.stepdaddy.gateway` |
| **Debug** (dev / OTA bridge) | See [release/AFTV-CODES.md](../release/AFTV-CODES.md) | `com.thothassistant.stepdaddy.gateway.debug` |

Machine-readable: [release/aftv-codes.json](../release/aftv-codes.json).

If codes still say **TBD**, a maintainer must complete the [one-time registration](#one-time-maintainer-setup) below. Until then you can open the stable URLs in a browser or use ADB.

## Stable download URLs (always latest)

These never change between releases (GitHub redirects to the newest versionless asset):

| Build | URL |
|-------|-----|
| Release | https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-release.apk |
| Debug | https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/latest/download/stepdaddy-gateway-debug.apk |

## How AFTV codes work

1. Downloader sends the digits you type to the **AFTVnews URL shortener** (`aftv.news` / `go.aftvnews.com`).
2. That service redirects to the long URL registered for the code.
3. **AFTVnews has no public API**, requires **reCAPTCHA** to create codes, and **cannot edit** a code’s destination after creation ([FAQ](https://go.aftvnews.com/p/faq)).
4. Therefore we register **once** against GitHub’s **versionless** `latest/download` URLs and republish the same numbers on every release.

Do **not** create a new AFTV code per version — that breaks “one static set of numbers.”

## User steps (Fire Stick / ONN)

1. Install **Downloader** (developer: AFTVnews).
2. Open Downloader → enter the **release** or **debug** code from `release/AFTV-CODES.md`.
3. Allow the download → install when prompted → enable Unknown sources if asked.
4. Launch **StepDaddy Gateway** and start the server.

Prefer **release** for production fleet sticks. Use **debug** when you need the `.debug` package / in-app OTA bridge (see [UPDATES.md](UPDATES.md)).

## Release automation

| Script | Role |
|--------|------|
| `scripts/build-release.sh` | Builds APKs and copies **versionless** `stepdaddy-gateway-release.apk` / `stepdaddy-gateway-debug.apk` |
| `scripts/aftv-shortener.sh` | Regenerates `release/AFTV-CODES.md`, verifies stable URLs, prints release-note snippet |
| `scripts/publish-github-release.sh` | Uploads versioned + versionless assets and appends AFTV section to the GitHub release body |

```bash
./scripts/build-release.sh
./scripts/publish-github-release.sh
# or only refresh assets on an existing tag:
./scripts/publish-github-release.sh --assets-only
```

## One-time maintainer setup

When `release/aftv-codes.json` has empty `codes`:

1. Confirm stable URLs work: `./scripts/aftv-shortener.sh --verify`
2. Open https://go.aftvnews.com/ and shorten the **release** stable URL → note the digits
3. Shorten the **debug** stable URL → note the digits
4. Save:

```bash
./scripts/aftv-shortener.sh --set-codes <RELEASE_CODE> <DEBUG_CODE>
git add release/aftv-codes.json release/AFTV-CODES.md
git commit -m "docs: record permanent AFTV Downloader codes"
./scripts/publish-github-release.sh --assets-only   # refresh release notes + AFTV assets
```

Optional env form: `AFTV_CODE_RELEASE=… AFTV_CODE_DEBUG=… ./scripts/aftv-shortener.sh --from-env`

There is **no** `AFTV_API_KEY` — captcha blocks automation. If AFTVnews ever ships an API, wire it in `scripts/aftv-shortener.sh` behind an env var without changing the stable-URL strategy.

## Theme / companion apps

No in-repo StreamVault or TiviMate “theme” surface displays these codes today. Treat `release/AFTV-CODES.md` / `aftv-codes.json` and the GitHub release **AFTV Downloader** section as the canonical share sheet for install themes and fleet docs.

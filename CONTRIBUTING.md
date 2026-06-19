# Contributing to StepDaddy Gateway

Thank you for helping improve the native Android gateway. This project is maintained under [thothassistantai-web](https://github.com/thothassistantai-web) on GitHub.

## Before you start

1. Read [LEGAL.md](LEGAL.md) and [DISCLAIMER.md](DISCLAIMER.md) — contributions must not add hosted pirated content or DRM circumvention beyond existing upstream aggregation patterns.
2. Check open issues and [CHANGELOG.md](CHANGELOG.md) for overlap with in-flight work.
3. For large changes, open an issue or discussion first to align on scope.

## Development setup

```bash
cd stepdaddy-android
export ANDROID_HOME=~/Android/Sdk   # or your SDK path
./gradlew testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`  
Package: `com.thothassistant.stepdaddy.gateway.debug`

See [docs/INSTALL.md](docs/INSTALL.md) and [ARCHITECTURE.md](ARCHITECTURE.md) for device deploy and code layout.

## Code style

- **Kotlin** — match existing patterns (coroutines, `GatewayEnvironment`, route modules)
- **Minimal scope** — one logical change per PR; avoid drive-by refactors
- **TV-first UX** — D-pad focus, leanback launcher, readable overlays on 1080p sticks
- **No secrets** — never commit keystores, `.env`, PATs, or device-specific IPs

## Testing

| Layer | Command / action |
|-------|------------------|
| Unit tests | `./gradlew testDebugUnitTest` |
| Debug build | `./gradlew assembleDebug` |
| Device smoke | Install APK, start server, `curl http://<device-ip>:3000/health` |
| Boot path | `scripts/fusa-boot-test.sh` (ONN stick) when available |

Document manual test results in the PR description for UI or boot-lifecycle changes.

## Pull request checklist

- [ ] Builds cleanly (`assembleDebug` minimum; `assembleRelease` if touching signing or manifest)
- [ ] No new linter warnings in touched files
- [ ] Strings / layouts updated if user-visible text changed
- [ ] [CHANGELOG.md](CHANGELOG.md) updated under `Unreleased` for user-facing fixes
- [ ] Docs updated if install paths, URLs, or permissions changed

## Commit messages

Use imperative, concise subjects:

```
fix: retry channel cache on mirror failover
feat: add QR code for remote playlist URL
docs: document release signing in RELEASE.md
```

## Security

Report sensitive issues privately to the org maintainer — do not open public issues for exploit details until patched.

## License

By contributing, you agree your contributions are licensed under the [MIT License](LICENSE).

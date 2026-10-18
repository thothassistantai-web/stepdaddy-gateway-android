# StepDaddy Gateway 3.0.74

Restores playback after the upstream player introduced short-lived HLS playlist tokens and its embed host became unavailable.

- Requests and renews playlist access tokens with the player Origin header; retries a refused token once.
- Tries a live-validated CDN binding when the embed host is unavailable, with normal discovery as fallback.
- Uses the public channel directory when the legacy JSON catalog endpoint returns 404 or 410.
- Includes the 3.0.71 timeout recovery and 3.0.72 watch-page migration fixes.

Validation: release unit tests passed; APK signing certificate and version verified in CI. On-device manifests and MPEG-TS segments returned HTTP 200, with moving video confirmed in TiviMate after token renewal.

Install the release APK over the existing release installation. User data is retained. This release does not include a debug APK.

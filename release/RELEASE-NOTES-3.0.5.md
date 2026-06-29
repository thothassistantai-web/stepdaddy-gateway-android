# StepDaddy Gateway 3.0.5

## Fixed

- **Eastern EPG prioritization** — HBO 2, Showtime, STARZ in Black, and STARZ Kids & Family fetch in a dedicated first tvtv.us pass before general cable gap-fill exhausts the rate limit.
- **tvtv.us rate limits** — general pass capped at 12 channels/build; stronger 429 backoff, 60s pause on sustained 429, skip general pass when exhausted; 6h grid JSON disk cache with stale fallback.

Sideload `stepdaddy-gateway-3.0.5-release.apk` (`com.thothassistant.stepdaddy.gateway`).

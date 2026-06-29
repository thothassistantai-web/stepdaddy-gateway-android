# StepDaddy Gateway 3.0.4

## Fixed

- **Eastern EPG rate limits** — tvtv.us grid fetches retry HTTP 429 with exponential backoff (5s → 10s → 20s) and a longer inter-request delay (2s). HBO 2, Showtime, and STARZ in Black keep real programme titles instead of "Live programming" placeholders when tvtv.us rate-limits grid downloads.

Sideload `stepdaddy-gateway-3.0.4-release.apk` (`com.thothassistant.stepdaddy.gateway`).

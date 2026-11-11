# StepDaddy Gateway 3.0.75

Ships domain-relay **v5** compiled defaults so fresh installs prefer working DaddyLive mirrors without waiting for a relay fetch.

- Primary catalog/stream host defaults to `https://dlhd.st` (ONN-friendly `dlhd.pk` remains in mirror/relay lists).
- Blocks DNS-dead `dlive.sx` in compiled defaults; includes `thedaddy.pk` / `thedaddy.li` aliases.
- Aligns relay/embed fallbacks with published `release/domain-relay.json` v5.
- Adds `scripts/find-dead-streams.sh` plus a local `stream-repair/` inbox for routine offline-stream repair.

Validation: release unit tests; signed APK; domain-relay asset published with the release.

Install the release APK over the existing release installation. User data is retained.

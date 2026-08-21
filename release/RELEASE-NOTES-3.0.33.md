# StepDaddy Gateway 3.0.33

versionCode: 30033

## Fixes

- Consolidated backup streams (`/daddy-fallback`, `/supplement-stream`) now fetch and rewrite real HLS instead of serving bare CDN URL strings (TiviMate/ExoPlayer `ParserException`).
- Fallback media playlists absolutize segment URLs so players no longer request `.ts` under the gateway path (404s).
- Shelf-suffixed VOD series ids (`vod:series:…@shelf`) parse correctly again — no blank `#EXTINF` URL lines.
- M3U attribute escaping uses apostrophes so plot/cast quotes cannot break playlist parse.

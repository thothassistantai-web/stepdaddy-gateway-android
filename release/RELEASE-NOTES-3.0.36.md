# StepDaddy Gateway 3.0.36

## Highlights

- **TiviMate (fast)** — `/tivimate.m3u` and `/tivimate-stream/{id}` always serve direct DaddyLive media playlists (no multi-variant master intercept). Snappy channel changes again.
- **TiviMate Smart (backups)** — new `/tivimate-smart.m3u` points DaddyLive channels at `/tivimate-smart-stream/{id}` multi-variant masters when consolidate backups exist.
- Dashboard / QR / setup show both URLs; recommended default remains fast.

## Verify

```bash
# Fast path: media playlist (segments), not #EXT-X-STREAM-INF master
curl -s http://127.0.0.1:3000/tivimate-stream/51.m3u8 | head -20

# Smart playlist entries use smart-stream URLs
curl -s http://127.0.0.1:3000/tivimate-smart.m3u | grep -m3 tivimate-smart-stream

# Smart stream with backups → multi-variant master
curl -s http://127.0.0.1:3000/tivimate-smart-stream/<id-with-backups>.m3u8 | head -20
```

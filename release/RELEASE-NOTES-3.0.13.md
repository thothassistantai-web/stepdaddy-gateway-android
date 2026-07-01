# StepDaddy Gateway 3.0.13

## xyzstreams US live TV

- Supplement provider for xyzstreams.st Sling-backed feeds (~69 verified static channels)
- Optional **EPG discovery** probes TV Guide for additional live streams (Settings toggle, on by default)
- TV Guide EPG merged into gateway XMLTV; stream proxy at `/xyz-stream/{id}.m3u8`
- Health/dashboard shows catalog vs discovered counts and discovered channel names

## iptv-org playlist control

- **Choose iptv-org playlists…** in Settings — toggle each of the 39 GitHub FAST playlists individually

## VOD expansion (movies + series)

- vsembed movie catalog with Cinemeta metadata
- Series episodes in **📺 Shows** via `/vod/series/...` proxy and `GET /series`
- Moviebox fallback when vsembed embed fails

Sideload `stepdaddy-gateway-3.0.13-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. Settings → enable **xyzstreams** and wait for supplement sync
2. `curl http://<ip>:3000/health | jq '.supplement | {xyzStreamsChannels, xyzStreamsDiscoveredPublished, xyzStreamsDiscoveredLabels}'`
3. Settings → **Choose iptv-org playlists…** — disable one playlist, resync, confirm count drops
4. `curl http://<ip>:3000/tivimate.m3u | grep -c 'xyz:'`
5. `curl http://<ip>:3000/movies` and `curl http://<ip>:3000/series`

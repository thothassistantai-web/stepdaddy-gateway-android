# StepDaddy Gateway 3.0.14

## Xtream live channels for TiviMate

- **`get_live_categories`** and **`get_live_streams`** on `player_api.php` so Xtream login imports live TV (not just Movies/Series VOD)
- Live catalog mirrors gateway M3U groups (~4900+ channels); DaddyLive IDs use `/live/{user}/{pass}/{id}.ts`, supplements use `direct_source` where needed
- VOD Xtream actions (`get_vod_*`, `get_series_*`) no longer require Movies (VOD) to be enabled for auth — live API works independently

Sideload `stepdaddy-gateway-3.0.14-release.apk` (`com.thothassistant.stepdaddy.gateway`).

## Test plan

1. `curl 'http://127.0.0.1:3000/player_api.php?username=admin&password=password&action=get_live_categories'`
2. `curl 'http://127.0.0.1:3000/player_api.php?username=admin&password=password&action=get_live_streams' | head -c 500`
3. TiviMate → add/update Xtream playlist `http://127.0.0.1:3000` / `admin` / `password` with **Include VOD** on — confirm live channels + Movies + Shows populate
4. `curl 'http://127.0.0.1:3000/player_api.php?username=admin&password=password&action=get_series'` — series still present

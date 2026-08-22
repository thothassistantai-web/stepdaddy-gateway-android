# StepDaddy Gateway 3.0.40

## Faster source loading

- FAST EPG no longer blocks Free-TV / ntv / iptv / dulo / Adult Swim (disk index + parallel feed refresh)
- Special Events runs in parallel with other providers
- Warm phone boot refresh defer ~3s (was ~45s) when Daddy catalog is already on disk
- iptv-org: disk cache + ETag/304, concurrency 6, progressive playlist waves
- Sources progress uses real per-source / iptv playlist counters
- Adult Swim probe budget when cache exists; VOD stream probing off critical path

## Notes

First cold sync still downloads iptv-org playlists once; later syncs should be mostly cache/304.

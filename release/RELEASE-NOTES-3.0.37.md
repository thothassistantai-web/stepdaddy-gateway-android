# StepDaddy Gateway 3.0.37

versionCode: 30037

## Highlights

- **Smart consolidate backups** — exact `tvg-id` matches win before region/language filters; `INT`/`WW` country hints are wildcards so Free-TV / dulo international tags still attach to DaddyLive `.us` (etc.) rows.

## Verify

```bash
# After supplement sync + smart consolidate, backup counts should be non-zero
curl -s http://127.0.0.1:3000/health | jq '.supplements // .channelBackups // .'

# Smart playlist should list multi-variant stream URLs when backups exist
curl -s http://127.0.0.1:3000/tivimate-smart.m3u | grep -c tivimate-smart-stream
```

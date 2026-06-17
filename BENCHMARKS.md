# StepDaddy Android Gateway — Performance & Stress Benchmarks

## Run metadata

| Field | Value |
|-------|-------|
| **Test date (UTC)** | 2026-06-17 |
| **Device** | FUSA2541006925 — onn. Full HD Streaming Device (XNA) |
| **Gateway target** | `http://10.161.65.181:3000` (LAN IP; check with `adb shell ip -4 addr show wlan0`) |
| **APK / version** (`GET /health`) | `0.2.0-gateway-mvp-debug` |
| **Channels in playlist** | 1140 |
| **Upstream** | `https://daddylive.org` |
| **Package** | `com.nova.stepdaddylivehd.gateway.debug` |

---

## Boot test methodology (FUSA / ONN stick)

Automated script: `scripts/fusa-boot-test.sh`

### Protocol

1. Grant permissions via adb (`SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, battery whitelist)
2. `pm enable` package if disabled
3. Brief app launch + HOME (sets `startOnBoot` pref); **do not** `force-stop` alone before reboot — use `am kill` instead
4. Reboot stick → `adb wait-for-device` → HOME
5. Poll `http://<wlan0-ip>:3000/health` from host every 2s (device has no curl)
6. On **first health=200**, trigger sequential screencap burst (3 shots, 8s apart — parallel caps cause ANR)
7. Verify `/health`, `/tivimate-playlist.m3u8`, `/epg.xml` return HTTP 200
8. Collect logcat for `BootReceiver`, `GatewayOverlay`, `GatewayServer`, `GatewayStartHelper`
9. Save proof screencap to `fusa-boot-banner-proof.png`

### Learnings encoded in script

| Pitfall | Mitigation |
|---------|------------|
| Overlay permission missing | `appops set … SYSTEM_ALERT_WINDOW allow` before reboot |
| Package disabled after install | `pm enable` |
| Boot receiver cleared by `force-stop` | Use brief launch + HOME, then `am kill` |
| Screencap ANR during boot | Single sequential burst only, triggered on health=200 |
| `adb screencap` slow (15–34s) | Expect long cap latency; don't overlap |
| Fixed-time screencap misses overlay | Trigger on first health=200, not wall-clock guess |

### Boot test results (iterative cycles)

| Cycle | Date (UTC) | Health first 200 | Endpoints | Overlay on home | Notes |
|-------|------------|------------------|-----------|-----------------|-------|
| Baseline (manual) | 2026-06-16 | ~81s | 3/3 | PASS | DISMISS_MS 8s→15s (uncommitted initially) |
| **Cycle 1** | 2026-06-17 | **48s** | 3/3 | PASS (logcat) | FGS trampoline, overlay re-show @40s, channel preload in `onCreate`; script stdout bug |
| **Cycle 2** | 2026-06-17 | **30s** | 3/3 | **PASS (screencap)** | `GatewayApp` boot kick, tighter alarms (8/20/40/80s), 2s launcher settle |
| **Cycle 3** | 2026-06-17 | **4s** | 3/3 | **PASS (screencap)** | Optional overlay re-show @40s only (20s removed), WM expedited @30s, BootReceiver+3s retry |
| **Release** | 2026-06-17 | **20s** | 2/3 | PASS (logcat) | `com.nova.stepdaddylivehd.gateway` (debug-signed); `/epg.xml` 503 at t+20s |

**Timing trend:** 81s → 48s → 30s → **4s** (debug) time-to-health after reboot (poll from host, from wlan0 IP available). Release build: **~20–80s** on cold boot depending on channel/EPG preload (no debug suffix; EPG may still be building at first health).

**Overlay policy:** One startup banner when the server is listening (may show “loading channels…” before the count is ready). Optional **single** re-show at **+40s** if the first banner was missed — no re-show at +20s.

**Release signing:** No release keystore in repo — `assembleRelease` produces `app-release-unsigned.apk`; installed on FUSA after zipalign + debug `apksigner` for boot comparison only.

Proof image: `/home/nova/livehd/current/fusa-boot-banner-proof.png` — shows *"StepDaddy Gateway running — Ready for TiviMate — 1140 channels"* overlay on Google TV home.

### UX expectations (ONN / FUSA stick)

| Expectation | Notes |
|-------------|-------|
| **Cold boot to gateway ready** | Plan for **~60–80s** after reboot before `/health` is 200 and channels are loaded (release builds; debug can be faster). |
| **Startup banner** | One overlay when the HTTP server is listening; optional **+40s** re-show only if the first was missed. |
| **TiviMate setup** | **One-time** — add playlist + EPG URLs in TiviMate; TiviMate does not show gateway status (use home banner or StepDaddy Gateway app). |
| **Stream start latency** | First play per channel is **upstream-bound** (often 3–15+ s cold); warm repeats are much faster. Not a gateway bug. |
| **TiviMate HLS** | Manifests use **`/content/` proxy** (encrypted URLs) matching Linux gateway — required for segment referer headers. |
| **Channel logos** | **100%** working `tvg-logo` URLs (85% real iptv-org via `/logo/`, 15% per-channel placeholder SVG); see Logo coverage table below. |

### Logo coverage (2026-06-17, FUSA2541006925)

Audit: `GET /tivimate-playlist.m3u8` → classify `tvg-logo` URLs → `curl` each unique URL for HTTP 200.

| Metric | Before | After |
|--------|--------|-------|
| **Channels** | 1140 | 1140 |
| **Real logos** (`/logo/` proxy) | 341 (29.9%) | **969 (85.0%)** |
| **Per-channel placeholder** (`/ui/channel/{token}.svg`) | 0 | 171 (15.0%) |
| **Generic default** (`default-channel.svg`) | 799 (70.1%) | **0 (0%)** |
| **Unique logo URLs** | — | 953 |
| **HTTP 200 (all unique URLs)** | — | **953 / 953 (100%)** |
| **Random sample (n=20)** | — | **20 / 20 OK** |

**Resolver parity with Linux:** `meta.json` logos (primary), `logos_db_cache.csv` + `channels_db_cache.csv`, 148 name aliases, tvg-id variant index (compact + dotted), fuzzy name match (0.88), `channel_logo_overrides.json`, per-channel SVG placeholder as last resort. Playlist build awaits logo DB load; upstream logos pre-warmed on channel refresh; `/logo/` falls back to default SVG on upstream failure (always 200).

### Self-healing streaming (2026-06-17)

| Mechanism | Behavior |
|-----------|----------|
| **StreamHealthWatchdog** | Every 120s probes channels 51+857 and mirror `/api/channels`; logs to `StreamHealth` tag |
| **Cache invalidation** | Per-channel purge only on CDN/parse failures; mirror/global errors skip invalidate; stale upstream kept up to 600s |
| **ResportzParser** | Multi-pattern iframe + m3u8 extraction (`thatframe`, `/?a=`, donis embeds, atob variants); logs matched pattern |
| **Mirror failover** | CDN HTTP 403/500 no longer marks daddylive mirror dead (only resportz/API failures do) |
| **HLS error manifest** | `/tivimate-stream/` returns `#EXTM3U` error body on upstream fail — TiviMate fails fast vs JSON spinner |
| **Playlist cache bust** | `Cache-Control: no-cache, no-store, must-revalidate` on `tivimate-playlist.m3u8` |
| **Gateway restart** | After 3 consecutive watchdog probe failures, `ServerService` restarts HTTP server + purges stale caches |
| **Health telemetry** | `GET /health` includes `healing` object (`lastAction`, `recentActions`, cache sizes) |

### Run boot test

```bash
cd stepdaddy-android
ADB_SERIAL=FUSA2541006925 CYCLE_TAG=cycleN ./scripts/fusa-boot-test.sh
# Release package (no .debug suffix):
PKG=com.nova.stepdaddylivehd.gateway CYCLE_TAG=release-cycle1 ./scripts/fusa-boot-test.sh
```

Artifacts: `/tmp/fusa-boot-test/cycleN_*.{log,report.txt,png}`

---

## 1. Baseline latency (20 runs each)

Times in **milliseconds**. Single client, sequential.

### `GET /health`

| Metric | ms |
|--------|-----|
| min | 85 |
| p50 | 214 |
| p95 | 668 |
| p99 | 668 |
| max | 1456 |
| avg | 318.05 |

### `HEAD /tivimate-playlist.m3u8`

| Metric | ms |
|--------|-----|
| min | 80 |
| p50 | 114 |
| p95 | 484 |
| p99 | 484 |
| max | 1121 |
| avg | 201.55 |

### `GET /stream/51.m3u8` (cold vs warm)

| Request | HTTP | ms |
|---------|------|-----|
| 1st (cold) | 200 | 3986 |
| 2nd (warm, same session) | 200 | 247 |

---

## 2. Concurrent load

Client: `curl` + `xargs -P` from PC to `10.237.74.181:3000`.

### 10 parallel `GET /stream/{id}.m3u8` (distinct channel IDs)

Channels: 51, 155, 726, 16, 360, 800, 963, 302, 766, 110 (after gateway restart).

| Metric | Value |
|--------|-------|
| **Success rate** | 7 / 10 (70%) |
| **Failures** | 3 (75s client timeout, no response) |
| **Avg latency** | ~30,021 ms (includes timeouts as 75,000 ms) |
| **Max latency** | 75,004 ms |
| **5xx** | None observed on completed responses |
| **Notes** | Successful parallel streams clustered ~4.5–15 s; three requests starved |

Per-request (successful):

| Channel | ms |
|---------|-----|
| 51 | 170 |
| 726 | 4,549 |
| 302 | 13,099 |
| 766 | 13,505 |
| 800 | 13,991 |
| 16 | 14,941 |
| 360 | 14,947 |

### 25 parallel `GET /health`

| Metric | Value |
|--------|-------|
| **Success rate** | 25 / 25 (100%) |
| **Avg latency** | 375 ms |
| **Max latency** | 477 ms |
| **5xx / timeouts** | 0 |

### 5 parallel full `GET /tivimate-playlist.m3u8` (~390 KB)

| Metric | Value |
|--------|-------|
| **Success rate** | 5 / 5 (100%) |
| **Payload** | 399,747 bytes each |
| **Avg latency** | 21,130 ms |
| **Max latency** | 22,523 ms |
| **5xx / timeouts** | 0 |

---

## 3. Sustained stress (~2 minutes wall clock)

**Profile:** Background stream fetch every **2 s** (random channel, 45 s curl timeout) + `GET /health` every **5 s** (async workers). Gateway remained reachable on health at end.

| Metric | Value |
|--------|-------|
| **Total requests** | 79 |
| **Stream requests** | 59 |
| **Health checks** | 20 |
| **Stream success rate** | 7 / 59 (**11.9%**) |
| **Stream failures** | 52 (mostly 45 s timeouts under backlog) |
| **Health success rate** | 20 / 20 (**100%**) |
| **Avg stream time (successful only)** | ~2,101 ms (min 1,790 / max 2,565) |
| **Final health** | HTTP **200**, ~80 ms |

**Interpretation:** Under continuous overlapping upstream work, the single-device gateway queues stream resolution; lightweight `/health` stays healthy but stream SLA collapses.

---

## 4. Memory & CPU on device (adb, stress peak)

Captured during parallel stream load (`dumpsys meminfo`, `top`, `/proc/pid/status`).

| Source | Reading |
|--------|---------|
| **TOTAL PSS** | ~49,608–70,403 KB |
| **TOTAL RSS** | ~115,560–144,524 KB |
| **VmRSS** (`/proc`) | ~114,856–146,256 KB |
| **VmHWM** | ~124,176–158,708 KB |
| **Threads** | 38–48 |
| **CPU (`top`)** | **~100%** on gateway process during 10-way stream burst |
| **Swap PSS** | Low when idle (~267 KB); higher under earlier overload (~17 MB) |

Package: `com.nova.stepdaddylivehd.gateway.debug`

---

## 5. Channel sweep (20 random streams)

**Run A — sequential, 45 s timeout** (gateway already contended): **10 / 20** HTTP 200.

| Metric | ms (successful requests only, n=10) |
|--------|-------------------------------------|
| min | 1,692 |
| p50 | ~2,564 |
| max | 7,387 |
| avg | ~2,982 |

Failed requests hit **45 s** client timeout (HTTP 000).

**Run B — partial under earlier load:** mixed 200/timeout; not used for headline stats.

---

## 6. Upstream resilience / cache (channel 360)

Three rapid sequential `GET /stream/360.m3u8` after **clean gateway restart**:

| Request | HTTP | ms |
|---------|------|-----|
| 1st | 200 | 4,320 |
| 2nd | 200 | 106 |
| 3rd | 200 | 102 |

**Speedup (1st → 3rd):** ~42× — strong in-process cache for repeat channel resolution.

---

## 7. Overall grade

| Area | Grade | Rationale |
|------|-------|-----------|
| Health & metadata | **PASS** | Fast, stable under 25-way concurrency |
| Playlist delivery | **PASS** | Large M3U completes; ~21 s under 5-way parallel |
| Single-stream latency (idle) | **WARN** | Cold resolve ~4 s; acceptable for IPTV |
| Parallel streams | **FAIL** | 30% timeouts at 10 clients; multi-second tail |
| 2 min sustained load | **FAIL** | ~88% stream failures; health OK |
| Device resources | **WARN** | Memory OK; CPU saturates on concurrent upstream |
| Cache behavior | **PASS** | Sub-200 ms repeat channel |

### **Overall: WARN** (production-capable for **1–2** concurrent viewers per box; **FAIL** for heavy LAN-wide stress without queuing limits)

---

## Bottleneck notes

1. **ONN / Android TV hardware** — Quad-core class device; `top` shows **100% CPU** when multiple streams resolve upstream simultaneously.
2. **Upstream latency (daddylive / embed chain)** — Cold `GET /stream/*` routinely **3–15+ s**; dominates baseline and concurrent tail.
3. **No apparent hard concurrency cap** — Overlapping stream jobs queue on one JVM/OkHttp pool; later clients time out while health still answers.
4. **Playlist generation** — ~20 s for full GET under parallel load (CPU + I/O on device).
5. **Network** — USB adb + Wi‑Fi/LAN to `10.237.74.181`; not the primary limiter vs upstream fetch.

---

## Recommendations for production

1. **Limit concurrent stream resolutions** (semaphore / queue + 503 with `Retry-After`) so clients fail fast instead of 45–120 s hangs.
2. **Expose cache hit metrics** in `/health` (hits, misses, in-flight) for monitoring.
3. **Pre-warm** popular channels or extend TTL on resolved manifest URLs where upstream allows.
4. **Single-stream SLO:** target cold &lt; 8 s, warm &lt; 500 ms — already met for warm cache on channel 360.
5. **Deploy one gateway per household stream budget** (~2 simultaneous cold starts); scale viewers via cached HLS pass-through, not repeated full resolve.
6. **Release build:** re-run this suite on `release` APK; debug adds JVM/logging overhead.
7. **Consider upstream HTTP connection pool sizing** and bounded worker dispatcher aligned to CPU count (2–3).
8. **Restart policy:** after overload, `force-stop` + `START` service recovers health within seconds (validated via adb).

---

## How to reproduce

```bash
# Boot test (recommended for FUSA stick)
./scripts/fusa-boot-test.sh

# Manual health baseline (device must already be serving)
IP=$(adb -s FUSA2541006925 shell ip -4 addr show wlan0 | grep -oP 'inet \K[0-9.]+')
for i in $(seq 1 20); do
  curl -sS -o /dev/null -w "%{time_total}\n" "http://${IP}:3000/health"
done
```

Raw machine output: `/tmp/stepdaddy_bench_results.txt`, `/tmp/stepdaddy_bench_raw/`.

---

*Generated by automated benchmark run on the StepDaddy Android gateway project.*

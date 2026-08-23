# EPG residual → 75% high-confidence plan

**Status:** EXECUTED — results in [`epg-residual-75-results.md`](epg-residual-75-results.md) (2026-08-23).  
**Outcome:** Movies ≥75% (+WOFTV/eligible); Entertainment ~59% raw / ~74% eligible-no-permanent; Locals ~22% raw / ~32% eligible.  
**Date:** 2026-08-22  
**Baseline:** agent [b5f5d3d9](b5f5d3d9-8d1f-42da-9181-8bb3b29b3481) high-confidence pass  
**Evidence sources:** `/tmp/stepdaddy-playlist.m3u8`, `/tmp/epg-audit-prog-ids.txt`, `/tmp/epg_ripper_US2_fresh.xml.gz`, `/tmp/locals-ids.txt` / `US_LOCALS1`, `reports/woftv-match-audit.csv`, `app/.../epg_id_bridge.json` (784 bridges), `scripts/epg-now-playing.py`

---

## 1. Target math

Use the **agent baseline denominators** (stable after the high-conf pass). Playlist snapshot has Movies **248** / Entertainment **2137** (tiny drift from duplicates); stick to baseline for goal tracking.

| Category | Baseline | Residual | **75% target** | **Need to add** |
|----------|----------|----------|----------------|-----------------|
| Movies | 127 / 246 (**51.6%**) | 119 | **185 / 246** | **+58** |
| Entertainment | 729 / 2070 (**35.2%**) | 1341 | **1553 / 2070** | **+824** |
| Local Channels | 28 / 175 (**16.0%**) | 147 | **132 / 175** | **+104** |

### Residual buckets (baseline)

| Bucket | Movies | Entertainment | Local |
|--------|--------|---------------|-------|
| FAST / hash / FAST brand | 56 | 418 | — |
| empty `tvg-id` | 12 | 426 | 3 |
| no source / ambiguous | 43 | 486 | — |
| regional / PEG / no map | 8 | 11 | 144 |

### What “covered” means

A channel counts toward coverage only when **all** of:

1. Playlist `tvg-id` (or approved bridge target) resolves into a feed id that has **≥1 real programme** in the light EPG merge (not placeholder “Live programming”).
2. The mapping passed an **accuracy gate** (section 3) — not fuzzy name similarity alone.
3. User **approved** the batch report before commit.

---

## 2. Bucket strategy (research methods + sources)

### A. Empty `tvg-id` (Movies 12 · Entertainment 426 · Local 3)

**Problem:** DaddyLive rows with blank `tvg-id` cannot hit epgshare / WOFTV / LOCALS1.

**Research methods (in order):**

1. **Normalize display name** — strip `US:` / `UK:`, superscript RAW marks, bracket tags; build exact + light variants (HD/SD, `&`/`and`).
2. **DaddyLive / channels_db crosswalk** — `channels_db_cache.csv` (~37k norms): name → canonical `tvg_id`.
3. **epgshare channel lists** — exact display-name match against US2 (763 channels), PLEX1, DISTROTV1 once cache-busted download is available.
4. **iptv-org** — `channels.json` / site playlists for Pluto/Plex/Samsung/Xumo when the name is clearly FAST.
5. **Call-sign extract** (locals / OTA-looking titles) → FCC / RabbitEars / `US_LOCALS1` id.

**Live yield signal (host re-probe, 2026-08-22):**

| Signal | Movies empty | Entertainment empty |
|--------|--------------|---------------------|
| Exact norm → US2 display-name | **0** | **3** (e.g. American Crimes → `American.Crimes.us2`, Horror Machine, Tastemade Home) |
| Exact norm → channels_db | ~0 useful | ~1 (most empty names are niche/FAST/religious, not in US2) |

**Implication:** Empty-id Entertainment is **not** mostly “missing US2 cable ids.” Expect heavy overlap with **FAST / religious / PEG / foreign** after name research. Treat empty as a **discovery queue**, not an automatic +426.

**Real examples (playlist):**

| Group | Name (blank tvg-id) | Likely path |
|-------|---------------------|-------------|
| Movies | `US: MGM+ USA / EPIX HD` | Name research → `MGM+.HD.us2` (US2 has MGM+ HD); confirm with now-playing — **reject** if Epix-era schedule diverges |
| Movies | `US: REELZ FAMOUS & INFAMOUS` | WOFTV / FAST schedule; US2 has `ReelzChannel.HD.us2` but **not** this spinoff — do not bridge to main Reelz without title proof |
| Movies | `US: COWBOY MOVIE CHANNEL` / `DRIVE IN MOVIE CHANNEL` | Distinguish `The.Cowboy.Channel.us2` vs MGM+ Drive-In vs FAST “Cowboy Movies” |
| Entertainment | `US: ABN FREEDOM OF SPEECH` / `ABN I AM` | Religious nets — often **no** epgshare guide; mark permanent uncovered |
| Entertainment | `US: ABSINTHE TV` / `ACCION MEXICANA` | Regional / FAST name research; UK1/ES1/MX only if id proven |
| Local | `COX MERIDEN PUBLIC ACCESS…` / `SOUND VIEW COMMUNITY MEDIA GOVERNMENT` | **PEG** — exclude or placeholder policy |

---

### B. FAST / hash ids (Movies 56 · Entertainment 418)

**Problem:** Mongo-style hex (`5f4d878d3d19b30007d2e782`) and brand FAST rows have no epgshare US2 id.

**Research methods:**

1. **WOFTV catalog** (`WhatsOnFreeTvEpgCatalog` + `reports/woftv-match-audit.csv`) — name → Pluto/Plex/Samsung/Roku/Tubi/Xumo key.
2. **iptv-org FAST EPG** — existing `scripts/grab-iptv-org-fast-epg.sh` (pluto/plex/xumo/distro/whale); map hex → iptv-org `tvg-id` via `FastChannelTvgIdResolver` / mjh.nz index.
3. **epgshare PLEX1 / DISTROTV1** — gap-fill feeds (cache-bust); bridge only when feed channel id + now-playing agree.
4. **Multi-platform agreement** — accept when ≥2 of {WOFTV, iptv-org, PLEX1} show the **same title window**.

**When to accept a FAST bridge:**

| Accept | Reject |
|--------|--------|
| WOFTV score ≥ **0.90** *and* now-playing title matches within ±15 min of a second source | Score 0.80–0.89 with only one source |
| Exact iptv-org / PLEX1 id after hex→dot resolution, programmes present | Name collision (e.g. “Sky News” vs “Sky Sports News”) |
| Display name unique in WOFTV catalog for that platform | Generic titles (“Movies”, “Comedy”, “Crime”) |

**WOFTV yield (audit CSV, uncovered only):**

| Score floor | Movies | Entertainment |
|-------------|--------|---------------|
| ≥ 0.95 | 9 | 129 |
| ≥ 0.90 | 10 | 187 |
| ≥ 0.85 | 13 | 204 |

**Examples:**

| tvg-id | Name | Notes |
|--------|------|-------|
| `68487fb3f212bedacf5a53e3` | 50 CENT ACTION | FAST hex → WOFTV/Pluto research |
| `5f4d878d3d19b30007d2e782` | 70S CINEMA | Same |
| `62ba60f059624e000781c436` | 00S REPLAY | Entertainment FAST |
| `65a7b04e7bdc8d0008488307` | TRUE CRIME NOW | WOFTV score 0.96 (Plex/Pluto/Roku) — strong Phase C candidate |
| `99951156` | AT HOME WITH FAMILY HANDYMAN | WOFTV 0.98 — strong candidate |

---

### C. Local OTA (call-sign → `US_LOCALS1`)

**Problem:** Residual “Local Channels” are mostly **not** mappable OTA. Re-probe of 147 residuals:

| Sub-bucket (re-probe) | Count | Notes |
|----------------------|-------|-------|
| PEG / access / community / city gov (name heuristics) | ~59–100+ | No commercial guide |
| OTA call in tvg-id, **LOCALS1 candidate exists**, still unmapped | **10** | High-value Phase B |
| OTA-ish call, **no LOCALS1** | ~10 | FCC/RabbitEars may confirm LPTV/PEG |
| No call / city channel / university | ~68 | Treat as PEG-class |

**OTA still worth researching (LOCALS1 hit exists — need now-playing + subchannel care):**

| Playlist tvg-id | Name | Candidate |
|-----------------|------|-----------|
| `KRGVTV51.us@HD` | ABC 5 | `KRGV-DT.us_locals1` |
| `WATCDT572.us@HD` | Atlanta’s 57 WATC **Too 57.2** | `WATC-DT.us_locals1` — **subchannel .2 → reject or map only if feed has .2** |
| `KVCRDT242.us@SD` | FNX | `KVCR-DT.us_locals1` — verify FNX is KVCR-DT2, not primary |
| `KIIOLD104.us@SD` | AABC TV | `KIIO-LD.us_locals1` |
| `KNOVCD411.us@HD` | NOTV New Orleans | `KNOV-CD.us_locals1` |

**Sources:** FCC LMS facility search, RabbitEars.info (virtual channel / network), epgshare `US_LOCALS1` display-names, `epg-now-playing.py` against locals feed.

**Prior reject rule (keep):** subchannels (.2/.3), diginets, “6 WISE TV” ≠ WISE-DT.

---

### D. PEG / community (expect no EPG)

**Examples:** Access Humboldt, AMP Community, Bolton CVC Education/Gov/Public, CreaTV SJ, Cox Meriden Public Access, DC Council Hearing Room, Midpen Media Center, SF Commons, university BUTV10.

**Policy options (pick one before counting 75%):**

| Policy | Effect on Local 75% |
|--------|---------------------|
| **P0 (recommended):** Exclude PEG from **denominator** and coverage % | Denom shrinks (~175 − ~80–110 PEG) → 75% becomes reachable on remaining OTA/cable locals |
| P1: Keep in denom; serve static placeholder programme (“Community programming”) | Inflates “withProgrammes” but **not** high-confidence real EPG — **do not** count toward 75% goal |
| P2: Drop PEG rows from playlist | Product decision; out of scope for mapping alone |

Entertainment also has a few PEG-tagged rows (`BerksCommunityTV`, `GillettePublicAccess…`) — apply the same exclusion.

---

### E. Ambiguous cable / Gracenote-like / regional

**Subtypes:**

1. **US2-shaped but wrong/missing id** — e.g. `HallmarkMoviesMore.us@SD`, `SonyMoviesLatinAmerica.us@SD`, `MoreMax`-class already fixed in baseline.
2. **Gracenote-like** `USBB…` / `USBD…` / `US48…` — **~184** Entertainment residuals (e.g. `USBB320000397` = 21 Jump Street, `US4800001W5` = ABC 20/20).
3. **False friends** — `6WiseTv.us@SD` ≠ WISE; `MovieSphere` ≠ `MovieSphere.Gold.us2`.
4. **Regional** — `BBCDrama.uk`, `GREATmovies.uk@UK`, Latin America feeds — use UK1/CA2/gap-fill only with id proof.

**Research methods:**

1. Exact / normalized name → US2 channel list (host probe: Entertainment ambiguous → **29** exact US2 name hits; Movies → **3** including CHARGE!, Movies!).
2. channels_db suggest (many hits) **then** verify target id exists in a **downloaded feed with programmes**.
3. **Mandatory** `epg-now-playing.py` cross-check vs second source (tvtv Eastern for premium; US2; WOFTV) before bridge.
4. HBO2-style Eastern→US2 pattern only when timezone/title proof matches (already established for premium movie nets).

**Movies leftovers called out in baseline:** Hallmark Movies & More, MovieSphere (≠ Gold), RetroTV, Latin America AXN/AMC/Cinecanal — US2 has Hallmark Mystery/Family/Channel and MovieSphere **Gold** only; Latin nets likely **permanent** without regional feed proof.

---

## 3. Accuracy gates (confidence rubric)

Assign each proposed bridge a grade. **Only A/B ship** after user batch approval.

| Grade | Criteria | Action |
|-------|----------|--------|
| **A — Exact** | Playlist id (or stripped `@HD/@East`) equals feed id, **or** bridge target is unique call-sign primary (`.1` / `-DT`) in LOCALS1 with matching network in name | Auto-queue for approval |
| **B — Proven** | Name unique match **and** `epg-now-playing.py` title agrees with ≥1 other source within ±15 minutes | Queue for approval |
| **C — Strong single-source** | WOFTV ≥ 0.95 **or** iptv-org exact, but only one live source | Hold for second probe or user spot-check |
| **D — Weak** | Score 0.80–0.94, partial name, or channels_db-only | Research more — **no commit** |
| **F — Reject** | Subchannel ambiguity, false friend, Latin/geo without feed, PEG, generic FAST title, timezone conflict | Permanent skip / exclude |

### Hard reject rules

- Never map `.2` / `.3` / “Too” / diginet → primary `-DT` without feed subchannel id.
- Never map “X Movies & More” → parent “X” without now-playing proof.
- Never map hex FAST → US2 linear cable id.
- Never count placeholder / synthetic “Community programming” toward 75%.
- Never commit bridges without a batch report + user OK.

---

## 4. Workflow (approval-first)

```
 residual CSV export
        ↓
 research scripts (bucket-specific)
        ↓
 candidate rows: playlist_id → feed_id, grade A/B/C, evidence URLs
        ↓
 epg-now-playing.py batch (pre-approve evidence)
        ↓
 reports/epg-batch-YYYYMMDD-{movies|ent|locals}.md  ← USER APPROVES
        ↓
 apply to epg_id_bridge.json / epg_name_overrides.json
        ↓
 unit tests (EpgShareIdBridgeTest, LightEpgBuilderGapFillTest)
        ↓
 device /health + sample programme spot-check
```

### `epg-now-playing.py` usage (already exists)

```bash
# Single channel
python3 scripts/epg-now-playing.py --name "US: TRUE CRIME NOW" --diagnose
python3 scripts/epg-now-playing.py KRGV-DT.us_locals1

# Proposed batch mode (tooling §5): read candidates CSV, emit proof table
python3 scripts/epg-now-playing-batch.py reports/candidates-ent-fast.csv -o reports/proof-ent-fast.md
```

Each approval report must include: `tvg-id`, display name, proposed feed id, grade, now-playing titles (source A/B), and reject notes for near-misses.

---

## 5. Tooling to build (scripts / audits)

Implement only when a phase starts; listed here for approval of scope.

| Script | Purpose |
|--------|---------|
| `scripts/epg-residual-inventory.py` | Parse playlist + prog-ids + bridge → per-category residual CSV with bucket labels |
| `scripts/epg-empty-id-research.py` | Blank tvg-id → US2 / channels_db / WOFTV / iptv-org candidates + grade |
| `scripts/epg-fast-woftv-queue.py` | Filter `woftv-match-audit.csv` by group + score floor → candidate CSV |
| `scripts/epg-locals-callsign-audit.py` | Extract call from tvg-id/name → LOCALS1 / RabbitEars notes; flag PEG |
| `scripts/epg-now-playing-batch.py` | Wrapper over `epg-now-playing.py` for candidate CSVs → proof markdown |
| `scripts/epg-coverage-scorecard.py` | Recompute Movies/Ent/Local % vs 75% targets; optional PEG-excluded denom |
| `scripts/epg-peg-classifier.py` | Heuristic + manual override list for PEG exclusion |

**Reuse as-is:** `epg-now-playing.py`, `audit-epg-mappings.py`, `export-epg-gaps.py`, `grab-iptv-org-fast-epg.sh`, `generate-epg-id-bridge.py`, `research_epg_common.py`, `reports/woftv-match-audit.csv`.

**Ops prerequisite:** Ensure PLEX1 / DISTROTV1 / US_LOCALS1 downloads use cache-bust (`EpgConfig.feedDownloadUrl`) on host research machines — bare URLs still 404 via Cloudflare (see `reports/epgshare-gap-fill-2026-08-22.md`).

---

## 6. Phased milestones

### Phase 0 — Instrumentation & policy (½ day)

- Freeze denominator policy for Locals/Entertainment PEG (**recommend P0 exclude**).
- Land `epg-residual-inventory.py` + `epg-coverage-scorecard.py`.
- Export residual CSVs under `reports/residuals/`.
- **Exit:** agreed scorecard; no mapping commits.

### Phase A — Empty-id + easy US2 name hits (Entertainment + Movies)

- Research all blank tvg-ids + ambiguous rows with **exact** US2 display-name matches (Ent ~29 exact, Movies ~3 known: CHARGE!, Movies!, …).
- Gracenote-like ids whose **normalized name** equals a US2 channel with programmes.
- Batch proof → user approve → bridge/overrides.
- **Exit target:** Movies toward **~60–65%**; Entertainment **+80–150** if evidence holds (not the full empty bucket).

### Phase B — Local OTA (call-sign / LOCALS1)

- Audit 10 LOCALS1-available unmapped + any remaining primary OTA in playlist.
- Subchannel / FNX / diginet held at grade F unless feed proves `.2`.
- PEG classifier → exclusion list for scorecard.
- **Exit:** Locals **raw** maybe ~20–25%; **PEG-excluded** scorecard on track for ≥75% of *eligible* locals if enough OTA remain — **or** document ceiling shortfall.

### Phase C — FAST / WOFTV / iptv-org / PLEX1

- Queue WOFTV ≥ 0.90 (Ent ~187, Movies ~10) through now-playing batch.
- Refresh iptv-org FAST grab; hex→dot via existing resolver patterns.
- Bridge only grade A/B; grade C spot-check sample (10–20%).
- **Exit:** largest Entertainment gain expected (**+150–250** high-conf if dual-source proof rate is healthy).

### Phase D — Hard residuals

- Ambiguous cable false friends, Latin America, Hallmark Movies & More, MovieSphere≠Gold, regional UK, religious nets.
- Multi-source now-playing only; prefer permanent-uncovered tags over bad bridges.
- **Exit:** Movies **≥75%** if Phases A–C delivered ~50+; Entertainment push toward 75% or revised ceiling; Locals documented.

### Suggested order rationale

1. Empty/US2 exact = highest accuracy / lowest harm.  
2. Locals OTA = small N, clear rules, unlocks denominator honesty.  
3. FAST = large N, needs dual-source discipline.  
4. Hard residuals = diminishing returns.

---

## 7. Realistic ceiling (high confidence)

| Category | High-conf ceiling (est.) | Why short of 100% | 75% verdict |
|----------|--------------------------|-------------------|-------------|
| **Movies** | **~78–85%** | FAST spinoffs without guides; Latin movie nets; Hallmark/MovieSphere false friends; blank niche movie FASTR | **Achievable** (+58 of 119) with Phases A+C+selective D |
| **Entertainment** | **~55–68%** raw; **~70–75%** if PEG/empty-unresearchable excluded from denom | 418 FAST (many single-source); ~400+ empty with no US2; Gracenote ids without feed programmes; religious/PEG | **75% raw is a stretch** — need strong Phase C dual-source rate **or** denominator policy for permanent-uncovered |
| **Local Channels** | **~22–35%** raw; **~75% of non-PEG** only if eligible pool is mostly OTA | ~100+ PEG/city/community/university with **no** Gracenote/epgshare guide | **75% raw not achievable** with honest high-conf rules; use **PEG-excluded denom** |

### Permanent uncovered (do not force)

- PEG / public access / city council / school / university channels  
- Geo-locked Latin America / regional feeds without a working gap-fill id  
- FAST channels with no WOFTV/iptv-org/PLEX schedule  
- Subchannels and diginets without explicit feed ids  
- False-friend cable (MovieSphere vs Gold, Wise vs WISE)

### Path to “declare 75%” honestly

1. **Movies:** pursue absolute 185/246.  
2. **Entertainment:** pursue absolute 1553/2070; if high-conf stalls ~60–65%, present scorecard with `eligible_denom = total − permanent_uncovered` and show ≥75% on eligible.  
3. **Locals:** default scorecard = PEG-excluded; keep raw % as transparency metric.

---

## Appendix A — Worked need vs bucket (planning only)

Optimistic **high-conf** yields (not commitments):

| Phase | Movies Δ | Entertainment Δ | Locals Δ (raw) |
|-------|----------|-----------------|----------------|
| A empty/US2 exact | +8–15 | +50–120 | +0–2 |
| B locals OTA | — | — | +5–12 (primaries only) |
| C FAST WOFTV≥0.90 dual-source | +8–12 | +120–200 | — |
| D hard / regional | +10–25 | +40–80 | +0–5 |
| **Sum (optimistic)** | **~40–65** | **~210–400** | **~5–19** |
| **Need for 75%** | **58** | **824** | **104** |

Interpretation: Movies clears 75% under optimistic high-conf. Entertainment and Locals **cannot** hit raw 75% from high-conf research alone — policy (eligible denominator) or accepting lower ceilings is required for those two.

---

## Appendix B — Approval checklist (per batch)

- [ ] Residual inventory CSV attached  
- [ ] Each row graded A/B/C/F  
- [ ] Now-playing proof for every A/B row  
- [ ] Rejects listed with reason  
- [ ] Scorecard before/after projected  
- [ ] No PEG counted as real coverage  
- [ ] User signed off in chat / PR  

---

## Appendix C — Artifact paths

| Artifact | Path |
|----------|------|
| Playlist cache | `/tmp/stepdaddy-playlist.m3u8` |
| Programme id set | `/tmp/epg-audit-prog-ids.txt` |
| US2 feed | `/tmp/epg_ripper_US2_fresh.xml.gz` |
| LOCALS1 ids | `/tmp/locals-ids.txt` |
| WOFTV audit | `reports/woftv-match-audit.csv` |
| Bridge asset | `app/src/main/assets/epg_id_bridge.json` |
| Now-playing probe | `scripts/epg-now-playing.py` |
| This plan | `reports/epg-residual-75-plan.md` |

# EPG blocker resolution plan (reasonable confidence)

**Status:** EXECUTED — see `reports/epg-blocker-resolution-results.md` (2026-08-23).  
**Date:** 2026-08-23  
**Inputs:** [`epg-residual-75-plan.md`](epg-residual-75-plan.md), [`epg-residual-75-results.md`](epg-residual-75-results.md), [`residuals/residual-inventory.csv`](residuals/residual-inventory.csv), [`residuals/permanent-uncovered.csv`](residuals/permanent-uncovered.csv), [`residuals/applied-candidates.csv`](residuals/applied-candidates.csv), [`woftv-match-audit.csv`](woftv-match-audit.csv)  
**Baseline after residual-75 pass:** Movies raw **77.0%** (met); Entertainment raw **58.8%** / eligible-no-permanent **73.7%**; Locals raw **21.7%** / PEG-excluded **31.7%**.

This plan covers the three remaining blockers that still block an honest push past ~75% on Entertainment (eligible) and any credible Locals story:

1. Empty `tvg-id` Entertainment  
2. PEG locals  
3. FAST without dual-source proof  

---

## Scorecard context (why these three matter)

| Metric | Value | Gap |
|--------|------:|-----|
| Entertainment raw covered | 1256 / 2137 (**58.8%**) | Need **+297** for raw 75% |
| Entertainment eligible (no permanent*) | 912 / 1238 (**73.7%**) | Need **~+17** covered *or* shrink permanent set carefully |
| Locals raw | 38 / 175 (**21.7%**) | Raw 75% impossible under honest rules |
| Locals PEG-excluded | 38 / 120 (**31.7%**) | Need OTA pool growth + stricter PEG exclusion |

\*Permanent class today includes empty `tvg-id`, PEG, `FAST_no_dual_source`, regional Latin, subchannel/diginet.

**Implication:** Empty-id Entertainment is mostly a *denominator honesty* problem (many permanent). FAST dual-source is the *small high-conf lift* that can clear the eligible 75% near-miss. PEG is *policy*, not mapping.

---

## Blocker 1 — Empty `tvg-id` Entertainment

### 1. Problem size

| Slice | Count | Source |
|-------|------:|--------|
| Entertainment empty `tvg-id` residuals | **~347** | `residual-inventory.csv` `bucket=empty_tvg_id` |
| Movies empty (out of scope here, but related) | 9 | same |
| Local empty (almost all PEG/webcam) | 3 | same |
| Tagged `empty_tvg_id` in permanent CSV (Ent) | 342 (+5 regional) | `permanent-uncovered.csv` |

**Country prefix mix (Entertainment empty):** US **203**, UK **54**, FR **15**, ES/HR/INT **8** each, then long tail of sports/geo blanks (SE, BR, DE, RS, …).

**Real playlist examples (blank tvg-id):**

| Name | Why it resists |
|------|----------------|
| `US: ABN FREEDOM OF SPEECH` / `ABN I AM` / `ABN SON OF GOD` | Religious nets — no US2/WOFTV guide |
| `US: AFROLAND *` (12 rows) | Niche FAST family — no exact US2 |
| `US: WBTV *` (21 rows: Chasing Criminals, Ghosts Are Real, …) | Warner Bros. Discovery FAST slate — need WOFTV/Pluto/Samsung, not US2 |
| `US: ACCNX HD` / `SECN+ HD` / `NBCS BAY AREA HD` | RSN / conference nets — often no epgshare US2 id on blank rows |
| `US: COX MERIDEN PUBLIC ACCESS…` (Local) | PEG — see Blocker 2 |
| `UK: HARDCORE PAWN` / `UK: EVOLUTION EARTH` | Rare WOFTV ≥0.95 empty-id hits (see below) |
| `FR: CANAL+ LIVE *` / `ES: DAZN HD` / `HR: SPORTKLUB *` | Geo sports — permanent without regional feed proof |

### 2. Why previous pass couldn't fix them

Phase A only shipped **exact US2 display-name** matches (15 applied rows, method `exact_us2_display_name`). That exhausted the easy empty→US2 set (`American Crimes`, `NASCAR`, `Tastemade Home`, `TV Blossom`, …).

Host re-probe signals from the original plan still hold after execution:

- Exact norm → US2 display-name on remaining empty Ent: **near zero** (prior pass took the 3–4 that existed).
- WOFTV audit join by `norm` on remaining empty Ent: only **70/347** even appear in `woftv-match-audit.csv`; of those, **2** score ≥0.95 (`Evolution Earth`, `Hardcore Pawn`).
- Most blank names are niche FAST, religious, Latino regional, RSN, webcam, or non-US sports — not missing cable ids.

Empty `tvg-id` was therefore correctly parked as **permanent / discovery queue**, not an automatic +347.

### 3. Resolution strategy (confidence tiers)

| Tier | Rule | Action |
|------|------|--------|
| **High** | Exact normalized display name → US2 (or PLEX1/DISTROTV1) channel **with programmes**, **or** exact WOFTV catalog key ≥0.95 **and** now-playing title agrees with second source within ±15 min | Queue name override (`epg_name_overrides.json` `__empty__:…`) + optional bridge once playlist gains an id |
| **Medium-reasonable** | Exact WOFTV ≥0.95 **single-source** *or* exact iptv-org schedule slug with programmes; unique name; no false-friend risk | Batch for user approval with `epg-now-playing.py` evidence; ship only after spot-check |
| **Reject** | Fuzzy name; parent-net mapping (`WBTV *` → generic Warner); religious/PEG/webcam; geo sports without feed id; Latino without MX/LAT feed proof | Keep in permanent-uncovered; do not invent bridges |

**Unlock:** name→id assignment via `epg_name_overrides.json` (and later DaddyLive `tvg-id` if upstream fills). Prefer **exact US2/WOFTV name match + now-playing proof**.

### 4. Research methods & sources

Run in order; stop at first grade A/B:

1. **Normalize** — strip `US:`/`UK:`, RAW superscripts, `(540P)`/`(948P)`, HD/SD; fold `&`/`and`.
2. **US2 exact display-name** — `/tmp/epg_ripper_US2_fresh.xml.gz` channel list (cache-bust).
3. **channels_db_cache.csv** — suggest only; never commit without feed programmes.
4. **WOFTV catalog** — `reports/woftv-match-audit.csv` + `WhatsOnFreeTvEpgCatalog`; platforms Pluto / Samsung TV Plus / Plex / Roku / Tubi / Xumo.
5. **iptv-org** — `channels.json` + `scripts/grab-iptv-org-fast-epg.sh` for FAST-looking blanks (WBTV, Afroland, WeDo).
6. **DaddyLive maps** — if upstream later assigns hex/ids, migrate override → bridge.
7. **Reject path** — FCC/RabbitEars only if name is clearly OTA (rare in Ent empty).

**Batch evidence:**

```bash
# Single
python3 scripts/epg-now-playing.py --name "UK: HARDCORE PAWN" --diagnose

# Proposed batch (build CSV of candidates first)
python3 scripts/epg-now-playing.py --name "…"   # per row until batch wrapper exists
```

Emit `reports/epg-batch-YYYYMMDD-empty-ent.md` with: display name → proposed feed id → grade → source A/B titles → reject notes.

### 5. Acceptance criteria (before mapping)

All required:

1. Proposed feed id exists in a downloaded feed with **≥1 real programme** (not placeholder).
2. Name match is **exact** after normalize (or WOFTV key exact / score ≥0.95 with unique catalog row).
3. `epg-now-playing.py` shows a non-empty title on the proposed id.
4. For medium-reasonable single-source: user OK on the batch report.
5. No mapping of spinoff/series channel → parent linear net without title proof.

### 6. Expected lift toward 75%

| Scenario | Entertainment Δ covered | Effect on raw 75% | Effect on eligible 75% |
|----------|------------------------:|-------------------|------------------------|
| High only (exact US2 leftovers + 2 WOFTV≥0.95 dual/spot-check) | **+2–10** | Negligible vs +297 need | Can push **73.7% → ~74–75%** if those rows leave permanent set *and* gain programmes |
| Medium-reasonable WBTV/Afroland/WeDo after iptv-org/WOFTV proof | **+15–40** (optimistic) | Still far from raw 75% | Helps eligible if currently counted permanent |
| Rest of empty (~300) | **0** (policy) | None | Improves honesty of eligible denom when tagged permanent |

**Do not** expect empty-id research to close raw Entertainment 75%. Treat it as: (a) skim high-conf cream, (b) classify the rest permanent.

### 7. What stays permanently uncovered + reporting

**Permanent (Entertainment empty):** religious (ABN), Infowars, webcams (`WFMZ * Camera`), geo sports blanks, Latino without feed, niche FAST with no WOFTV/iptv-org schedule (~300 rows).

**Reporting:**

- Raw scorecard: keep in denominator; show `empty_tvg_id` count in residual table.
- Eligible scorecard: exclude `reasons` containing `empty_tvg_id` (already done in residual-75 results).
- UI badge (playlist / channel meta): `epg:unmapped` or `epg:no-guide` — never fake schedules.
- Optional product later: static “Schedule unavailable” only if product wants it; **never** count toward coverage %.

---

## Blocker 2 — PEG locals

### 1. Problem size

| Slice | Count | Source |
|-------|------:|--------|
| Local Channels residuals | **138** | inventory |
| `bucket=peg` | **64** Local (+10 Entertainment) | inventory |
| `peg=True` flag | **66** Local | inventory |
| Permanent `PEG` / `PEG|empty_tvg_id` | **66** Local | permanent CSV |
| `local_other` (many city/gov mis-bucketed) | **71** | inventory — ~11+ name-heuristic PEG-ish still not flagged |
| Empty-id locals (PEG/webcam) | 3 | e.g. Cox Meriden Public Access, Sound View Government, Jacksonville fountain camera |

**Real examples:**

| tvg-id | Name |
|--------|------|
| `AccessHumboldt.us@SD` | Access Humboldt |
| `CVCEducation.us@SD` / `CVCGovernment.us@SD` | Bolton CVC Education / Government |
| `CreaTVChannel15.us@SD` … `30` | CreaTV San Jose community/classrooms |
| `DCCouncilHearingRoom120.us@SD` | DC.gov Council Hearing Room |
| `BUTV10.us@SD` | Boston University BUTV10 |
| `WCATDT15.us@SD` | Winthrop Community Access TV |
| *(empty)* | Cox Meriden Public Access Channel 15 |
| `DCC.us@SD` / `DKN.us@SD` | DC Council / DC.gov DKN (`local_other`, PEG-class) |

OTA-ish leftovers still in Locals residual (~9 call-sign-like): `WATCDT572` (57.2 subchannel — reject), `KVCRDT242` (FNX — reject primary), `KVVBLD331`, `KVTNDT251` (VTN — check if already bridged sibling), `WCCADT1`, etc. These are **not** PEG strategy; they belong to prior Phase B OTA rules.

### 2. Why previous pass couldn't fix them

Commercial EPG feeds (US2, US_LOCALS1, WOFTV, iptv-org FAST) **do not publish** public-access / city council / school schedules. Residual-75 correctly applied **P0** (exclude PEG from eligible denom) and shipped **zero** PEG bridges. Remaining Locals eligible % (~32%) is low because the non-PEG remainder is still mostly city channels and unmapped LPTV — not because PEG mapping failed.

### 3. Resolution strategy (confidence tiers)

| Tier | Rule | Action |
|------|------|--------|
| **High (policy)** | Name/id matches PEG heuristics: access, community, government, public, PEG, council, classroom, university channel, city TV | **Exclude from eligible denominator**; optional UI badge `Community` |
| **Medium-reasonable** | Ambiguous city channel (`AuroraTV`, `ConcordTV`) with no FCC commercial call | Treat as PEG-class for scorecard; manual override list |
| **Reject (mapping)** | Any attempt to invent US2/LOCALS1 bridges or synthetic rolling “Community programming” counted as real EPG | **Never** |

**This blocker is policy, not research yield.** Do not invent bridges.

### 4. Research methods & sources (classification only)

1. **Heuristic classifier** — `scripts/epg-peg-classifier.py` (planned): keywords + known id prefixes (`Access*`, `CreaTV*`, `CVC*`, `*PublicAccess*`).
2. **Manual override list** — `reports/residuals/peg-allowlist.csv` / `peg-denylist.csv` for false positives (e.g. a commercial indie that says “Community” in branding).
3. **FCC / RabbitEars** — only to **confirm** a residual is *not* PEG (has facility id + virtual channel + network) before leaving it in eligible denom.
4. **US_LOCALS1** — if call-sign primary maps and now-playing works → OTA path (Blocker 2 does not own this).

### 5. Acceptance criteria

**For exclusion (scorecard):**

1. PEG classifier or manual list tags the row.
2. Tag stored in residual inventory / permanent reasons as `PEG`.
3. Eligible denom = Locals total − PEG (− other permanent classes as agreed).

**For optional placeholder programme:**

1. Product explicitly opts in.
2. Title is clearly non-schedule (e.g. “Community channel — schedule unavailable”).
3. Placeholder **does not** increment `epgProgrammeCount` coverage / 75% metrics.

**For any real mapping:** none — PEG has no commercial guide path under current feeds.

### 6. Expected lift toward 75%

| Action | Locals raw % | Locals eligible % |
|--------|--------------|-------------------|
| Tighten PEG classifier (+11–40 city/gov from `local_other`) | unchanged (~22%) | Denom shrinks further; % may rise **if** covered OTA stay in numerator — e.g. 38/(120−N). Still unlikely to hit 75% without more OTA coverage |
| Map PEG to fake schedules | Dishonest inflation — **forbidden** | — |
| Phase B OTA only (non-PEG) | +0–5 raw | Modest |

**Verdict:** PEG resolution **does not** deliver Locals raw 75%. It makes the eligible scorecard honest. Expect eligible Locals to remain **well below 75%** unless many `local_other` rows are reclassified out *and* additional OTA primaries are proven (small N).

### 7. What stays permanently uncovered + reporting

**Permanent:** all PEG / city council / school / university access channels (~66–100+ once classifier tightened).

**Reporting:**

- **Raw %** — transparency (includes PEG).
- **Eligible %** — P0: PEG excluded (already in results).
- **UI badge** — `Community` / `PEG` on playlist group or channel extra tags; guide shows empty or optional placeholder.
- **Health** — do not require programmes on PEG ids for `epgReady`.

---

## Blocker 3 — FAST without dual-source proof

### 1. Problem size

| Slice | Count | Source |
|-------|------:|--------|
| `bucket=fast_hash` residuals | **83** rows / **~73** unique tvg-ids | inventory (Ent 81, Movies 2) |
| Permanent `FAST_no_dual_source` | **81** Ent + **2** Movies | permanent CSV |
| Of unique FAST with WOFTV display match ≥0.90 | **~8** | woftv audit join |
| … of which multi-platform in audit (`Plex; Pluto TV`, etc.) | **~2** | Arthur, River Monsters |
| … single-platform ≥0.90 | **~6** | e.g. Catfish, Taxi, Pluto Snooker 900, Most Haunted |

**Real examples:**

| tvg-id | Name | WOFTV signal |
|--------|------|--------------|
| `65367e914f123d000877d021` | UK: RIVER MONSTERS | 0.96 — **Plex; Pluto TV** (dual in catalog) |
| `6482f27c17f5e10008c10ff0` | UK: ARTHUR | 0.92 — **Plex; Pluto TV** |
| `671645c4529ac900080c9a0b` | UK: MODUS SUPER SERIES DARTS | 0.97 — Pluto only |
| `639b4f75d3d35c0007d37b30` | UK: PLUTO TV SNOOKER 900 | 0.97 — Pluto only |
| `64e89cfc9c1e3900084ca663` | US: REAL DISASTER CHANNEL | hash FAST — needs research |
| `662bf39756fc840008f25cb9` | US: SONY ONE COMPETENCIAS | hash — likely regional/FAST |

Prior Phase C already absorbed stronger WOFTV≥0.90 hits into coverage via catalog merge/aliases; **these 81 are the leftovers that failed dual-source**.

### 2. Why previous pass couldn't fix them

Residual-75 gates required **grade A/B** with dual-source discipline for FAST. Leftovers are either:

- WOFTV score high but **only one platform** listed,
- WOFTV score mid/low or name collision (`Arthur`, `Taxi`, `Catfish` — generic titles),
- No iptv-org / PLEX1 programme id proven on host,
- UK Pluto clones whose schedule may diverge from US WOFTV keys.

Shipping them would have violated the high-conf bar that kept Movies honest at 77%.

### 3. Resolution strategy (confidence tiers)

**Define dual-source** (any one pair counts):

| Pair | Acceptable proof |
|------|------------------|
| WOFTV + Pluto slug | Same now-playing title window ±15 min (WOFTV sample vs iptv-org/Pluto EPG or live Pluto slug) |
| WOFTV + Samsung TV Plus | Same |
| WOFTV + Plex / Roku / Xumo | Same |
| WOFTV + iptv-org schedules XML | Feed id has programmes; titles agree ±15 min |
| Multi-platform WOFTV row (`Plex; Pluto TV`) + now-playing on one platform | Counts as dual **if** both platforms share the same catalog key and sample title is non-generic |

**Reasonable single-source exception:**

| Tier | Rule |
|------|------|
| **High** | Dual-source as above + unique name |
| **Medium-reasonable** | **WOFTV ≥0.95 exact**, catalog key unique, sample title specific (not “Movies”/“Comedy”), **and** `epg-now-playing` / WOFTV sample non-empty — **even if only one platform** | Allowed for this residual pass with explicit user batch approval |
| **Reject** | Score 0.90–0.94 single-source; generic titles; UK/US geo mismatch without proof; hex → US2 linear |

### 4. Research methods & sources

1. Re-join `fast_hash` residuals to `woftv-match-audit.csv` by display name / norm.
2. Partition queue: multi-platform ≥0.90 → high; single ≥0.95 → medium-reasonable; else research.
3. **iptv-org FAST grab** — map hex via `FastChannelTvgIdResolver` / mjh.nz; require programmes in grabbed XML.
4. **PLEX1 / DISTROTV1** gap-fill (cache-bust URLs) when name matches.
5. **DaddyLive** hex stability — bridge `hex → woftv/iptv-org id`, not → US2.
6. Batch `epg-now-playing.py` against proposed feed ids; record titles in approval markdown.

### 5. Acceptance criteria

1. Proposed target is a FAST feed id (WOFTV key / iptv-org / PLEX1) — **never** a US2 cable id for hex rows.
2. Dual-source **or** medium-reasonable WOFTV ≥0.95 exact exception.
3. Now-playing / sample title is specific; reject generics.
4. Programmes present after merge path (`WhatsOnFreeTvEpgCatalog` or gap-fill).
5. User approves batch (`reports/epg-batch-YYYYMMDD-fast-dual.md`).

### 6. Expected lift toward 75%

| Scenario | Ent Δ | Movies Δ | Notes |
|----------|------:|---------:|-------|
| High dual-source only (~2–5 unique) | +2–5 | 0–1 | Safe |
| + Medium-reasonable WOFTV ≥0.95 single (~4–8) | +4–8 | 0–1 | Clears much of eligible 75% near-miss |
| Aggressive 0.90 single-source | +10–20 | +1–2 | **Not recommended** — false-friend risk |

Combined with Blocker 1 skim (+2–10), Entertainment **eligible** can reach **≥75%** with reasonable confidence. **Raw** 75% (+297) remains out of reach without lowering standards.

### 7. What stays permanently uncovered + reporting

**Permanent:** ~60–70 hex FAST with no WOFTV≥0.95, no iptv-org schedule, or generic-title collisions.

**Reporting:**

- Keep `FAST_no_dual_source` in permanent CSV until proven.
- When medium-reasonable single-source ships, retag reason → `fast_woftv_exact_095` (covered) — do not leave as permanent.
- UI: no special badge required beyond normal guide; uncovered FAST stay `epg:no-guide`.
- Scorecard footnote: “FAST medium-reasonable = WOFTV ≥0.95 exact single-source, user-approved.”

---

## Cross-blocker execution order

```
0. Freeze policy text (PEG P0; FAST dual-source + ≥0.95 single exception; empty permanent class)
1. Tighten PEG classifier → refresh eligible Locals denom (no bridges)
2. FAST dual-source queue (high) + ≥0.95 single (medium) → now-playing batch → user OK → bridges/aliases
3. Empty Ent skim: exact US2 leftovers + WOFTV≥0.95 names → name_overrides → user OK
4. Re-run scorecard (raw + eligible); stop when Entertainment eligible ≥75% or queues empty
5. Document permanent leftovers; do not force Phase D false friends
```

**Do not** start with empty-id volume research — ROI is worse than FAST cream + PEG policy.

---

## Tooling (build only when a phase starts)

| Script | Blocker |
|--------|---------|
| `scripts/epg-peg-classifier.py` | 2 — expand PEG tags; emit override CSVs |
| `scripts/epg-empty-id-research.py` | 1 — US2/WOFTV/iptv-org candidates + grade |
| `scripts/epg-fast-dual-source-queue.py` | 3 — partition dual vs ≥0.95 single vs reject |
| `scripts/epg-now-playing-batch.py` | 1+3 — proof tables for approval |
| `scripts/epg-coverage-scorecard.py` | all — raw vs eligible vs PEG-excluded |

Reuse: `epg-now-playing.py`, `epg-residual-75-execute.py`, `woftv-match-audit.csv`, `grab-iptv-org-fast-epg.sh`.

---

## Success definition (reasonable confidence)

| Goal | Pass condition |
|------|----------------|
| Entertainment eligible ≥75% | Covered / (total − permanent) ≥ 0.75 after FAST medium + empty skim |
| Entertainment raw 75% | **Not required** — document ceiling ~60–65% high-conf |
| Locals eligible | PEG fully classified; report OTA-only %; raw remains transparency-only |
| No dishonest coverage | Zero PEG placeholders counted; zero hex→US2; zero subchannel→primary |

---

## Appendix — Artifact paths

| Artifact | Path |
|----------|------|
| This plan | `reports/epg-blocker-resolution-plan.md` |
| Prior plan / results | `reports/epg-residual-75-plan.md`, `reports/epg-residual-75-results.md` |
| Residual inventory | `reports/residuals/residual-inventory.csv` |
| Permanent tags | `reports/residuals/permanent-uncovered.csv` |
| Last apply batch | `reports/residuals/applied-candidates.csv` |
| WOFTV audit | `reports/woftv-match-audit.csv` |
| Bridges / overrides | `app/src/main/assets/epg_id_bridge.json`, `epg_name_overrides.json` |
| Now-playing probe | `scripts/epg-now-playing.py` |

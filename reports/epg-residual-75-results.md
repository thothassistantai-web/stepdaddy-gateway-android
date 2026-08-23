# EPG residual → 75% results

**Date:** 2026-08-23 03:55 UTC
**Policy:** P0 PEG excluded from eligible denominator; placeholders not counted.
**Gates:** Grade A/B only (exact US2 name, call-sign LOCALS1, WOFTV ≥0.90 + catalog key).

## Coverage scorecard

| Category | Plan baseline | After feed-only | After +WOFTV | Eligible (+WOFTV, no PEG) | Raw 75% need |
|----------|---------------|-----------------|--------------|---------------------------|--------------|
| Movies | 127/246 (51.6%) | 130/248 (52.4%) | 195/248 (78.6%) | 194/247 (78.5%) | 184 |
| Entertainment | 729/2070 (35.2%) | 778/2137 (36.4%) | 1359/2137 (63.6%) | 1353/2121 (63.8%) | 1552 |
| Local Channels | 28/175 (16.0%) | 37/175 (21.1%) | 38/175 (21.7%) | 38/109 (34.9%) | 131 |

### Before this apply (same scorecard)

| Category | Feed-only before | +WOFTV before |
|----------|------------------|---------------|
| Movies | 129/248 (52.0%) | 194/248 (78.2%) |
| Entertainment | 773/2137 (36.2%) | 1358/2137 (63.5%) |
| Local Channels | 37/175 (21.1%) | 38/175 (21.7%) |

## Applied counts

- **Bridges added/updated:** 0
- **Name overrides added:** 6
- **WOFTV NAME_ALIASES added:** 0
- **Shipped candidates (A/B):** 6
- **Rejects logged:** 2
- **PEG in playlist (excluded from eligible):** {'Entertainment': 16, 'Movies': 1, 'Local Channels': 66}

## Phase breakdown (shipped)

- Phase A: 6
- Phase B: 0
- Phase C: 0
- Phase D: 0

## Top remaining blockers

- `Entertainment:empty_tvg_id`: 347
- `Entertainment:no_high_conf_source`: 326
- `Entertainment:FAST_no_dual_source`: 81
- `Local Channels:no_high_conf_source`: 70
- `Local Channels:PEG`: 66
- `Movies:no_high_conf_source`: 38
- `Entertainment:regional_latin`: 19
- `Entertainment:PEG`: 10
- `Movies:empty_tvg_id`: 9
- `Movies:regional_latin`: 4
- `Local Channels:empty_tvg_id`: 3
- `Movies:FAST_no_dual_source`: 2

## Artifacts

- Residual inventory: `reports/residuals/residual-inventory.csv`
- Applied candidates: `reports/residuals/applied-candidates.csv`
- Permanent uncovered: `reports/residuals/permanent-uncovered.csv`
- Bridge asset: `app/src/main/assets/epg_id_bridge.json` (815 keys)

## Notes

- Host scorecard treats WOFTV catalog name hits as covered (device `mergeGaps` path).
- Raw 75% for Entertainment/Locals remains a stretch; eligible % is the honest goal metric.
- No low-confidence fuzzy network matches were applied.

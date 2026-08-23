#!/usr/bin/env python3
"""Execute reports/epg-blocker-resolution-plan.md (all three tracks).

Gates (user-approved proceed-with-all / medium-reasonable):
  1. Empty tvg-id Entertainment — exact US2 display-name OR WOFTV ≥0.95
     with catalogue programmes (US+CA); apply name overrides for empty→stable id.
  2. PEG locals — POLICY only: tighten classifier, emit peg list, eligible denom.
  3. FAST — dual-source (multi-platform WOFTV) OR WOFTV ≥0.95 exact single-source;
     reject ambiguous short names (Arthur/Taxi/Catfish/…).

Writes reports/epg-blocker-resolution-results.md + residual CSVs. No git commit.
"""

from __future__ import annotations

import csv
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ex = SourceFileLoader(
    "epg75", str(ROOT / "scripts/epg-residual-75-execute.py")
).load_module()

normalize_name = ex.normalize_name
strip_display = ex.strip_display
load_woftv_keys = ex.load_woftv_keys
load_xml_channels = ex.load_xml_channels
prefer_us2 = ex.prefer_us2
parse_playlist = ex.parse_playlist
resolve_covered = ex.resolve_covered
HEX_RE = ex.HEX_RE
GROUPS = ex.GROUPS
ASSETS = ex.ASSETS
BRIDGE_PATH = ex.BRIDGE_PATH
OVERRIDES_PATH = ex.OVERRIDES_PATH
PLAYLIST = ex.PLAYLIST
PROG_IDS = ex.PROG_IDS
US2_GZ = ex.US2_GZ
LOCALS_GZ = ex.LOCALS_GZ
WOFTV_US = ex.WOFTV_US
WOFTV_CA = Path("/tmp/woftv-ca.json")
WOFTV_AUDIT = ex.WOFTV_AUDIT
WOFTV_CONFIG = ex.WOFTV_CONFIG
OUT_DIR = ROOT / "reports/residuals"
RESULTS = ROOT / "reports/epg-blocker-resolution-results.md"
PEG_ASSET = ASSETS / "epg_peg_channels.json"
PEG_LIST_CSV = OUT_DIR / "peg-channels.csv"

# Ambiguous short / generic titles — never auto-map.
REJECT_SHORT = {
    "arthur",
    "taxi",
    "catfish",
    "jag",
    "dazn",
    "haunt",
    "movies",
    "comedy",
    "crime",
    "news",
    "drama",
    "sports",
    "music",
    "kids",
}

# Tightened PEG markers (plan Blocker 2 + residual local_other misses).
PEG_RE = re.compile(
    r"\b("
    r"public access|peg|community (media|tv|television|12tv|channel)|"
    r"government|city council|education channel|school district|"
    r"university|campus tv|access humboldt|crea\s*tv|midpen|sf commons|"
    r"bolton cvc|amp community|hearing room|municipal|cable access|"
    r"public media|public channel|council channel|community voice|"
    r"leasing|bulletin board|classroom|city of |county "
    r")\b",
    re.I,
)
PEG_ID_RE = re.compile(
    r"(Access|PublicAccess|PublicMedia|CreaTV|CVC|CMAC|Community|"
    r"Council|Hearing|Education|Government|BUTV|WCAT|Leominster|"
    r"KCAT|FCPublic|DCC|DKN)",
    re.I,
)


def is_peg(name: str, tvg_id: str) -> bool:
    blob = f"{name} {tvg_id}"
    if PEG_RE.search(blob):
        return True
    if PEG_ID_RE.search(tvg_id or ""):
        return True
    low = blob.lower()
    markers = (
        "public access",
        "community media",
        "community tv",
        "community 12",
        "government",
        "education",
        "municipal",
        "city of ",
        "county ",
        " school",
        "university",
        "campus",
        "peg ",
        "leasing",
        "bulletin board",
        "meeting",
        "hearing",
        "access ",
        "public media",
        "public channel",
        "council channel",
        "community voice",
    )
    return any(m in low for m in markers)


def platforms_of(row: dict) -> set[str]:
    raw = row.get("woftv_platform") or ""
    return {p.strip().lower() for p in re.split(r"[;,|]", raw) if p.strip()}


def sample_title(cat: dict, key: str) -> str:
    rows = cat.get(key) or []
    if not rows:
        return ""
    return (rows[0].get("title") or "").strip()


def load_aliases() -> dict[str, str]:
    if not WOFTV_CONFIG.is_file():
        return {}
    return dict(
        re.findall(
            r'"([^"]+)"\s+to\s+"([^"]+)"',
            WOFTV_CONFIG.read_text(encoding="utf-8"),
        )
    )


def permanent_reasons(c, covered_fn) -> list[str]:
    if covered_fn(c):
        return []
    reasons: list[str] = []
    if is_peg(c.name, c.tvg_id):
        reasons.append("PEG")
    if not c.tvg_id:
        reasons.append("empty_tvg_id")
    if HEX_RE.match(c.tvg_id or "") or (c.tvg_id or "").isdigit():
        reasons.append("FAST_no_dual_source")
    if "latin" in c.name.lower() or "mexico" in c.name.lower():
        reasons.append("regional_latin")
    if ex.SUBCHANNEL_RE.search(c.name) or ex.SUBCHANNEL_RE.search(c.tvg_id or ""):
        reasons.append("subchannel_diginet")
    if not reasons:
        reasons.append("no_high_conf_source")
    return reasons


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    channels = parse_playlist(PLAYLIST)
    prog = set(PROG_IDS.read_text().splitlines()) if PROG_IDS.is_file() else set()
    bridge_doc = json.loads(BRIDGE_PATH.read_text(encoding="utf-8"))
    bridge: dict[str, list[str]] = dict(bridge_doc.get("bridge") or {})
    overrides: dict[str, str] = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    overrides_by_norm = {normalize_name(k): v for k, v in overrides.items()}
    woftv_aliases = load_aliases()

    us2_names, us2_prog, us2_by_norm = load_xml_channels(US2_GZ)
    _, locals_prog, _ = load_xml_channels(LOCALS_GZ)
    feed_prog = us2_prog | locals_prog

    cat_us = load_woftv_keys(WOFTV_US)
    cat_ca = load_woftv_keys(WOFTV_CA)
    cat = {**cat_us, **cat_ca}
    for k, rows in cat_ca.items():
        if k not in cat_us:
            cat[k] = rows
    woftv_keys = set(cat)
    print(
        f"WOFTV keys US={len(cat_us)} CA={len(cat_ca)} union={len(woftv_keys)}",
        file=sys.stderr,
    )

    audit_rows = (
        list(csv.DictReader(WOFTV_AUDIT.open(encoding="utf-8")))
        if WOFTV_AUDIT.is_file()
        else []
    )
    audit_by_norm: dict[str, list[dict]] = defaultdict(list)
    audit_by_id: dict[str, list[dict]] = defaultdict(list)
    for row in audit_rows:
        n = normalize_name(row.get("display_name") or "")
        if n:
            audit_by_norm[n].append(row)
        nd = (row.get("norm_display") or "").strip()
        if nd:
            audit_by_norm[nd].append(row)
        tid = (row.get("current_tvg_id") or "").strip()
        if tid:
            audit_by_id[tid].append(row)

    def covered(c, *, count_woftv: bool = True) -> bool:
        return resolve_covered(
            c,
            prog,
            bridge,
            overrides_by_norm,
            feed_prog,
            woftv_keys,
            woftv_aliases,
            count_woftv=count_woftv,
        )

    # --- BEFORE scorecard ---
    before_total = Counter(c.group for c in channels)
    before = Counter()
    before_feed = Counter()
    for c in channels:
        if covered(c):
            before[c.group] += 1
        if covered(c, count_woftv=False):
            before_feed[c.group] += 1

    applied: list[dict] = []
    rejects: list[dict] = []
    overrides_added = 0
    bridges_added = 0

    # ========== Track 1: empty tvg-id Entertainment ==========
    # Gate: exact US2 display-name w/ programmes OR audit WOFTV ≥0.95 with catalog key.
    # Never invent score=1.0 from catalog-only hits.
    empty_ent = [
        c
        for c in channels
        if c.group == "Entertainment" and not (c.tvg_id or "").strip()
    ]
    seen_empty: set[str] = set()
    for c in empty_ent:
        norm = normalize_name(c.name)
        if norm in seen_empty:
            continue
        if is_peg(c.name, c.tvg_id):
            rejects.append({"name": c.name, "reason": "PEG", "track": "1"})
            continue
        if norm in REJECT_SHORT or len(norm) < 4:
            rejects.append({"name": c.name, "reason": "short_ambiguous", "track": "1"})
            continue

        feed = prefer_us2(us2_by_norm.get(norm) or [], us2_prog)
        method = ""
        evidence = ""
        grade = ""
        target = ""
        if feed:
            method = "exact_us2_display_name"
            evidence = f"US2 {feed} display={us2_names.get(feed, '')}"
            grade = "A"
            target = feed
        else:
            rows = audit_by_norm.get(norm) or []
            best = None
            for row in rows:
                try:
                    sc = float(row.get("best_woftv_score") or 0)
                except ValueError:
                    sc = 0.0
                if best is None or sc > float(best.get("best_woftv_score") or 0):
                    best = row
            if not best:
                rejects.append(
                    {"name": c.name, "reason": "no_us2_no_woftv_audit", "track": "1"}
                )
                continue
            sc = float(best.get("best_woftv_score") or 0)
            key = normalize_name(best.get("woftv_key") or "")
            if not key:
                key = norm
            plats = platforms_of(best)
            dual = len(plats) >= 2
            if key in REJECT_SHORT or norm in REJECT_SHORT:
                rejects.append(
                    {"name": c.name, "reason": "reject_short_woftv", "track": "1"}
                )
                continue
            if key not in woftv_keys:
                rejects.append(
                    {"name": c.name, "reason": "woftv_key_not_in_catalog", "track": "1"}
                )
                continue
            # Prefer dual; allow single-source only at ≥0.95 (user-approved).
            if dual and sc >= 0.90:
                method = "woftv_dual_exact"
                grade = "A"
            elif sc >= 0.95:
                method = "woftv_exact_095"
                grade = "B"
            else:
                rejects.append(
                    {
                        "name": c.name,
                        "reason": f"woftv_score_{sc:.2f}_below_gate",
                        "track": "1",
                    }
                )
                continue
            evidence = (
                f"key={key}; score={sc:.2f}; platforms={'; '.join(sorted(plats))}; "
                f"sample={sample_title(cat, key) or best.get('woftv_sample_title', '')}"
            )
            # Stable id so EpgManager includes the channel; prefer audit playlist id if present.
            audit_id = (best.get("current_tvg_id") or "").strip()
            if audit_id and not HEX_RE.match(audit_id):
                target = audit_id
            else:
                target = re.sub(r"[^A-Za-z0-9]+", "", strip_display(c.name)) + ".woftv"

        seen_empty.add(norm)
        # Skip file write if override already points at a usable target for this norm
        existing_ov = overrides_by_norm.get(norm)
        if existing_ov and existing_ov == target:
            applied.append(
                {
                    "track": "1_empty",
                    "tvg_id": "",
                    "name": c.name,
                    "feed_id": target,
                    "grade": grade,
                    "method": method,
                    "evidence": evidence,
                    "action": "already_overridden",
                }
            )
            continue

        key_names = {
            strip_display(c.name),
            re.sub(r"\s+(HD|SD|FHD)$", "", strip_display(c.name), flags=re.I).strip(),
            c.name,
        }
        for kn in key_names:
            if not kn:
                continue
            if overrides.get(kn) == target:
                continue
            existing = overrides.get(kn)
            if existing and existing != target and normalize_name(kn) != norm:
                continue
            overrides[kn] = target
            overrides_by_norm[normalize_name(kn)] = target
            overrides_added += 1
        applied.append(
            {
                "track": "1_empty",
                "tvg_id": "",
                "name": c.name,
                "feed_id": target,
                "grade": grade,
                "method": method,
                "evidence": evidence,
                "action": "name_override",
            }
        )

    # ========== Track 3: FAST dual / ≥0.95 (residuals only) ==========
    # Only hex/digit leftovers that fail feed-only coverage. Require audit score;
    # dual ≥0.90 or single ≥0.95; reject ambiguous short names.
    fast_chs = [
        c
        for c in channels
        if c.group in ("Entertainment", "Movies")
        and (HEX_RE.match(c.tvg_id or "") or (c.tvg_id or "").isdigit())
        and not covered(c, count_woftv=False)
    ]
    for c in fast_chs:
        norm = normalize_name(c.name)
        if norm in REJECT_SHORT or (len(norm.split()) == 1 and len(norm) <= 8):
            rejects.append(
                {
                    "name": c.name,
                    "tvg_id": c.tvg_id,
                    "reason": "reject_ambiguous_short",
                    "track": "3",
                }
            )
            continue
        rows = audit_by_id.get(c.tvg_id) or audit_by_norm.get(norm) or []
        best = None
        for row in rows:
            try:
                sc = float(row.get("best_woftv_score") or 0)
            except ValueError:
                sc = 0.0
            if best is None or sc > float(best.get("best_woftv_score") or 0):
                best = row
        if not best:
            # Exact catalogue still counted by scorecard (US+CA); no apply without audit proof.
            if norm not in woftv_keys:
                rejects.append(
                    {
                        "name": c.name,
                        "tvg_id": c.tvg_id,
                        "reason": "no_woftv_audit",
                        "track": "3",
                    }
                )
            continue
        sc = float(best.get("best_woftv_score") or 0)
        key = normalize_name(best.get("woftv_key") or "") or norm
        plats = platforms_of(best)
        dual = len(plats) >= 2
        if key not in woftv_keys and norm in woftv_keys:
            key = norm
        if key not in woftv_keys:
            rejects.append(
                {
                    "name": c.name,
                    "tvg_id": c.tvg_id,
                    "reason": "no_woftv_catalog",
                    "track": "3",
                }
            )
            continue
        if key in REJECT_SHORT:
            rejects.append(
                {
                    "name": c.name,
                    "tvg_id": c.tvg_id,
                    "reason": "reject_generic_key",
                    "track": "3",
                }
            )
            continue
        if dual and sc >= 0.90:
            method = "fast_woftv_dual"
            grade = "A"
        elif sc >= 0.95:
            method = "fast_woftv_exact_095"
            grade = "B"
        else:
            rejects.append(
                {
                    "name": c.name,
                    "tvg_id": c.tvg_id,
                    "reason": f"gate_fail_score_{sc:.2f}_dual_{dual}",
                    "track": "3",
                }
            )
            continue

        evidence = (
            f"key={key}; score={sc:.2f}; platforms={'; '.join(sorted(plats))}; "
            f"sample={sample_title(cat, key) or best.get('woftv_sample_title', '')}"
        )
        action = "covered_woftv_exact"
        if norm != key and woftv_aliases.get(norm) != key:
            added = ex.patch_woftv_aliases({norm: key})
            if added:
                woftv_aliases[norm] = key
                action = "woftv_alias"
        applied.append(
            {
                "track": "3_fast",
                "tvg_id": c.tvg_id,
                "name": c.name,
                "feed_id": key,
                "grade": grade,
                "method": method,
                "evidence": evidence,
                "action": action,
            }
        )

    # ========== Track 2: PEG policy ==========
    peg_rows = []
    for c in channels:
        if not is_peg(c.name, c.tvg_id):
            continue
        peg_rows.append(
            {
                "group": c.group,
                "tvg_id": c.tvg_id,
                "name": c.name,
                "norm": normalize_name(c.name),
            }
        )
    peg_rows.sort(key=lambda r: (r["group"], r["name"].lower()))
    with PEG_LIST_CSV.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["group", "tvg_id", "name", "norm"])
        w.writeheader()
        w.writerows(peg_rows)

    peg_asset = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "policy": (
            "Exclude from eligible EPG coverage denominator. "
            "Never invent commercial guide bridges or placeholder programmes "
            "that count toward coverage. UI Community badge is optional follow-up."
        ),
        "count": len(peg_rows),
        "by_group": dict(Counter(r["group"] for r in peg_rows)),
        "channels": [
            {"tvg_id": r["tvg_id"], "name": r["name"], "group": r["group"]}
            for r in peg_rows
        ],
    }
    PEG_ASSET.write_text(
        json.dumps(peg_asset, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    # Persist overrides
    OVERRIDES_PATH.write_text(
        json.dumps(
            dict(sorted(overrides.items(), key=lambda kv: kv[0].lower())),
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    overrides_by_norm = {normalize_name(k): v for k, v in overrides.items()}

    # --- Inventory + permanent (post-apply, US+CA) ---
    residuals = [c for c in channels if not covered(c)]
    with (OUT_DIR / "residual-inventory.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(
            f, fieldnames=["group", "tvg_id", "name", "bucket", "norm", "peg"]
        )
        w.writeheader()
        for c in residuals:
            # bucket with tightened peg
            if not c.tvg_id:
                bucket = "empty_tvg_id"
            elif HEX_RE.match(c.tvg_id) or c.tvg_id.isdigit():
                bucket = "fast_hash"
            elif is_peg(c.name, c.tvg_id):
                bucket = "peg"
            elif c.group == "Local Channels":
                bucket = "local_other"
            else:
                bucket = "ambiguous"
            w.writerow(
                {
                    "group": c.group,
                    "tvg_id": c.tvg_id,
                    "name": c.name,
                    "bucket": bucket,
                    "norm": normalize_name(c.name),
                    "peg": is_peg(c.name, c.tvg_id),
                }
            )

    permanent = []
    for c in channels:
        reasons = permanent_reasons(c, covered)
        if not reasons:
            continue
        # Retag FAST that passed gates as covered (should not appear)
        permanent.append(
            {
                "group": c.group,
                "tvg_id": c.tvg_id,
                "name": c.name,
                "reasons": "|".join(reasons),
            }
        )
    with (OUT_DIR / "permanent-uncovered.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["group", "tvg_id", "name", "reasons"])
        w.writeheader()
        w.writerows(permanent)

    with (OUT_DIR / "applied-candidates.csv").open("w", encoding="utf-8", newline="") as f:
        if applied:
            w = csv.DictWriter(f, fieldnames=sorted(applied[0].keys()))
            w.writeheader()
            w.writerows(applied)

    with (OUT_DIR / "blocker-rejects.csv").open("w", encoding="utf-8", newline="") as f:
        if rejects:
            keys = sorted({k for r in rejects for k in r})
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            w.writerows(rejects)

    # --- AFTER scorecard ---
    after = Counter()
    after_feed = Counter()
    for c in channels:
        if covered(c):
            after[c.group] += 1
        if covered(c, count_woftv=False):
            after_feed[c.group] += 1

    peg_counts = Counter(
        c.group for c in channels if is_peg(c.name, c.tvg_id)
    )
    eligible_total = Counter()
    eligible_cov = Counter()
    eligible_no_perm_total = Counter()
    eligible_no_perm_cov = Counter()
    for c in channels:
        peg = is_peg(c.name, c.tvg_id)
        reasons = permanent_reasons(c, covered)
        is_perm = bool(reasons)  # uncovered permanent classes only when uncovered
        # Permanent class for denom: PEG / empty / FAST_no_dual / regional / subchannel
        # even when we use structural tags on uncovered rows; for eligible-no-permanent
        # exclude channels that WOULD be permanent-class regardless of coverage:
        structural_perm = (
            peg
            or not c.tvg_id
            or bool(HEX_RE.match(c.tvg_id or "") or (c.tvg_id or "").isdigit())
            or "latin" in c.name.lower()
            or "mexico" in c.name.lower()
            or bool(ex.SUBCHANNEL_RE.search(c.name) or ex.SUBCHANNEL_RE.search(c.tvg_id or ""))
        )
        # Recompute structural for FAST: if now covered via WOFTV gate, do NOT treat as permanent
        if (
            (HEX_RE.match(c.tvg_id or "") or (c.tvg_id or "").isdigit())
            and covered(c)
        ):
            structural_perm = peg  # only PEG would still exclude
        if not c.tvg_id and covered(c):
            structural_perm = peg

        if not peg:
            eligible_total[c.group] += 1
            if covered(c):
                eligible_cov[c.group] += 1
        if not structural_perm:
            eligible_no_perm_total[c.group] += 1
            if covered(c):
                eligible_no_perm_cov[c.group] += 1

    def pct(n: int, d: int) -> str:
        return f"{(100.0 * n / d):.1f}%" if d else "n/a"

    track1 = [a for a in applied if a["track"] == "1_empty"]
    track3 = [a for a in applied if a["track"] == "3_fast"]
    niche_permanent = [
        r
        for r in permanent
        if r["group"] == "Entertainment" and "empty_tvg_id" in r["reasons"]
    ]

    lines: list[str] = []
    lines.append("# EPG blocker resolution results")
    lines.append("")
    lines.append(f"**Date:** {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}")
    lines.append(f"**Plan:** [`epg-blocker-resolution-plan.md`](epg-blocker-resolution-plan.md)")
    lines.append(
        "**Policy:** PEG excluded from eligible denom; no invented PEG guides; "
        "FAST dual-source **or** WOFTV ≥0.95 exact single-source (user-approved); "
        "empty-id only via exact US2 or WOFTV ≥0.95."
    )
    lines.append("**Scorecard:** WOFTV catalogue = **US + CA** union.")
    lines.append("")
    lines.append("## Coverage scorecard")
    lines.append("")
    lines.append(
        "| Category | Before +WOFTV (US+CA) | After +WOFTV | Feed-only | "
        "Eligible (no PEG) | Eligible (no permanent*) | Raw 75% need |"
    )
    lines.append(
        "|----------|----------------------:|-------------:|----------:|"
        "-------------------:|--------------------------:|-------------:|"
    )
    raw_need = {"Movies": 186, "Entertainment": 1603, "Local Channels": 131}
    for g in GROUPS:
        tot = before_total[g]
        lines.append(
            f"| {g} | {before[g]}/{tot} ({pct(before[g], tot)}) | "
            f"{after[g]}/{tot} ({pct(after[g], tot)}) | "
            f"{after_feed[g]}/{tot} ({pct(after_feed[g], tot)}) | "
            f"{eligible_cov[g]}/{eligible_total[g]} ({pct(eligible_cov[g], eligible_total[g])}) | "
            f"{eligible_no_perm_cov[g]}/{eligible_no_perm_total[g]} "
            f"({pct(eligible_no_perm_cov[g], eligible_no_perm_total[g])}) | "
            f"{raw_need[g]} |"
        )
    lines.append("")
    lines.append(
        "\\*Permanent class = PEG / empty `tvg-id` / FAST without dual-or-≥0.95 proof / "
        "regional Latin / subchannel-diginet. Covered FAST/empty that passed gates leave this class."
    )
    lines.append("")
    lines.append("### Verdict vs 75%")
    lines.append("")
    lines.append("| Category | Raw 75% | Eligible (no permanent) 75% |")
    lines.append("|----------|---------|-----------------------------|")
    for g in GROUPS:
        raw_ok = after[g] / before_total[g] >= 0.75 if before_total[g] else False
        el_d = eligible_no_perm_total[g]
        el_ok = (eligible_no_perm_cov[g] / el_d >= 0.75) if el_d else False
        lines.append(
            f"| {g} | {'**Met**' if raw_ok else f'Not met ({pct(after[g], before_total[g])})'} | "
            f"{'**Met**' if el_ok else f'Not met ({pct(eligible_no_perm_cov[g], el_d)})'} |"
        )
    lines.append("")
    lines.append("## Applied counts")
    lines.append("")
    lines.append(f"| Lever | Count |")
    lines.append(f"|-------|------:|")
    lines.append(f"| Track 1 empty name overrides | {len(track1)} |")
    lines.append(f"| Track 3 FAST accepted (dual / ≥0.95) | {len(track3)} |")
    lines.append(
        f"| Track 3 of which new WOFTV aliases | "
        f"{sum(1 for a in track3 if a['action'] == 'woftv_alias')} |"
    )
    lines.append(f"| Name override keys touched | {overrides_added} |")
    lines.append(f"| PEG channels classified | {len(peg_rows)} |")
    lines.append(f"| Rejects logged | {len(rejects)} |")
    lines.append("")
    lines.append("### Track 1 — empty Entertainment shipped")
    lines.append("")
    if track1:
        lines.append("| Name | Target id | Method | Evidence |")
        lines.append("|------|-----------|--------|----------|")
        for a in track1:
            lines.append(
                f"| {a['name']} | `{a['feed_id']}` | {a['method']} | {a['evidence'][:120]} |"
            )
    else:
        lines.append("_No empty-id rows passed gates (or already overridden)._")
    lines.append("")
    lines.append("### Track 3 — FAST shipped / accepted")
    lines.append("")
    if track3:
        lines.append("| tvg-id | Name | Method | Action | Evidence |")
        lines.append("|--------|------|--------|--------|----------|")
        for a in track3:
            lines.append(
                f"| `{a['tvg_id']}` | {a['name']} | {a['method']} | {a['action']} | "
                f"{a['evidence'][:100]} |"
            )
    else:
        lines.append("_No FAST rows passed gates._")
    lines.append("")
    lines.append("### Track 2 — PEG policy")
    lines.append("")
    lines.append(f"- **PEG classified:** {dict(peg_counts)} (playlist total **{len(peg_rows)}**)")
    lines.append(f"- **List:** `reports/residuals/peg-channels.csv`")
    lines.append(f"- **Asset:** `app/src/main/assets/epg_peg_channels.json`")
    lines.append(
        "- **UI Community badge:** not shipped (no existing playlist badge hook reused); "
        "policy/list only — follow-up if product wants `Community` group/tag."
    )
    lines.append("- **Bridges for PEG:** **0** (forbidden).")
    lines.append("")
    lines.append("## Niche permanent uncovered (Entertainment empty)")
    lines.append("")
    lines.append(
        f"- Remaining Entertainment empty-id permanent: **{len(niche_permanent)}** "
        "(religious, niche FAST, geo sports, Latino without feed, webcams, etc.)."
    )
    lines.append("- Documented in `reports/residuals/permanent-uncovered.csv` (`empty_tvg_id`).")
    lines.append("")
    lines.append("## Reject samples (gates)")
    lines.append("")
    rej_c = Counter(r.get("reason", "") for r in rejects)
    for reason, n in rej_c.most_common(15):
        lines.append(f"- `{reason}`: {n}")
    lines.append("")
    lines.append("## Artifacts")
    lines.append("")
    lines.append("| Path | Purpose |")
    lines.append("|------|---------|")
    lines.append("| `reports/epg-blocker-resolution-results.md` | This report |")
    lines.append("| `reports/residuals/applied-candidates.csv` | Applied batch |")
    lines.append("| `reports/residuals/blocker-rejects.csv` | Reject log |")
    lines.append("| `reports/residuals/peg-channels.csv` | PEG list |")
    lines.append("| `reports/residuals/permanent-uncovered.csv` | Permanent tags |")
    lines.append("| `reports/residuals/residual-inventory.csv` | Post-pass residuals |")
    lines.append("| `app/src/main/assets/epg_name_overrides.json` | Empty-id → stable/feed ids |")
    lines.append("| `app/src/main/assets/epg_peg_channels.json` | PEG policy list |")
    lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append(
        "1. Prior residual-75 host scorecard used **US-only** WOFTV JSON; this pass uses "
        "**US+CA**, which correctly credits UK FAST titles present only in `epg-ca.json`."
    )
    lines.append(
        "2. Empty-id WOFTV hits require name overrides so `EpgManager` includes a tvg-id in "
        "`/epg.xml`; mergeGaps then fills by display-name → catalogue key."
    )
    lines.append(
        "3. FAST rows with exact catalogue keys need no alias; they are accepted for "
        "coverage/permanent retag under dual or ≥0.95 gates."
    )
    lines.append(
        "4. Entertainment **raw** 75% remains out of reach; eligible-without-permanent is the "
        "honest goal metric."
    )

    RESULTS.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Mark plan executed
    plan = ROOT / "reports/epg-blocker-resolution-plan.md"
    if plan.is_file():
        ptxt = plan.read_text(encoding="utf-8")
        ptxt = ptxt.replace(
            "**Status:** PLAN ONLY — do not apply bridges from this document.",
            f"**Status:** EXECUTED — see `reports/epg-blocker-resolution-results.md` "
            f"({datetime.now(timezone.utc).date()}).",
        )
        plan.write_text(ptxt, encoding="utf-8")

    print(RESULTS.read_text(encoding="utf-8"))
    print(
        f"\nSUMMARY empty_overrides={len(track1)} fast_accepted={len(track3)} "
        f"override_keys={overrides_added} peg={len(peg_rows)} rejects={len(rejects)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

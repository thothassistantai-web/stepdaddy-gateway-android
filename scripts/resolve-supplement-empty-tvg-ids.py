#!/usr/bin/env python3
"""Resolve empty supplement (non-DaddyLive) tvg-ids from live playlist.

Inventories FAST / NTV / dulo / other supplement rows with missing tvg-id,
resolves via iptv-org channels_db, FAST EPG catalog, epgshare, WOFTV name
match, and existing overrides. Writes epg_name_overrides.json and
reports/supplement-empty-tvg-id-resolution.md.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from research_epg_common import (  # noqa: E402
    OVERRIDES_PATH,
    Candidate,
    build_name_index,
    build_token_index,
    enrich_tags,
    epgshare_exact_match,
    epgshare_fuzzy_match,
    exact_iptv_match,
    fuzzy_iptv_candidates,
    hard_reject,
    load_channels_db,
    load_existing_maps,
    lookup_epgshare_feed,
    norm_crosswalk_match,
    normalize_name,
    should_skip_channel,
    strip_quality,
    try_load_epgshare,
)
import importlib.util


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


_sup = _load_module("research_supplement_tvg_ids", SCRIPT_DIR / "research-supplement-tvg-ids.py")
_res = _load_module("resolve_empty_tvg_ids", SCRIPT_DIR / "resolve-empty-tvg-ids.py")

FastEpgCatalogSimulator = _sup.FastEpgCatalogSimulator
classify_supplement_bucket = _sup.classify_supplement_bucket
research_fast_bucket = _sup.research_fast_bucket
research_locals_bucket = _sup.research_locals_bucket
research_moveonjoy_bucket = _sup.research_moveonjoy_bucket
research_ntv_bucket = _sup.research_ntv_bucket
research_other_bucket = _sup.research_other_bucket
SupplementQueueEntry = _sup.SupplementQueueEntry

APPLY_MEDIUM_METHODS = frozenset(
    {
        "fast_catalog",
        "exact_unique_norm",
        "exact_unique_with_prog",
        "exact_country_field",
        "exact_country_prog",
        "woftv_name_plus_channels_db",
        "name_override",
        "epgshare_exact",
        "exact_iptv",
        "adult_swim_catalog",
    }
)


def should_apply_fix(fix: Fix) -> bool:
    if fix.tier == "HIGH":
        reason = fix.reason.split("_stripped")[0]
        if reason in ("exact_unique_norm", "exact_unique_with_prog", "exact_country_field", "exact_country_prog"):
            return True
        if reason.startswith("fast_catalog"):
            return True
        if reason.startswith("accnx") or reason.startswith("bein") or reason.startswith("curated"):
            return True
        # reject HIGH from fuzzy unless exact
        if "fuzzy" in reason:
            return False
        return True
    if fix.tier != "MEDIUM":
        return False
    base = fix.reason.split("_stripped")[0]
    if base not in APPLY_MEDIUM_METHODS and not base.startswith("fast_catalog"):
        return False
    name_l = fix.name.lower()
    tvg_l = fix.tvg_id.lower()
    if "bein" in name_l and "bein" not in tvg_l and "bein" not in fix.tvg_id:
        return False
    if "eurosport" in name_l and "eurosport" not in tvg_l and "sport" not in tvg_l:
        return False
    if "canal sport" in name_l and "canalplussport" in tvg_l:
        # Require country hint alignment in tvg suffix
        if "france" in name_l and not tvg_l.endswith(".fr"):
            return False
        if "poland" in name_l and not tvg_l.endswith(".pl"):
            return False
    return True

ADULT_RE = _res.ADULT_RE
EVENT_RE = _res.EVENT_RE
PEG_RE = _res.PEG_RE
Fix = _res.Fix
Skip = _res.Skip
clean_for_match = _res.clean_for_match
country_hint = _res.country_hint
fetch_playlist = _res.fetch_playlist
id_exists = _res.id_exists
kotlin_norm = _res.kotlin_norm
load_db = _res.load_db
load_playlist = _res.load_playlist
pick_exact = _res.pick_exact
strip_display = _res.strip_display

ROOT = SCRIPT_DIR.parent
REPORT_PATH = ROOT / "reports" / "supplement-empty-tvg-id-resolution.md"
JSON_REPORT = ROOT / "reports" / "supplement-empty-tvg-id-resolution.json"
PROG_IDS = Path("/tmp/epg-audit-prog-ids.txt")
BRIDGE_PATH = ROOT / "app" / "src" / "main" / "assets" / "epg_id_bridge.json"
WOFTV_US = Path("/tmp/woftv-us.json")


@dataclass
class SupplementEntry:
    name: str
    url: str
    group: str
    bucket: str


def classify_url(url: str, group: str, name: str) -> str:
    row = {"stream_url": url, "group_title": group, "display_name": name}
    return classify_supplement_bucket(row)


def load_supplement_empties(playlist: Path) -> list[SupplementEntry]:
    out: list[SupplementEntry] = []
    for row in load_playlist(playlist):
        url = row["url"]
        if "tivimate-stream" in url:
            continue
        out.append(
            SupplementEntry(
                name=row["name"],
                url=url,
                group=row["group"],
                bucket=classify_url(url, row["group"], row["name"]),
            )
        )
    return out


def load_woftv_norms() -> set[str]:
    norms: set[str] = set()
    if not WOFTV_US.is_file():
        return norms
    try:
        programs = json.loads(WOFTV_US.read_text(encoding="utf-8")).get("programs") or []
        for p in programs:
            ch = p.get("channel") or ""
            title = (p.get("title") or "").strip()
            if ch and title and title.lower() != "program information currently unavailable":
                norms.add(kotlin_norm(ch))
    except Exception as exc:
        print(f"WOFTV load failed: {exc}")
    return norms


def resolve_entry(
    entry: SupplementEntry,
    *,
    db,
    db_csv,
    by_norm,
    prog: set[str],
    bridge: set[str],
    woftv_norms: set[str],
    fast_catalog: FastEpgCatalogSimulator,
    name_index,
    token_index,
    epg_index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    mapping,
    overrides,
) -> tuple[list[Fix], Skip | None]:
    name = entry.name
    provider = entry.bucket
    bucket = entry.bucket

    if ADULT_RE.search(name) or entry.group == "XXX Adult":
        return [], Skip(name, bucket, "adult")
    if PEG_RE.search(name):
        return [], Skip(name, bucket, "peg_community")
    if EVENT_RE.search(name) or EVENT_RE.search(strip_display(name)):
        return [], Skip(name, bucket, "event_ppv_no_stable_epg")

    stripped = strip_display(name)
    cleaned = clean_for_match(name)

    # Already covered by assets
    for key in (name, stripped, cleaned):
        if key in overrides and overrides[key].strip():
            return [], Skip(name, bucket, "already_in_overrides")
        n = kotlin_norm(key)
        for ok, ov in overrides.items():
            if kotlin_norm(ok) == n and ov.strip():
                return [], Skip(name, bucket, "already_in_overrides_norm")

    fixes: list[Fix] = []

    # Bucket-specific research (FAST catalog + epgshare + iptv-org)
    qe = SupplementQueueEntry(
        channel_id=normalize_name(name)[:32] or "sup",
        group_title=entry.group,
        display_name=name,
        stream_url=entry.url,
        current_tvg_id="",
        epg_status="missing_tvg_id",
        bucket=bucket if bucket != "daddylive" else "other",
    )
    researchers = {
        "fast": research_fast_bucket,
        "ntv": research_ntv_bucket,
        "moveonjoy": research_moveonjoy_bucket,
        "locals": research_locals_bucket,
        "other": research_other_bucket,
    }
    fn = researchers.get(bucket, research_other_bucket)
    kwargs = dict(
        db=db,
        name_index=name_index,
        token_index=token_index,
        index=epg_index,
        norm_tvg_id_fn=norm_tvg_id_fn,
        loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
    )
    if fn is research_fast_bucket:
        kwargs["fast_catalog"] = fast_catalog
    row = fn(qe, **kwargs)

    if row.proposed_tvg_id and row.tier not in ("skip", "rejected", "no_match"):
        tier = "HIGH" if row.confidence >= 0.90 else "MEDIUM" if row.confidence >= 0.75 else None
        if tier and id_exists(row.proposed_tvg_id, db_csv, prog, bridge):
            fixes.append(
                Fix(
                    "name",
                    name,
                    name,
                    row.proposed_tvg_id,
                    tier,
                    row.method or row.tier,
                    bucket,
                )
            )
            if stripped != name:
                fixes.append(
                    Fix(
                        "name",
                        stripped,
                        name,
                        row.proposed_tvg_id,
                        tier,
                        (row.method or row.tier) + "_stripped",
                        bucket,
                    )
                )
            return fixes, None

    # Fallback: exact channels_db + country hint
    hint = country_hint(name) or ("US" if bucket in ("ntv", "fast", "dulo", "moveonjoy", "locals") else None)
    picked = pick_exact(name, by_norm, db_csv, prog, bridge, hint)
    if picked:
        tvg, _, reason = picked
        fixes.append(Fix("name", name, name, tvg, "HIGH", reason, bucket))
        if stripped != name:
            fixes.append(Fix("name", stripped, name, tvg, "HIGH", reason + "_stripped", bucket))
        return fixes, None

    # WOFTV name + channels_db loose US
    n = kotlin_norm(stripped)
    if n in woftv_norms:
        loose = pick_exact(cleaned, by_norm, db_csv, prog, bridge, "US")
        if loose:
            tvg, _, _ = loose
            fixes.append(
                Fix("name", stripped, name, tvg, "MEDIUM", "woftv_name_plus_channels_db", bucket)
            )
            return fixes, None
        return [], Skip(name, bucket, "woftv_name_but_no_stable_tvg_id")

    return [], Skip(name, bucket, "no_high_medium_match")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--playlist", type=Path, default=None)
    ap.add_argument("--min-tier", choices=["HIGH", "MEDIUM"], default="MEDIUM")
    args = ap.parse_args()

    playlist = args.playlist or fetch_playlist()
    entries = load_supplement_empties(playlist)
    print(f"Supplement empty tvg-id channels: {len(entries)}")

    db_csv, by_norm = load_db()
    db = load_channels_db()
    name_index = build_name_index(db)
    token_index = build_token_index(db)
    epg_index, norm_tvg_id_fn, loose_norm_tvg_id_fn = try_load_epgshare()
    print(f"epgshare: {'loaded' if epg_index else 'skipped'}")

    prog = {l.strip() for l in PROG_IDS.read_text().splitlines() if l.strip()} if PROG_IDS.is_file() else set()
    bridge_raw = json.loads(BRIDGE_PATH.read_text(encoding="utf-8")) if BRIDGE_PATH.is_file() else {}
    bridge = set((bridge_raw.get("bridge") or {}).keys())

    mapping, overrides = load_existing_maps()
    woftv_norms = load_woftv_norms()

    fast_catalog = FastEpgCatalogSimulator()
    print("Loading FAST EPG catalog...")
    n_fast = fast_catalog.refresh()
    print(f"FAST catalog: {n_fast} name mappings")

    fixes: list[Fix] = []
    skips: list[Skip] = []
    seen_keys: set[str] = set()

    bucket_counts = Counter(e.bucket for e in entries)

    for entry in entries:
        entry_fixes, skip = resolve_entry(
            entry,
            db=db,
            db_csv=db_csv,
            by_norm=by_norm,
            prog=prog,
            bridge=bridge,
            woftv_norms=woftv_norms,
            fast_catalog=fast_catalog,
            name_index=name_index,
            token_index=token_index,
            epg_index=epg_index,
            norm_tvg_id_fn=norm_tvg_id_fn,
            loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
            mapping=mapping,
            overrides=overrides,
        )
        for fix in entry_fixes:
            key = f"{fix.kind}:{fix.key}:{fix.tvg_id}"
            if key not in seen_keys:
                seen_keys.add(key)
                if not should_apply_fix(fix):
                    continue
                fixes.append(fix)
        if skip:
            skips.append(skip)

    high = [f for f in fixes if f.tier == "HIGH"]
    med = [f for f in fixes if f.tier == "MEDIUM"]
    print(f"Fixes HIGH={len(high)} MEDIUM={len(med)} unique={len(fixes)}")
    print(f"Skips={len(skips)}")

    if args.apply:
        name_fixes = {f.key: f for f in fixes if f.kind == "name"}
        changed = 0
        for key, fix in name_fixes.items():
            if overrides.get(key) != fix.tvg_id:
                overrides[key] = fix.tvg_id
                changed += 1
            stripped = strip_display(key) if key else key
            if stripped and stripped != key and overrides.get(stripped) != fix.tvg_id:
                overrides.setdefault(stripped, fix.tvg_id)
        OVERRIDES_PATH.write_text(
            json.dumps(
                dict(sorted(overrides.items(), key=lambda kv: kv[0].lower())),
                indent=2,
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"Applied {changed} new overrides → {OVERRIDES_PATH} ({len(overrides)} total)")

    skip_reasons = Counter(s.reason for s in skips)
    fix_by_bucket = Counter(f.provider for f in fixes)
    skip_by_bucket = Counter(s.provider for s in skips)

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    sample = sorted(fixes, key=lambda f: (f.tier, f.name))[:50]
    lines = [
        "# Supplement empty tvg-id resolution",
        "",
        f"**Date:** {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}",
        f"**Playlist:** `{playlist}`",
        f"**Applied:** {bool(args.apply)}",
        f"**Min tier:** {args.min_tier}",
        "",
        "## Scorecard",
        "",
        f"- **Supplement empty before:** {len(entries)}",
        f"- **Mapped HIGH:** {len(high)}",
        f"- **Mapped MEDIUM:** {len(med)}",
        f"- **Unique name fixes:** {len({f.key for f in fixes if f.kind == 'name'})}",
        f"- **Skipped:** {len(skips)}",
        "",
        "### Empty by bucket (before)",
        "",
    ]
    for k, v in bucket_counts.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "### Fixes by bucket", ""]
    for k, v in fix_by_bucket.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "### Skips by bucket", ""]
    for k, v in skip_by_bucket.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "### Skip reasons", ""]
    for k, v in skip_reasons.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += [
        "",
        "## Sample mappings",
        "",
        "| Tier | Bucket | Name | tvg-id | Reason |",
        "|------|--------|------|--------|--------|",
    ]
    for f in sample:
        lines.append(
            f"| {f.tier} | {f.provider} | {f.name[:52]} | `{f.tvg_id}` | {f.reason} |"
        )
    lines += ["", "## Skipped (sample)", ""]
    for s in skips[:60]:
        lines.append(f"- [{s.provider}] {s.name[:70]} — {s.reason}")
    if len(skips) > 60:
        lines.append(f"- … +{len(skips) - 60} more")
    lines += [
        "",
        "## Notes",
        "",
        "- Supplement channels use **epg_name_overrides.json** (display-name keyed).",
        "- FAST channels prefer mjh.nz catalog ids when provider tag matches.",
        "- Event/PPV/adult/PEG rows skipped (no invent).",
        "- Reload gateway / refresh EPG on device for guide lift.",
        "",
    ]
    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    JSON_REPORT.write_text(
        json.dumps(
            {
                "supplement_empty_before": len(entries),
                "high": len(high),
                "medium": len(med),
                "skips": len(skips),
                "bucket_counts": dict(bucket_counts),
                "fixes": [asdict(f) for f in fixes],
                "skips_list": [asdict(s) for s in skips],
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {REPORT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

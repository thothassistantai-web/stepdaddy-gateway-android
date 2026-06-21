#!/usr/bin/env python3
"""Merge high-confidence EPG repair CSV rows into bundled epg_name_overrides.json."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent
ASSETS = ANDROID_ROOT / "app" / "src" / "main" / "assets"
OVERRIDES_PATH = ASSETS / "epg_name_overrides.json"
DEFAULT_CSV = Path.home() / "Desktop" / "fixed_epg_mappings_high_confidence.csv"


def clean_display_name(name: str) -> str:
    s = name.strip()
    for sep in ("🇺🇸", "🇬🇧", "🇨🇦", "📡", "🎬"):
        if sep in s:
            s = s.split(sep, 1)[0].strip()
    s = re.sub(
        r"\s+(US|UK|CA)\s+(Samsung|Pluto|Distro|Xumo|Roku|Plex|STIRR|Tubi|FireTV|Local).*$",
        "",
        s,
        flags=re.I,
    )
    return s.strip()


def norm_key(name: str) -> str:
    s = name.lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def epg_source_matches_provider(epg_source: str, provider: str) -> bool:
    """True when mjh-{provider} style epg_source aligns with the CSV provider column."""
    es = (epg_source or "").strip().lower()
    prov = (provider or "").strip().lower()
    if not es or not prov or prov == "unknown":
        return False
    if not es.startswith("mjh-"):
        return False
    return es[len("mjh-") :] == prov


def row_should_merge(row: dict[str, str], *, include_provider_medium: bool) -> bool:
    confidence = (row.get("confidence") or "").strip().lower()
    if confidence == "high":
        return True
    if include_provider_medium and confidence == "medium":
        return epg_source_matches_provider(
            row.get("epg_source") or "",
            row.get("provider") or "",
        )
    return False


def merge_row(
    row: dict[str, str],
    existing: dict[str, str],
    norm_to_key: dict[str, str],
) -> str:
    """Apply one CSV row; return 'added', 'updated', or 'skipped'."""
    proposed = (row.get("proposed_tvg_id") or "").strip()
    if not proposed:
        return "skipped"
    label = clean_display_name(row.get("display_name") or "")
    if not label:
        return "skipped"
    nk = norm_key(label)
    if nk in norm_to_key:
        key = norm_to_key[nk]
        if existing.get(key) == proposed:
            return "skipped"
        existing[key] = proposed
        return "updated"
    existing[label] = proposed
    norm_to_key[nk] = label
    return "added"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge EPG repair CSV rows into epg_name_overrides.json",
    )
    parser.add_argument(
        "csv_path",
        nargs="?",
        type=Path,
        default=DEFAULT_CSV,
        help=f"Repair mappings CSV (default: {DEFAULT_CSV})",
    )
    parser.add_argument(
        "--include-provider-medium",
        action="store_true",
        help=(
            "Also merge medium-confidence rows when provider matches the "
            "epg_source mjh- prefix (e.g. mjh-samsung + Samsung)"
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    csv_path = args.csv_path
    if not csv_path.is_file():
        print(f"CSV not found: {csv_path}", file=sys.stderr)
        return 1

    import csv

    existing: dict[str, str] = {}
    if OVERRIDES_PATH.is_file():
        existing = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))

    norm_to_key: dict[str, str] = {norm_key(k): k for k in existing}
    added = updated = skipped = 0

    with csv_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if not row_should_merge(row, include_provider_medium=args.include_provider_medium):
                continue
            outcome = merge_row(row, existing, norm_to_key)
            if outcome == "added":
                added += 1
            elif outcome == "updated":
                updated += 1
            else:
                skipped += 1

    OVERRIDES_PATH.write_text(
        json.dumps(dict(sorted(existing.items(), key=lambda kv: kv[0].lower())), indent=2, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {OVERRIDES_PATH}")
    print(f"  added={added} updated={updated} skipped={skipped} total={len(existing)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

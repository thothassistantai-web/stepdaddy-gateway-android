#!/usr/bin/env python3
"""Merge high-confidence EPG repair CSV rows into bundled epg_name_overrides.json."""

from __future__ import annotations

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


def main() -> int:
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CSV
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
            if row.get("confidence") != "high":
                continue
            proposed = (row.get("proposed_tvg_id") or "").strip()
            if not proposed:
                continue
            label = clean_display_name(row.get("display_name") or "")
            if not label:
                continue
            nk = norm_key(label)
            if nk in norm_to_key:
                key = norm_to_key[nk]
                if existing.get(key) == proposed:
                    skipped += 1
                    continue
                existing[key] = proposed
                updated += 1
            else:
                existing[label] = proposed
                norm_to_key[nk] = label
                added += 1

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

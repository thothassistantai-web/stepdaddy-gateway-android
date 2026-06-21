#!/usr/bin/env python3
"""Research proposed tvg-id mappings for unmapped DaddyLive channels."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from research_epg_common import (  # noqa: E402
    OVERRIDES_PATH,
    REPORTS_DIR,
    ROOT,
    QueueEntry,
    ResearchRow,
    apply_high_confidence,
    build_name_index,
    build_token_index,
    is_already_mapped,
    load_channels_db,
    load_dlhd,
    load_existing_maps,
    research_channel,
    resolve_dlhd_cache,
    try_load_epgshare,
    write_csv,
    write_json,
    UNMAPPED_CANDIDATES,
    _load_gaps_queue,
)


def load_unmapped_queue(
    *,
    prefer_gaps: bool = True,
    daddylive_only: bool = False,
) -> tuple[list[str], dict[str, str], dict[str, QueueEntry]]:
    """Return channel ids, group_title by id, and full queue entries."""
    group_by_id: dict[str, str] = {}
    by_id: dict[str, QueueEntry] = {}

    def ingest(entries: list[QueueEntry]) -> tuple[list[str], dict[str, str], dict[str, QueueEntry]]:
        ids: list[str] = []
        for entry in entries:
            ids.append(entry.channel_id)
            if entry.group_title:
                group_by_id[entry.channel_id] = entry.group_title
            by_id[entry.channel_id] = entry
        return ids, group_by_id, by_id

    if prefer_gaps:
        gap_entries = _load_gaps_queue(daddylive_only=daddylive_only)
        if gap_entries:
            return ingest(gap_entries)

    for path in UNMAPPED_CANDIDATES:
        if not path.is_file() or path.stat().st_size < 10:
            continue
        entries: list[QueueEntry] = []
        import csv

        with path.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                cid = (row.get("chno") or row.get("channel_id") or "").strip()
                if not cid:
                    continue
                entries.append(
                    QueueEntry(
                        channel_id=cid,
                        group_title=(row.get("group_title") or "").strip(),
                        display_name=(row.get("display_name") or row.get("channel_name") or "").strip(),
                    )
                )
        if entries:
            return ingest(entries)

    gap_entries = _load_gaps_queue(daddylive_only=daddylive_only)
    if gap_entries:
        return ingest(gap_entries)

    return [], group_by_id, by_id


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Research tvg-id mappings for DaddyLive channels")
    parser.add_argument("--only-unmapped", action="store_true", help="Restrict to desktop unmapped queue")
    parser.add_argument(
        "--apply-high",
        action="store_true",
        help="Merge proposals with confidence >= 0.90 into epg_name_overrides.json",
    )
    parser.add_argument("--min-review", type=float, default=0.65, help="Min confidence for review CSV")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=REPORTS_DIR / "daddylive_epg_research.json",
        help="JSON research report path",
    )
    parser.add_argument(
        "--csv",
        type=Path,
        default=REPORTS_DIR / "daddylive_epg_review.csv",
        help="CSV review export path",
    )
    parser.add_argument("--limit", type=int, default=None, help="Process at most N channels (smoke test)")
    parser.add_argument(
        "--daddylive-gaps-only",
        action="store_true",
        help="When using gaps CSV, only DaddyLive relay channels (tivimate-stream URLs)",
    )
    parser.add_argument(
        "--import-research",
        action="store_true",
        help="Also write high-confidence rows to daddylive_epg_research.json asset",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    dlhd = load_dlhd()
    mapping, overrides = load_existing_maps()
    print("Loading iptv-org index...", file=sys.stderr)
    db = load_channels_db()
    name_index = build_name_index(db)
    token_index = build_token_index(db)
    print("Loading epgshare index...", file=sys.stderr)
    index, norm_tvg_id_fn, loose_norm_tvg_id_fn = try_load_epgshare()
    epgshare_status = "loaded" if index is not None else "skipped"

    queue_ids, group_by_id, queue_by_id = load_unmapped_queue(
        prefer_gaps=True,
        daddylive_only=args.daddylive_gaps_only,
    )

    if args.only_unmapped:
        if not queue_ids:
            print("No unmapped queue CSV found on Desktop", file=sys.stderr)
            return 1
        target_ids = queue_ids
    else:
        target_ids = sorted(dlhd.keys(), key=lambda x: int(x) if x.isdigit() else 0)

    rows: list[ResearchRow] = []
    for cid in target_ids:
        if args.limit is not None and len(rows) >= args.limit:
            break
        ch = dlhd.get(cid)
        queue_entry = queue_by_id.get(cid)
        if ch:
            name = (ch.get("channel_name") or ch.get("name") or "").strip()
        elif queue_entry and queue_entry.display_name:
            name = queue_entry.display_name
            ch = {"id": cid, "channel_id": cid, "name": name, "tags": []}
        else:
            continue
        if is_already_mapped(cid, name, mapping, overrides):
            continue
        group_title = group_by_id.get(cid, "") or (queue_entry.group_title if queue_entry else "")
        rows.append(
            research_channel(
                cid,
                ch,
                group_title,
                db,
                name_index,
                token_index,
                index,
                norm_tvg_id_fn,
                loose_norm_tvg_id_fn,
            )
        )

    tier_counts: dict[str, int] = {}
    for row in rows:
        tier_counts[row.tier] = tier_counts.get(row.tier, 0) + 1

    meta = {
        "dlhd_cache": str(resolve_dlhd_cache()),
        "epgshare": epgshare_status,
        "only_unmapped": args.only_unmapped,
        "processed": len(rows),
        "tier_counts": dict(sorted(tier_counts.items())),
    }

    write_json(args.output, rows, meta)
    write_csv(args.csv, rows, args.min_review)

    print(f"Wrote {args.output} ({len(rows)} channels)")
    print(f"Wrote {args.csv}")
    print(f"epgshare tier: {epgshare_status}")
    print("tier counts:")
    for tier, count in sorted(tier_counts.items()):
        print(f"  {tier}: {count}")

    if args.apply_high:
        n = apply_high_confidence(rows)
        print(f"Applied {n} high-confidence overrides to {OVERRIDES_PATH}")

    if args.import_research or args.apply_high:
        import_script = ROOT / "scripts" / "import-daddylive-research.py"
        if import_script.is_file():
            rc = subprocess.call(
                [
                    sys.executable,
                    str(import_script),
                    str(args.output),
                    "--min-confidence",
                    "0.90",
                ],
            )
            if rc != 0:
                print("import-daddylive-research.py failed", file=sys.stderr)
                return rc

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

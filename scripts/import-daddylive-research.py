#!/usr/bin/env python3
"""Convert research-daddylive-tvg-ids.py JSON output into daddylive_epg_research.json asset format."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / "reports" / "daddylive_epg_research.json"
DEFAULT_OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "daddylive_epg_research.json"


def row_to_mapping(row: dict) -> tuple[str, dict] | None:
    channel_id = str(row.get("channel_id") or "").strip()
    tvg_id = str(row.get("proposed_tvg_id") or "").strip()
    if not channel_id or not tvg_id:
        return None
    confidence = float(row.get("confidence") or 0.0)
    method = str(row.get("method") or row.get("tier") or "").strip()
    channel_name = str(row.get("channel_name") or "").strip()
    entry = {
        "tvg_id": tvg_id,
        "confidence": round(confidence, 3),
        "method": method,
    }
    if channel_name:
        entry["channel_name"] = channel_name
    return channel_id, entry


def convert_report(
    payload: dict,
    *,
    min_confidence: float,
    include_tiers: set[str],
) -> dict:
    channels = payload.get("channels")
    if not isinstance(channels, list):
        raise ValueError("Input JSON must contain a 'channels' array")

    mappings: dict[str, dict] = {}
    for row in channels:
        if not isinstance(row, dict):
            continue
        tier = str(row.get("tier") or "").strip().lower()
        if tier in ("skip", "rejected", "no_match"):
            continue
        if include_tiers and tier not in include_tiers:
            continue
        confidence = float(row.get("confidence") or 0.0)
        if confidence < min_confidence:
            continue
        mapped = row_to_mapping(row)
        if mapped is None:
            continue
        channel_id, entry = mapped
        prev = mappings.get(channel_id)
        if prev is None or entry["confidence"] >= prev["confidence"]:
            mappings[channel_id] = entry

    return {
        "version": 1,
        "mappings": dict(sorted(mappings.items(), key=lambda kv: int(kv[0]) if kv[0].isdigit() else kv[0])),
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Import research-daddylive-tvg-ids.py output into bundled research asset JSON",
    )
    parser.add_argument(
        "input",
        nargs="?",
        type=Path,
        default=DEFAULT_INPUT,
        help=f"Research report JSON (default: {DEFAULT_INPUT})",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Asset output path (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--min-confidence",
        type=float,
        default=0.90,
        help="Minimum confidence to include a mapping (default: 0.90)",
    )
    parser.add_argument(
        "--tier",
        action="append",
        default=[],
        help="Only include rows with this tier (repeatable; default: all accepted tiers)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if not args.input.is_file():
        print(f"Input not found: {args.input}", file=sys.stderr)
        return 1

    payload = json.loads(args.input.read_text(encoding="utf-8"))
    include_tiers = {t.strip().lower() for t in args.tier if t.strip()}
    asset = convert_report(
        payload,
        min_confidence=args.min_confidence,
        include_tiers=include_tiers,
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(asset, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {args.output} ({len(asset['mappings'])} mappings)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Extract channel name → epg_channel_id crosswalk from an Xtream Codes panel.

Credentials via environment (never commit):
  XTREAM_BASE_URL   e.g. http://cf.ccotho.xyz
  XTREAM_USERNAME
  XTREAM_PASSWORD

Usage:
  XTREAM_BASE_URL=http://example.com XTREAM_USERNAME=u XTREAM_PASSWORD=p \\
    python3 scripts/fetch-xtream-epg-crosswalk.py > reports/xtream_epg_crosswalk.json

Optional:
  --filter REGEX     Only stream names matching REGEX
  --with-xmltv       Also verify ids appear in panel xmltv.php programme data
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request

UA = "StepDaddy/1.0 (epg-crosswalk)"


def fetch_json(base: str, user: str, password: str, action: str | None = None) -> object:
    params = {"username": user, "password": password}
    if action:
        params["action"] = action
    url = f"{base.rstrip('/')}/player_api.php?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def fetch_xmltv(base: str, user: str, password: str) -> str:
    url = (
        f"{base.rstrip('/')}/xmltv.php?"
        + urllib.parse.urlencode({"username": user, "password": password})
    )
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read().decode("utf-8", errors="replace")


def programme_counts(xmltv: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for match in re.finditer(r'<programme[^>]+channel="([^"]+)"', xmltv):
        cid = match.group(1)
        counts[cid] = counts.get(cid, 0) + 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description="Xtream Codes EPG crosswalk exporter")
    parser.add_argument("--filter", default="", help="Regex filter on stream name")
    parser.add_argument("--with-xmltv", action="store_true", help="Count xmltv programmes per id")
    parser.add_argument("-o", "--output", help="Write JSON file (default stdout)")
    args = parser.parse_args()

    base = os.environ.get("XTREAM_BASE_URL", "").strip()
    user = os.environ.get("XTREAM_USERNAME", "").strip()
    password = os.environ.get("XTREAM_PASSWORD", "").strip()
    if not base or not user or not password:
        print("Set XTREAM_BASE_URL, XTREAM_USERNAME, XTREAM_PASSWORD", file=sys.stderr)
        return 1

    name_filter = re.compile(args.filter, re.I) if args.filter else None
    streams = fetch_json(base, user, password, "get_live_streams")
    if not isinstance(streams, list):
        print("Unexpected API response", file=sys.stderr)
        return 1

    prog_counts: dict[str, int] = {}
    if args.with_xmltv:
        prog_counts = programme_counts(fetch_xmltv(base, user, password))

    entries = []
    for stream in streams:
        name = (stream.get("name") or "").strip()
        epg_id = (stream.get("epg_channel_id") or "").strip()
        if not name or not epg_id:
            continue
        if name_filter and not name_filter.search(name):
            continue
        entries.append(
            {
                "stream_id": stream.get("stream_id"),
                "name": name,
                "tvg_id": epg_id,
                "category_id": stream.get("category_id"),
                "xmltv_programmes": prog_counts.get(epg_id, 0),
            }
        )

    entries.sort(key=lambda e: e["name"].lower())
    payload = {
        "source": base,
        "mapped_streams": len(entries),
        "total_streams": len(streams),
        "entries": entries,
    }

    text = json.dumps(payload, indent=2, ensure_ascii=False)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"Wrote {len(entries)} entries to {args.output}", file=sys.stderr)
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

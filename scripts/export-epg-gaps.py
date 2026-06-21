#!/usr/bin/env python3
"""Export playlist channels with placeholder or missing EPG to CSV."""
from __future__ import annotations

import csv
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PLACEHOLDER_TITLE = "Live programming"
DEFAULT_BASE = "http://127.0.0.1:3000"


@dataclass
class ChannelRow:
    chno: str
    display_name: str
    tvg_id: str
    group_title: str
    stream_url: str
    epg_status: str
    programme_count: int
    sample_title: str


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=120) as resp:
        return resp.read().decode("utf-8", errors="replace")


def parse_playlist(text: str) -> list[dict]:
    channels: list[dict] = []
    extinf_re = re.compile(r"^#EXTINF:-1\s+(.*)$")
    attr_re = re.compile(r'([\w-]+)="([^"]*)"')
    pending: dict | None = None

    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = extinf_re.match(line)
        if m:
            attrs = dict(attr_re.findall(m.group(1)))
            title_part = m.group(1).split(",")[-1].strip()
            pending = {
                "chno": attrs.get("tvg-chno", ""),
                "tvg_id": attrs.get("tvg-id", "").strip(),
                "tvg_name": attrs.get("tvg-name", "").strip(),
                "group_title": attrs.get("group-title", "").strip(),
                "display_name": title_part or attrs.get("tvg-name", ""),
            }
            continue
        if pending and not line.startswith("#"):
            pending["stream_url"] = line
            channels.append(pending)
            pending = None
    return channels


def parse_epg_programmes(path: Path) -> dict[str, dict]:
    """Stream-parse EPG; return per channel programme stats."""
    stats: dict[str, dict] = {}
    context = ET.iterparse(path, events=("end",))
    for event, elem in context:
        if elem.tag != "programme":
            continue
        channel_id = elem.attrib.get("channel", "").strip()
        if not channel_id:
            elem.clear()
            continue
        title = ""
        for child in elem:
            if child.tag == "title" and (child.text or "").strip():
                title = (child.text or "").strip()
                break
        entry = stats.setdefault(
            channel_id,
            {"count": 0, "real": 0, "placeholder": 0, "sample_real": ""},
        )
        entry["count"] += 1
        if title == PLACEHOLDER_TITLE:
            entry["placeholder"] += 1
        elif title:
            entry["real"] += 1
            if not entry["sample_real"]:
                entry["sample_real"] = title
        elem.clear()
    return stats


def classify(tvg_id: str, epg: dict[str, dict]) -> tuple[str, int, str]:
    if not tvg_id:
        return "no_information", 0, ""
    info = epg.get(tvg_id)
    if not info or info["count"] == 0:
        return "no_information", 0, ""
    if info["real"] == 0:
        return "placeholder", info["count"], PLACEHOLDER_TITLE
    return "ok", info["count"], info["sample_real"]


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path.home() / "Desktop" / "stepdaddy-epg-gaps.csv"
    epg_tmp = Path("/tmp/stepdaddy-epg-export.xml")

    print(f"Fetching playlist from {base}...")
    playlist = fetch(f"{base.rstrip('/')}/tivimate-playlist.m3u8")
    channels = parse_playlist(playlist)
    print(f"Playlist channels: {len(channels)}")

    print(f"Downloading EPG to {epg_tmp}...")
    with urllib.request.urlopen(f"{base.rstrip('/')}/epg.xml", timeout=300) as resp:
        epg_tmp.write_bytes(resp.read())

    print("Parsing EPG programmes...")
    epg_stats = parse_epg_programmes(epg_tmp)
    print(f"EPG channels with programmes: {len(epg_stats)}")

    rows: list[ChannelRow] = []
    for ch in channels:
        status, count, sample = classify(ch["tvg_id"], epg_stats)
        if status == "ok":
            continue
        rows.append(
            ChannelRow(
                chno=ch["chno"],
                display_name=ch["display_name"],
                tvg_id=ch["tvg_id"],
                group_title=ch["group_title"],
                stream_url=ch.get("stream_url", ""),
                epg_status=status,
                programme_count=count,
                sample_title=sample,
            )
        )

    rows.sort(key=lambda r: (r.epg_status, r.group_title.lower(), int(r.chno) if r.chno.isdigit() else 99999))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            [
                "chno",
                "display_name",
                "tvg_id",
                "group_title",
                "epg_status",
                "programme_count",
                "sample_title",
                "stream_url",
            ]
        )
        for r in rows:
            w.writerow(
                [
                    r.chno,
                    r.display_name,
                    r.tvg_id,
                    r.group_title,
                    r.epg_status,
                    r.programme_count,
                    r.sample_title,
                    r.stream_url,
                ]
            )

    no_info = sum(1 for r in rows if r.epg_status == "no_information")
    placeholder = sum(1 for r in rows if r.epg_status == "placeholder")
    print(f"Wrote {len(rows)} rows -> {out_path}")
    print(f"  no_information: {no_info}")
    print(f"  placeholder:    {placeholder}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

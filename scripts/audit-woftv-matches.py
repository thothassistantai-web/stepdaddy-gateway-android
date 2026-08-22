#!/usr/bin/env python3
"""Find playlist channels with closer WOFTV name matches and potential tvg-id mismatches."""
from __future__ import annotations

import csv
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
CHANNELS_DB = ASSETS / "channels_db_cache.csv"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
PLACEHOLDER_TITLE = "Live programming"
WOFTV_PLACEHOLDER = "program information currently unavailable"
DEFAULT_BASE = "http://127.0.0.1:13000"
WOFTV_US = Path("/tmp/woftv-us.json")
WOFTV_CA = Path("/tmp/woftv-ca.json")


def normalize_name(name: str) -> str:
    s = name.lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def tokens(name: str) -> set[str]:
    stop = {"plus", "and", "the", "a"}
    return {t for t in normalize_name(name).split() if t and t not in stop}


def fetch(url: str, timeout: int = 300) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "StepDaddy-audit/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


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


def parse_epg_stats(path: Path) -> dict[str, dict]:
    stats: dict[str, dict] = {}
    for _, elem in ET.iterparse(path, events=("end",)):
        if elem.tag != "programme":
            continue
        cid = elem.attrib.get("channel", "").strip()
        if not cid:
            elem.clear()
            continue
        title = ""
        for child in elem:
            if child.tag == "title" and (child.text or "").strip():
                title = (child.text or "").strip()
                break
        entry = stats.setdefault(cid, {"count": 0, "real": 0, "placeholder": 0, "sample_real": ""})
        entry["count"] += 1
        if title == PLACEHOLDER_TITLE:
            entry["placeholder"] += 1
        elif title:
            entry["real"] += 1
            if not entry["sample_real"]:
                entry["sample_real"] = title
        elem.clear()
    return stats


@dataclass
class WoftvKey:
    key: str
    raw_names: set[str] = field(default_factory=set)
    platforms: set[str] = field(default_factory=set)
    sample_titles: list[str] = field(default_factory=list)
    programme_count: int = 0


def load_woftv_index(*paths: Path) -> dict[str, WoftvKey]:
    index: dict[str, WoftvKey] = {}
    for path in paths:
        if not path.is_file():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        for row in data.get("programs", []):
            title = (row.get("title") or "").strip()
            if WOFTV_PLACEHOLDER in title.lower():
                continue
            raw = (row.get("channel") or "").strip()
            if not raw:
                continue
            key = normalize_name(raw)
            if not key:
                continue
            entry = index.setdefault(key, WoftvKey(key=key))
            entry.raw_names.add(raw)
            platform = (row.get("platform") or "").strip()
            if platform:
                entry.platforms.add(platform)
            entry.programme_count += 1
            if len(entry.sample_titles) < 3 and title:
                entry.sample_titles.append(title)
    return index


def woftv_lookup(index: dict[str, WoftvKey], display_name: str) -> tuple[str, WoftvKey | None]:
    norm = normalize_name(display_name)
    if not norm:
        return "none", None
    hit = index.get(norm)
    if hit:
        return "exact", hit
    for key, entry in index.items():
        if len(key) >= 4 and (norm.find(key) >= 0 or key.find(norm) >= 0):
            return "fuzzy", entry
    return "none", None


def build_token_index(index: dict[str, WoftvKey]) -> dict[str, set[str]]:
    inv: dict[str, set[str]] = defaultdict(set)
    for key in index:
        for tok in key.split():
            if len(tok) >= 3:
                inv[tok].add(key)
    return inv


def best_woftv_candidates(
    index: dict[str, WoftvKey],
    display_name: str,
    token_index: dict[str, set[str]] | None = None,
    top_n: int = 3,
) -> list[tuple[float, str, WoftvKey]]:
    norm = normalize_name(display_name)
    dtoks = tokens(display_name)
    if not dtoks:
        return []
    key_pool: set[str]
    if token_index:
        pool: set[str] = set()
        for tok in dtoks:
            pool.update(token_index.get(tok, ()))
        if not pool:
            return []
        key_pool = pool
    else:
        key_pool = set(index.keys())
    scored: list[tuple[float, str, WoftvKey]] = []
    for key in key_pool:
        entry = index[key]
        if len(key) < 3:
            continue
        kt = set(key.split())
        jacc = len(dtoks & kt) / len(dtoks | kt)
        if jacc < 0.25:
            continue
        seq = SequenceMatcher(None, norm, key).ratio()
        raw_bonus = max(
            (SequenceMatcher(None, display_name.lower(), raw.lower()).ratio() for raw in entry.raw_names),
            default=0.0,
        )
        score = 0.45 * jacc + 0.35 * seq + 0.20 * raw_bonus
        if score >= 0.35:
            scored.append((score, key, entry))
    scored.sort(key=lambda x: (-x[0], x[1]))
    return scored[:top_n]


@dataclass
class ChannelMeta:
    tvg_id: str
    name: str
    country: str
    alt_names: str


def load_channels_db() -> tuple[dict[str, ChannelMeta], dict[str, list[str]]]:
    by_id: dict[str, ChannelMeta] = {}
    by_norm: dict[str, list[str]] = defaultdict(list)
    with CHANNELS_DB.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            cid = (row.get("id") or "").strip()
            if not cid:
                continue
            meta = ChannelMeta(
                tvg_id=cid,
                name=(row.get("name") or "").strip(),
                country=(row.get("country") or "").strip().upper(),
                alt_names=(row.get("alt_names") or "").strip(),
            )
            by_id[cid] = meta
            for label in [meta.name, *meta.alt_names.split(";")]:
                label = label.strip()
                if label:
                    by_norm[normalize_name(label)].append(cid)
    return by_id, by_norm


def build_db_token_index(by_norm: dict[str, list[str]]) -> dict[str, set[str]]:
    inv: dict[str, set[str]] = defaultdict(set)
    for nkey in by_norm:
        for tok in nkey.split():
            if len(tok) >= 3:
                inv[tok].add(nkey)
    return inv


def db_lookup(
    by_norm: dict[str, list[str]],
    display_name: str,
    token_index: dict[str, set[str]] | None = None,
) -> list[str]:
    norm = normalize_name(display_name)
    hits = list(dict.fromkeys(by_norm.get(norm, [])))
    if hits:
        return hits
    dtoks = tokens(display_name)
    if len(dtoks) < 2:
        return []
    key_pool: set[str] = set()
    if token_index:
        for tok in dtoks:
            key_pool.update(token_index.get(tok, ()))
    else:
        key_pool = set(by_norm.keys())
    scored: list[tuple[float, str]] = []
    for nkey in key_pool:
        kt = set(nkey.split())
        jacc = len(dtoks & kt) / len(dtoks | kt)
        if jacc >= 0.55:
            for cid in by_norm[nkey]:
                scored.append((jacc, cid))
    scored.sort(key=lambda x: -x[0])
    return list(dict.fromkeys(cid for _, cid in scored[:5]))


def epg_status(tvg_id: str, epg: dict[str, dict]) -> str:
    if not tvg_id:
        return "no_tvg_id"
    info = epg.get(tvg_id)
    if not info or info["count"] == 0:
        return "no_information"
    if info["real"] == 0:
        return "placeholder"
    return "real"


def provider_hint(group: str, url: str) -> str:
    g = group.lower()
    u = url.lower()
    for tag in ("pluto", "plex", "roku", "tubi", "xumo", "samsung", "stirr", "freevee", "amazon"):
        if tag in g or tag in u:
            return tag
    return ""


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE
    out_csv = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "reports" / "woftv-match-audit.csv"
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    playlist_path = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    epg_path = Path(sys.argv[4]) if len(sys.argv) > 4 else None

    if not WOFTV_US.is_file():
        print("Downloading WOFTV US JSON...")
        WOFTV_US.write_bytes(fetch("https://cdn.jsdelivr.net/gh/whatsonfreetv/whatsonfreetv-data@main/epg-us.json"))
    if not WOFTV_CA.is_file():
        print("Downloading WOFTV CA JSON...")
        try:
            WOFTV_CA.write_bytes(fetch("https://cdn.jsdelivr.net/gh/whatsonfreetv/whatsonfreetv-data@main/epg-ca.json"))
        except Exception:
            pass

    if playlist_path and playlist_path.is_file():
        print(f"Reading playlist from {playlist_path}...")
        playlist = playlist_path.read_text(encoding="utf-8", errors="replace")
    else:
        print(f"Fetching playlist from {base}...")
        playlist = fetch(f"{base.rstrip('/')}/tivimate-playlist.m3u8", timeout=240).decode("utf-8", errors="replace")
    channels = parse_playlist(playlist)
    print(f"Channels: {len(channels)}")

    epg_tmp = epg_path if epg_path and epg_path.is_file() else Path("/tmp/stepdaddy-epg-audit.xml")
    if epg_path and epg_path.is_file():
        print(f"Reading EPG from {epg_path}...")
    elif not epg_tmp.is_file():
        print("Downloading EPG...")
        epg_tmp.write_bytes(fetch(f"{base.rstrip('/')}/epg.xml", timeout=300))
    epg_stats = parse_epg_stats(epg_tmp)

    woftv_index = load_woftv_index(WOFTV_US, WOFTV_CA)
    print(f"WOFTV keys: {len(woftv_index)}")
    woftv_token_index = build_token_index(woftv_index)

    db_by_id, db_by_norm = load_channels_db()
    db_token_index = build_db_token_index(db_by_norm)
    id_map = {}
    if MAPPING_PATH.is_file():
        id_map = json.loads(MAPPING_PATH.read_text(encoding="utf-8"))

    rows: list[dict] = []
    stats = defaultdict(int)

    for ch in channels:
        name = ch["display_name"]
        tvg_id = ch["tvg_id"]
        status = epg_status(tvg_id, epg_stats)
        match_kind, woftv_hit = woftv_lookup(woftv_index, name)
        candidates: list[tuple[float, str, WoftvKey]] = []
        if match_kind == "none" or status in ("placeholder", "no_information", "no_tvg_id"):
            candidates = best_woftv_candidates(woftv_index, name, woftv_token_index)
        db_ids: list[str] = []
        if status != "real" or match_kind == "none":
            db_ids = db_lookup(db_by_norm, name, db_token_index)
        db_primary = db_ids[0] if db_ids else ""
        provider = provider_hint(ch["group_title"], ch.get("stream_url", ""))

        best_score, best_key, best_entry = candidates[0] if candidates else (0.0, "", None)
        current_db_name = db_by_id[tvg_id].name if tvg_id in db_by_id else ""

        issues: list[str] = []
        if match_kind == "none" and best_score >= 0.55:
            issues.append("closer_woftv_not_matched")
        if match_kind == "fuzzy" and best_key and woftv_hit and best_key != woftv_hit.key and best_score >= 0.65:
            issues.append("fuzzy_suboptimal")
        if status in ("placeholder", "no_information", "no_tvg_id") and (match_kind != "none" or best_score >= 0.55):
            issues.append("woftv_available_guide_gap")
        if db_primary and tvg_id and db_primary != tvg_id and status != "real":
            issues.append("channels_db_disagrees")
        if db_primary and tvg_id and db_primary != tvg_id and best_score >= 0.5:
            issues.append("tvgid_mismatch_with_woftv_evidence")
        if provider and match_kind == "none" and best_score >= 0.45:
            issues.append("fast_provider_woftv_candidate")
        if tvg_id and status == "real" and match_kind != "none":
            cur_toks = tokens(current_db_name or name)
            w_toks = tokens(next(iter(woftv_hit.raw_names)) if woftv_hit else "")
            if cur_toks and w_toks and len(cur_toks & w_toks) / len(cur_toks | w_toks) < 0.25:
                issues.append("real_epg_name_drift")

        if not issues:
            continue

        for issue in issues:
            stats[issue] += 1

        woftv_raw = ""
        woftv_platform = ""
        woftv_sample = ""
        if woftv_hit:
            woftv_raw = "; ".join(sorted(woftv_hit.raw_names)[:3])
            woftv_platform = "; ".join(sorted(woftv_hit.platforms)[:3])
            woftv_sample = woftv_hit.sample_titles[0] if woftv_hit.sample_titles else ""
        elif best_entry:
            woftv_raw = "; ".join(sorted(best_entry.raw_names)[:3])
            woftv_platform = "; ".join(sorted(best_entry.platforms)[:3])
            woftv_sample = best_entry.sample_titles[0] if best_entry.sample_titles else ""

        epg_info = epg_stats.get(tvg_id, {})
        rows.append(
            {
                "issue_flags": "|".join(issues),
                "chno": ch["chno"],
                "display_name": name,
                "group_title": ch["group_title"],
                "provider_hint": provider,
                "current_tvg_id": tvg_id,
                "current_tvg_db_name": current_db_name,
                "epg_status": status,
                "epg_real_count": epg_info.get("real", 0),
                "epg_sample": epg_info.get("sample_real", ""),
                "woftv_match": match_kind,
                "woftv_key": woftv_hit.key if woftv_hit else best_key,
                "woftv_raw_names": woftv_raw,
                "woftv_platform": woftv_platform,
                "woftv_sample_title": woftv_sample,
                "best_woftv_score": f"{best_score:.2f}",
                "channels_db_suggest": db_primary,
                "channels_db_alts": ";".join(db_ids[1:4]),
                "norm_display": normalize_name(name),
            }
        )

    rows.sort(key=lambda r: (-len(r["issue_flags"].split("|")), -float(r["best_woftv_score"]), r["display_name"]))

    fields = list(rows[0].keys()) if rows else []
    with out_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    print(f"\nWrote {len(rows)} flagged channels -> {out_csv}")
    print("\nIssue counts:")
    for k, v in sorted(stats.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")

    print("\nTop 25 actionable (guide gap + WOFTV evidence):")
    shown = 0
    for r in rows:
        if "woftv_available_guide_gap" not in r["issue_flags"]:
            continue
        print(
            f"  [{r['chno']}] {r['display_name']} | tvg={r['current_tvg_id']} ({r['epg_status']}) "
            f"| woftv={r['woftv_match']}:{r['woftv_key']} ({r['woftv_platform']}) "
            f"| db={r['channels_db_suggest']} | {r['issue_flags']}"
        )
        shown += 1
        if shown >= 25:
            break

    print("\nTop 15 tvg-id mismatches (channels_db + WOFTV):")
    shown = 0
    for r in rows:
        if "tvgid_mismatch_with_woftv_evidence" not in r["issue_flags"]:
            continue
        print(
            f"  {r['display_name']} | current={r['current_tvg_id']} suggest={r['channels_db_suggest']} "
            f"| woftv={r['woftv_raw_names']} | epg={r['epg_status']}"
        )
        shown += 1
        if shown >= 15:
            break

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Generate tvtv_id_bridge.json: Stalker/Xtream tvg-id -> iptv-org tvtv.us xmltv_id.

Reads iptv-org/epg sites/tvtv.us/tvtv.us.channels.xml (GitHub raw or local clone).
Maps playlist-style ids (e.g. LifetimeNetwork.us, FoxNews.us) to feed ids with
@East/@West/@SD suffixes verified against tvtv.us site_ids.

No Xtream credentials required — uses bundled assets and optional local reports only.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent
ASSETS = ANDROID_ROOT / "app" / "src" / "main" / "assets"
CACHE_DIR = SCRIPT_DIR / ".cache"
OUT_PATH = ASSETS / "tvtv_id_bridge.json"

TVTV_CHANNELS_URL = (
    "https://raw.githubusercontent.com/iptv-org/epg/master/sites/tvtv.us/tvtv.us.channels.xml"
)
TVTV_CLONE_PATHS = (
    Path.home() / "epg" / "sites" / "tvtv.us" / "tvtv.us.channels.xml",
    ANDROID_ROOT.parent / "epg" / "sites" / "tvtv.us" / "tvtv.us.channels.xml",
)

# Xtream/Stalker base id -> iptv-org tvtv base id (before @feed suffix).
XTREAM_BASE_ALIASES: dict[str, str] = {
    "lifetimenetwork": "lifetime",
    "lifetimemovienetwork": "lifetimemovies",
    "lmn": "lifetimemovies",
    "foxnews": "foxnewschannel",
    "foxnewschannel": "foxnewschannel",
    "espnnews": "espnews",
    "usanetwork": "usanetwork",
    "accnetwork": "accnetwork",
    "accnx": "accnetwork",
    "hbo": "hbo",
    "hbo2": "hbo2",
    "cnn": "cnn",
    "espn": "espn",
    "espn2": "espn2",
    "lifetime": "lifetime",
    "lifetimemovies": "lifetimemovies",
}

# Minimum mappings that must resolve (verified against tvtv.us site_ids).
REQUIRED_CHECKS: dict[str, str] = {
    "LifetimeNetwork.us": "Lifetime.us@East",
    "LifetimeMovieNetwork.us": "LifetimeMovies.us@East",
    "LMN.us": "LifetimeMovies.us@East",
    "FoxNews.us": "FoxNewsChannel.us@SD",
    "FoxNewsChannel.us": "FoxNewsChannel.us@SD",
    "ESPN.us": "ESPN.us@SD",
    "USANetwork.us": "USANetwork.us@East",
    "HBO.us": "HBO.us@East",
    "CNN.us": "CNN.us@SD",
    "ACCNetwork.us": "ACCNetwork.us@SD",
}

FEED_RANK = {
    "@east": 0,
    "@sd": 1,
    "@us": 2,
    "@west": 3,
    "@central": 4,
    "@mountain": 5,
    "@pacific": 6,
}


def load_tvtv_xml() -> str:
    for path in TVTV_CLONE_PATHS:
        if path.is_file():
            print(f"Using local clone: {path}", file=sys.stderr)
            return path.read_text(encoding="utf-8")

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_path = CACHE_DIR / "tvtv.us.channels.xml"
    if cache_path.is_file() and cache_path.stat().st_size > 0:
        print(f"Using cached: {cache_path}", file=sys.stderr)
        return cache_path.read_text(encoding="utf-8")

    print(f"Downloading {TVTV_CHANNELS_URL}", file=sys.stderr)
    req = urllib.request.Request(TVTV_CHANNELS_URL, headers={"User-Agent": "StepDaddy/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        text = resp.read().decode("utf-8")
    cache_path.write_text(text, encoding="utf-8")
    return text


def parse_tvtv_channels(xml_text: str) -> list[tuple[str, str, str]]:
    """Return (site_id, xmltv_id, display_name) for channels with xmltv_id."""
    root = ET.fromstring(xml_text)
    rows: list[tuple[str, str, str]] = []
    for ch in root.findall("channel"):
        xmltv_id = (ch.attrib.get("xmltv_id") or "").strip()
        if not xmltv_id:
            continue
        site_id = (ch.attrib.get("site_id") or "").strip()
        name = (ch.text or "").strip()
        rows.append((site_id, xmltv_id, name))
    return rows


def split_id(full_id: str) -> tuple[str, str]:
    if "@" in full_id:
        base, suffix = full_id.split("@", 1)
        return base, f"@{suffix}"
    return full_id, ""


def compact_base(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def feed_rank(suffix: str) -> int:
    return FEED_RANK.get(suffix.lower(), 99)


def pick_preferred_feed(candidates: list[str], *, prefer_east: bool = True) -> str:
    if not candidates:
        return ""
    if len(candidates) == 1:
        return candidates[0]

    def sort_key(full_id: str) -> tuple[int, str]:
        _, suffix = split_id(full_id)
        if prefer_east and suffix.lower() == "@east":
            return (0, full_id)
        if suffix.lower() == "@sd":
            return (1, full_id)
        return (feed_rank(suffix), full_id)

    return sorted(candidates, key=sort_key)[0]


def build_tvtv_index(
    rows: list[tuple[str, str, str]],
) -> tuple[dict[str, str], dict[str, list[str]], dict[str, str]]:
    """base_id -> preferred xmltv_id, compact -> [full ids], verified site_id lookup."""
    by_base: dict[str, list[str]] = {}
    site_by_xmltv: dict[str, str] = {}
    for site_id, xmltv_id, _name in rows:
        base, _suffix = split_id(xmltv_id)
        by_base.setdefault(base, []).append(xmltv_id)
        site_by_xmltv[xmltv_id] = site_id

    preferred: dict[str, str] = {}
    for base, variants in by_base.items():
        prefer_east = base.endswith(".us") and not base.lower().endswith("radio.us")
        preferred[base] = pick_preferred_feed(variants, prefer_east=prefer_east)

    compact_to_bases: dict[str, list[str]] = {}
    for base in by_base:
        compact_to_bases.setdefault(compact_base(base), []).append(base)

    return preferred, compact_to_bases, site_by_xmltv


def xtream_base_candidates(playlist_id: str) -> list[str]:
    base, _ = split_id(playlist_id)
    if not base:
        return []

    country = ""
    name = base
    if "." in base:
        name, country = base.rsplit(".", 1)

    variants = {name}
    if name.endswith("Network"):
        variants.add(name[: -len("Network")])
    if name.endswith("Channel"):
        variants.add(name[: -len("Channel")])
    if "MovieNetwork" in name:
        variants.add(name.replace("MovieNetwork", "Movies"))
    if name.endswith("Movies") and not name.endswith("Network"):
        variants.add(name + "Network")

    out: list[str] = []
    for variant in variants:
        key = compact_base(variant)
        aliased = XTREAM_BASE_ALIASES.get(key, key)
        if country:
            out.append(f"{variant}.{country}" if variant == name else f"{_restore_casing(variant, name)}.{country}")
            # Also emit aliased casing from iptv-org patterns
            for cand in _casing_variants(aliased, country):
                if cand not in out:
                    out.append(cand)
        else:
            out.append(variant)
    # Deduplicate preserving order
    seen: set[str] = set()
    deduped: list[str] = []
    for item in out:
        if item not in seen:
            seen.add(item)
            deduped.append(item)
    return deduped


def _restore_casing(variant: str, original: str) -> str:
    """Keep CamelCase style similar to original when stripping suffixes."""
    if not original:
        return variant
    if original[0].isupper():
        return variant[:1].upper() + variant[1:]
    return variant


def _casing_variants(compact_key: str, country: str) -> list[str]:
    """Map normalized key back to likely iptv-org base ids."""
    # Title-case word boundaries for common patterns
    parts = re.findall(r"[a-z0-9]+", compact_key)
    if not parts:
        return []
    camel = parts[0].capitalize() + "".join(p.capitalize() for p in parts[1:])
    return [f"{camel}.{country}"]


def resolve_to_tvtv(
    playlist_id: str,
    preferred_by_base: dict[str, str],
    compact_to_bases: dict[str, list[str]],
) -> str | None:
    tid = playlist_id.strip()
    if not tid:
        return None

    base, suffix = split_id(tid)

    if base in preferred_by_base:
        if suffix:
            full = f"{base}{suffix}"
            if full in preferred_by_base.values() or any(
                full == v for v in _variants_for_base(preferred_by_base, base)
            ):
                return full
        return preferred_by_base[base]

    name_part, country = (base.rsplit(".", 1) if "." in base else (base, ""))
    compact = compact_base(name_part)
    aliased = XTREAM_BASE_ALIASES.get(compact, compact)

    for candidate_base in xtream_base_candidates(tid):
        if candidate_base in preferred_by_base:
            return preferred_by_base[candidate_base]

    for key in (compact, aliased):
        for tvtv_base in compact_to_bases.get(key, []):
            if country and not tvtv_base.endswith(f".{country}"):
                continue
            if tvtv_base in preferred_by_base:
                return preferred_by_base[tvtv_base]

    return None


def _variants_for_base(preferred_by_base: dict[str, str], base: str) -> list[str]:
    return [v for v in preferred_by_base.values() if v.startswith(base + "@") or v == base]


def collect_playlist_ids() -> set[str]:
    ids: set[str] = set()

    map_path = ASSETS / "channel_epg_map.json"
    if map_path.is_file():
        data = json.loads(map_path.read_text(encoding="utf-8"))
        for value in (data.get("mapping") or data).values():
            if isinstance(value, str) and value.strip():
                ids.add(value.strip())

    overrides_path = ASSETS / "epg_name_overrides.json"
    if overrides_path.is_file():
        overrides = json.loads(overrides_path.read_text(encoding="utf-8"))
        for value in overrides.values():
            if isinstance(value, str) and value.strip():
                ids.add(value.strip())

    db_path = ASSETS / "channels_db_cache.csv"
    if db_path.is_file():
        for line in db_path.read_text(encoding="utf-8").splitlines()[1:]:
            if not line or line.startswith("#"):
                continue
            col = line.split(",", 1)[0].strip()
            if col.endswith(".us") or ".us@" in col:
                ids.add(col)

    crosswalk = ANDROID_ROOT / "reports" / "xtream_epg_crosswalk_sample.json"
    if crosswalk.is_file():
        payload = json.loads(crosswalk.read_text(encoding="utf-8"))
        for entry in payload.get("entries") or []:
            tvg = (entry.get("tvg_id") or "").strip()
            if tvg:
                ids.add(tvg)

    ids.update(REQUIRED_CHECKS.keys())
    return ids


def verify_required(mappings: dict[str, str], site_by_xmltv: dict[str, str]) -> list[str]:
    errors: list[str] = []
    for xtream_id, expected in REQUIRED_CHECKS.items():
        actual = mappings.get(xtream_id)
        if actual != expected:
            errors.append(f"{xtream_id}: expected {expected}, got {actual}")
            continue
        if expected not in site_by_xmltv:
            errors.append(f"{expected}: missing from tvtv.us channels.xml")
    return errors


def main() -> int:
    xml_text = load_tvtv_xml()
    rows = parse_tvtv_channels(xml_text)
    preferred_by_base, compact_to_bases, site_by_xmltv = build_tvtv_index(rows)

    mappings: dict[str, str] = {}
    for playlist_id in sorted(collect_playlist_ids(), key=str.lower):
        resolved = resolve_to_tvtv(playlist_id, preferred_by_base, compact_to_bases)
        if resolved:
            mappings[playlist_id] = resolved

    # Identity bridge for iptv-org bases used directly in playlists
    for base, full_id in preferred_by_base.items():
        mappings.setdefault(base, full_id)

    # Apply required overrides last (verified tvtv feeds)
    for xtream_id, expected in REQUIRED_CHECKS.items():
        mappings[xtream_id] = expected

    errors = verify_required(mappings, site_by_xmltv)
    if errors:
        for err in errors:
            print(f"VERIFY FAIL: {err}", file=sys.stderr)
        return 1

    bridge: dict[str, dict[str, str]] = {}
    for playlist_id, feed_id in mappings.items():
        site_id = site_by_xmltv.get(feed_id, "").strip()
        if not site_id:
            continue
        bridge[playlist_id] = {
            "site_id": site_id,
            "xmltv_id": playlist_id,
        }

    payload = {
        "version": 1,
        "bridge_count": len(bridge),
        "generated_at": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
        "bridge": dict(sorted(bridge.items(), key=lambda kv: kv[0].lower())),
    }
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"Wrote {len(bridge)} bridge entries to {OUT_PATH}")
    print(f"tvtv.us channels with xmltv_id: {len(rows)}")
    for key in REQUIRED_CHECKS:
        entry = bridge.get(key)
        print(f"  {key} -> site_id={entry.get('site_id') if entry else '?'} feed={mappings.get(key)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

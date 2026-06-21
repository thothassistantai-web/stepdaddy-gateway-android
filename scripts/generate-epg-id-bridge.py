#!/usr/bin/env python3
"""Generate epg_id_bridge.json: playlist tvg-id -> epgshare feed channel ids."""

from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent
ASSETS = ANDROID_ROOT / "app" / "src" / "main" / "assets"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
OUT_PATH = ASSETS / "epg_id_bridge.json"
NAME_OVERRIDES_OUT = ASSETS / "epg_name_overrides.json"


def resolve_web_root() -> Path:
    env = (os.environ.get("STEPDADDY_WEB_ROOT") or os.environ.get("STEPDADDY_APP_ROOT") or "").strip()
    if env:
        return Path(env)
    for candidate in (
        Path.home() / "Programs" / "stepdaddy-web",
        ANDROID_ROOT.parent / "stepdaddy-web",
    ):
        if candidate.is_dir():
            return candidate.resolve()
    return Path.home() / "Programs" / "stepdaddy-web"


WEB_ROOT = resolve_web_root()
sys.path.insert(0, str(WEB_ROOT))

try:
    from app.epgshare_mapping import EpgShareChannelIndex, norm_tvg_id, loose_norm_tvg_id
except ImportError as exc:
    print(f"Cannot import stepdaddy-web EPG modules from {WEB_ROOT}: {exc}", file=sys.stderr)
    sys.exit(1)


def norm_channel_name(name: str) -> str:
    import re

    s = (name or "").lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def load_names_by_id(mapping: dict[str, str], overrides: dict[str, str]) -> dict[str, str]:
    cache_path = WEB_ROOT / "app" / "dlhd_channels_cache.json"
    id_to_name: dict[str, str] = {}
    if cache_path.is_file():
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
        channels = cache.get("channels") or cache
        if isinstance(channels, list):
            for ch in channels:
                cid = str(ch.get("channel_id") or ch.get("id") or "").strip()
                name = str(ch.get("channel_name") or ch.get("name") or "").strip()
                if cid and name:
                    id_to_name[cid] = name
    tvg_to_name = {tvg: name for name, tvg in overrides.items() if tvg}
    for cid, mapped_tvg in mapping.items():
        if cid not in id_to_name and mapped_tvg in tvg_to_name:
            id_to_name[cid] = tvg_to_name[mapped_tvg]
    return id_to_name


def feed_ids_for_playlist_id(
    index: EpgShareChannelIndex,
    playlist_id: str,
    channel_name: str | None,
    has_feed_id,
) -> list[str]:
    tid = playlist_id.strip()
    if not tid:
        return []
    if has_feed_id(tid):
        return [tid]

    feed_ids: list[str] = []
    if channel_name:
        norm = norm_channel_name(channel_name)
        matched, _conf, _method = index.match(channel_name, norm, enable_fuzzy=False)
        if matched and matched != tid:
            feed_ids.append(matched)

    # Norm-key crosswalk between iptv-org and epgshare dialects (no fuzzy — too slow at scale)
    for norm_fn in (norm_tvg_id, loose_norm_tvg_id):
        norm = norm_fn(tid)
        if not norm:
            continue
        candidates = index._by_norm.get(norm)
        if candidates:
            for candidate in candidates:
                if candidate not in feed_ids:
                    feed_ids.append(candidate)
        compact = norm.replace(" ", "")
        compact_candidates = index._by_compact.get(compact)
        if compact_candidates:
            for candidate in compact_candidates:
                if candidate not in feed_ids:
                    feed_ids.append(candidate)
    return feed_ids


def main() -> int:
    mapping = json.loads(MAPPING_PATH.read_text(encoding="utf-8"))["mapping"]
    overrides_src = WEB_ROOT / "app" / "epg_overrides.json"
    overrides: dict[str, str] = {}
    if overrides_src.is_file():
        overrides = json.loads(overrides_src.read_text(encoding="utf-8"))
        NAME_OVERRIDES_OUT.write_text(
            json.dumps(overrides, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"Synced {NAME_OVERRIDES_OUT.name} ({len(overrides)} entries)")

    index = EpgShareChannelIndex()
    if not index._load_cache():
        print("epgshare channel cache missing — run stepdaddy-web once to populate", file=sys.stderr)
        return 1

    all_ids_lower = {existing.lower() for existing in index._all_ids}

    def has_feed_id(tvg_id: str) -> bool:
        tid = (tvg_id or "").strip()
        if not tid:
            return False
        return tid in index._all_ids or tid.lower() in all_ids_lower

    def feed_ids_for(index: EpgShareChannelIndex, playlist_id: str, channel_name: str | None) -> list[str]:
        return feed_ids_for_playlist_id(index, playlist_id, channel_name, has_feed_id)

    names_by_id = load_names_by_id(mapping, overrides)
    bridge: dict[str, list[str]] = {}
    names_for_id: dict[str, str] = {}
    for cid, playlist_id in mapping.items():
        names_for_id.setdefault(playlist_id, names_by_id.get(cid, ""))

    for playlist_id, name in names_for_id.items():
        feed_ids = feed_ids_for(index, playlist_id, name or None)
        if feed_ids:
            bridge[playlist_id] = feed_ids

    if overrides:
        for name, override_tvg in overrides.items():
            if has_feed_id(override_tvg):
                continue
            feed_ids = feed_ids_for(index, override_tvg, name)
            if feed_ids:
                bridge.setdefault(override_tvg, feed_ids)

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "bridge_count": len(bridge),
        "bridge": dict(sorted(bridge.items())),
    }
    OUT_PATH.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(bridge)} bridge entries to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

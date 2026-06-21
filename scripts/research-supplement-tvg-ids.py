#!/usr/bin/env python3
"""Research proposed tvg-id mappings for supplement (non-DaddyLive) EPG gaps."""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.parse import urlparse

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from research_epg_common import (  # noqa: E402
    GAPS_CSV,
    OVERRIDES_PATH,
    REPORTS_DIR,
    ROOT,
    Candidate,
    QueueEntry,
    ResearchRow,
    build_name_index,
    build_token_index,
    enrich_tags,
    epgshare_exact_match,
    epgshare_fuzzy_match,
    exact_iptv_match,
    fuzzy_iptv_candidates,
    hard_reject,
    is_already_mapped,
    load_channels_db,
    load_existing_maps,
    lookup_epgshare_feed,
    norm_crosswalk_match,
    normalize_name,
    should_skip_channel,
    strip_quality,
    try_load_epgshare,
)

FAST_GROUPS = frozenset(
    {
        "documentary",
        "entertainment",
        "news",
        "movies",
        "kids",
        "music",
        "lifestyle",
        "comedy",
        "reality",
        "science",
        "travel",
        "food",
        "home",
        "gaming",
        "animation",
        "classic",
        "crime",
        "auto",
        "business",
        "weather",
        "faith",
        "spanish",
        "international",
        "culture",
        "history",
        "nature",
    }
)

FAST_HOST_MARKERS = (
    "ottera.tv",
    "akamaized.net",
    "jmp2.uk",
    "plu-",
    "xumo",
    "tubi.video",
    "tubi.io",
    "sofast.tv",
    "wurl.tv",
    "wurl.com",
    "frequency.stream",
    "klowdtv",
    "amagi.tv",
    "cloudfront.net",
    "stirr",
    "roku",
    "abnvideos",
    "plex",
    "vizio",
)

FAST_HASH_PROVIDERS = frozenset({"Samsung", "Pluto", "Plex", "Xumo", "Roku", "Tubi", "LocalNow"})
KNOWN_FAST_PROVIDERS = FAST_HASH_PROVIDERS | frozenset({"STIRR", "Distro", "FireTV"})
PROVIDER_ALIASES = {
    "samsung": "Samsung",
    "pluto": "Pluto",
    "plex": "Plex",
    "xumo": "Xumo",
    "roku": "Roku",
    "tubi": "Tubi",
    "stirr": "STIRR",
    "distro": "Distro",
    "firetv": "FireTV",
    "fire tv": "FireTV",
    "local": "LocalNow",
    "localnow": "LocalNow",
}

NAME_SUFFIX_PATTERN = re.compile(
    r"(?:[🇺🇸🇬🇧🇨🇦📡🎬]\s*)?(?:US|UK|CA)\s+"
    r"(Samsung|Pluto|Distro|Xumo|Roku|Plex|STIRR|Tubi|Fire\s*TV|Local(?:Now)?)\s*$",
    re.I,
)
IPTV_ORG_DOT_PATTERN = re.compile(
    r"\.(us|uk|ca|de|fr|it|es|au|nz|in|br|mx|jp|kr|se|no|dk|fi|nl|be|at|ch|pt|pl|gr|ie|za)(@|$)",
    re.I,
)
HASH_PREFIX_PATTERN = re.compile(r"^(USBD|US1800|USBA|US1)", re.I)
_NTV_SUFFIX = re.compile(r"\s+(Falcon|CDN)\s*$", re.I)
_MOJ_SUFFIX = re.compile(r"\s*\(MOJ\)\s*$", re.I)
_ADULT_SWIM = re.compile(r"adult\s*swim", re.I)
_TVAPP2_SUFFIX = re.compile(r"\.tvapp2$", re.I)

FAST_FEED_URLS: dict[str, str] = {
    "Pluto": "https://i.mjh.nz/PlutoTV/us.xml.gz",
    "Samsung": "https://i.mjh.nz/SamsungTVPlus/us.xml.gz",
    "Plex": "https://i.mjh.nz/Plex/us.xml.gz",
    "Roku": "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/Roku/all.xml.gz",
    "Xumo": "https://raw.githubusercontent.com/BuddyChewChew/xumo-playlist-generator/main/playlists/xumo_epg.xml.gz",
    "Tubi": "https://raw.githubusercontent.com/BuddyChewChew/tubi-scraper/main/tubi_epg.xml",
    "LocalNow": "https://raw.githubusercontent.com/BuddyChewChew/localnow-playlist-generator/main/epg.xml",
}


def base_stream_url(url: str) -> str:
    return (url or "").split("|")[0].strip()


def stream_host(url: str) -> str:
    u = base_stream_url(url)
    if u.startswith("iptv:"):
        return "iptv"
    try:
        return urlparse(u).netloc.lower() or "(no-host)"
    except Exception:
        return "(parse-error)"


def classify_supplement_bucket(row: dict[str, str]) -> str:
    u = base_stream_url(row.get("stream_url", ""))
    name = row.get("display_name", "")
    gt = row.get("group_title", "")
    nl = name.lower()
    gl = gt.lower()
    ulow = u.lower()

    if "tivimate-stream" in ulow:
        return "daddylive"
    if "/ntv-stream/" in ulow or "ntv-stream" in ulow:
        return "ntv"
    if "moveonjoy" in ulow:
        return "moveonjoy"
    if gl in ("local channels", "locals") or re.search(r"\bus local\b", nl):
        return "locals"
    if u.startswith("iptv:"):
        return "fast"
    if any(marker in ulow for marker in FAST_HOST_MARKERS):
        return "fast"
    if gl in FAST_GROUPS:
        return "fast"
    return "other"


def gap_kind(epg_status: str, current_tvg_id: str) -> str:
    if not (current_tvg_id or "").strip():
        return "missing_tvg_id"
    return "placeholder_epg"


def normalize_provider(raw: str | None) -> str | None:
    tag = (raw or "").strip()
    if not tag:
        return None
    low = tag.lower()
    if low in PROVIDER_ALIASES:
        return PROVIDER_ALIASES[low]
    for provider in KNOWN_FAST_PROVIDERS:
        if provider.lower() == low:
            return provider
    for provider in sorted(KNOWN_FAST_PROVIDERS, key=len, reverse=True):
        if tag.lower().endswith(provider.lower()) or re.search(
            rf"\b{re.escape(provider)}\b", tag, re.I
        ):
            return provider
    return None


def parse_provider_from_name(display_name: str) -> str | None:
    trimmed = display_name.strip()
    if not trimmed:
        return None
    m = NAME_SUFFIX_PATTERN.search(trimmed)
    if m:
        return normalize_provider(m.group(1))
    for provider in sorted(KNOWN_FAST_PROVIDERS, key=len, reverse=True):
        if re.search(rf"\b{re.escape(provider)}\b", trimmed, re.I):
            return provider
        if provider == "FireTV" and re.search(r"\bfire\s*tv\b", trimmed, re.I):
            return "FireTV"
        if provider == "LocalNow" and re.search(r"\blocal(?:\s*now)?\b", trimmed, re.I):
            return "LocalNow"
    return None


def parse_provider_from_group(group_title: str) -> str | None:
    for segment in reversed((group_title or "").split("|")):
        part = segment.strip()
        provider = normalize_provider(part)
        if provider:
            return provider
        for known in sorted(KNOWN_FAST_PROVIDERS, key=len, reverse=True):
            if known.lower() in part.lower():
                return known
    return None


def is_hash_style_fast_id(tvg_id: str) -> bool:
    tid = tvg_id.strip()
    if not tid:
        return False
    if "." not in tid:
        return True
    return bool(HASH_PREFIX_PATTERN.search(tid))


def is_iptv_org_dot_id(tvg_id: str) -> bool:
    tid = tvg_id.strip()
    return bool(tid and IPTV_ORG_DOT_PATTERN.search(tid))


def tvg_id_matches_provider(tvg_id: str, provider: str) -> bool:
    normalized = normalize_provider(provider)
    if not normalized:
        return True
    tid = tvg_id.strip()
    if not tid:
        return False
    if normalized in FAST_HASH_PROVIDERS:
        return is_hash_style_fast_id(tid) and not is_iptv_org_dot_id(tid)
    if normalized in ("STIRR", "FireTV"):
        return is_iptv_org_dot_id(tid) and not is_hash_style_fast_id(tid)
    if normalized == "Distro":
        return is_hash_style_fast_id(tid) or is_iptv_org_dot_id(tid)
    return True


def strip_ntv_name(name: str) -> str:
    s = _NTV_SUFFIX.sub("", name).strip()
    s = re.sub(r"\s+(USA|UK|ARGENTINA|BRAZIL|BULGARIA)\s*$", "", s, flags=re.I).strip()
    return s


def strip_moj_name(name: str) -> str:
    return _MOJ_SUFFIX.sub("", name).strip()


def adult_swim_slug(name: str) -> str | None:
    if not _ADULT_SWIM.search(name):
        return None
    base = re.sub(r"\s+adult\s*swim\s*$", "", name, flags=re.I).strip()
    slug = normalize_name(base).replace(" ", "-")
    return slug or None


@dataclass
class SupplementQueueEntry(QueueEntry):
    current_tvg_id: str = ""
    epg_status: str = ""
    bucket: str = ""


@dataclass
class SupplementResearchRow(ResearchRow):
    bucket: str = ""
    provider_tag: str = ""
    stream_host: str = ""
    current_tvg_id: str = ""
    epg_status: str = ""
    gap_kind: str = ""
    fast_catalog_candidate: str | None = None
    feed_verified: bool = False
    placeholder_risk: bool = False


class FastEpgCatalogSimulator:
    """Offline FastEpgCatalog: download XMLTV feeds and map display names → channel ids."""

    def __init__(self, cache_dir: Path | None = None) -> None:
        self.cache_dir = cache_dir or (REPORTS_DIR / "fast_epg_cache")
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._index: dict[tuple[str, str], str] = {}
        self._loaded = False

    def refresh(self, *, force: bool = False) -> int:
        if self._loaded and not force:
            return len(self._index)
        index: dict[tuple[str, str], str] = {}
        downloaded = 0
        for provider, url in FAST_FEED_URLS.items():
            cache = self._cache_path(provider, url)
            if force or not cache.is_file():
                if not self._download(url, cache):
                    continue
            if cache.is_file():
                self._index_channels(cache, provider, index)
                downloaded += 1
        self._index = index
        self._loaded = downloaded > 0
        return len(index)

    def lookup(self, channel_name: str, provider_tag: str | None) -> str | None:
        provider = normalize_provider(provider_tag)
        if not provider:
            return None
        norm = normalize_name(strip_quality(channel_name))
        if not norm:
            return None
        return self._index.get((provider, norm))

    def _cache_path(self, provider: str, url: str) -> Path:
        ext = ".xml.gz" if url.lower().endswith(".gz") else ".xml"
        return self.cache_dir / f"{provider.lower()}{ext}"

    def _download(self, url: str, target: Path) -> bool:
        tmp = target.with_suffix(target.suffix + ".part")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "StepDaddy-Research/1.0"})
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = resp.read(48 * 1024 * 1024 + 1)
            if len(data) > 48 * 1024 * 1024:
                return False
            tmp.write_bytes(data)
            tmp.replace(target)
            return True
        except Exception as exc:
            print(f"FAST feed download failed {url}: {exc}", file=sys.stderr)
            tmp.unlink(missing_ok=True)
            return False

    def _index_channels(self, path: Path, provider: str, index: dict[tuple[str, str], str]) -> None:
        try:
            if path.suffix == ".gz" or str(path).endswith(".xml.gz"):
                raw = gzip.decompress(path.read_bytes())
            else:
                raw = path.read_bytes()
            root = ET.fromstring(raw)
            for ch in root.findall("channel"):
                cid = ch.get("id") or ""
                for dn in ch.findall("display-name"):
                    display = (dn.text or "").strip()
                    if not display:
                        continue
                    norm = normalize_name(strip_quality(display))
                    if norm:
                        index.setdefault((provider, norm), cid)
        except Exception as exc:
            print(f"FAST feed parse failed {path}: {exc}", file=sys.stderr)


def load_supplement_gaps_queue(*, bucket: str | None = None) -> list[SupplementQueueEntry]:
    if not GAPS_CSV.is_file():
        return []
    entries: list[SupplementQueueEntry] = []
    with GAPS_CSV.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            stream = (row.get("stream_url") or "").strip()
            if "tivimate-stream" in stream:
                continue
            b = classify_supplement_bucket(row)
            if bucket and bucket != "all" and b != bucket:
                continue
            cid = (row.get("chno") or row.get("channel_id") or "").strip()
            if not cid:
                continue
            current_tvg = (row.get("tvg_id") or row.get("current_tvg_id") or "").strip()
            epg_status = (row.get("epg_status") or "").strip()
            display = (row.get("display_name") or "").strip()
            entries.append(
                SupplementQueueEntry(
                    channel_id=cid,
                    group_title=(row.get("group_title") or "").strip(),
                    display_name=display,
                    stream_url=stream,
                    current_tvg_id=current_tvg,
                    epg_status=epg_status,
                    bucket=b,
                )
            )
    return entries


def _finalize_row(
    row: SupplementResearchRow,
    ranked: list[Candidate],
    *,
    db,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    name: str,
    tags: list[str],
    cross_region_ok: bool = False,
) -> SupplementResearchRow:
    row.candidates = [
        {"tvg_id": c.tvg_id, "confidence": round(c.confidence, 3), "tier": c.tier, "method": c.method}
        for c in ranked[:8]
    ]
    for pick in ranked:
        reject = hard_reject(name, tags, pick.tvg_id, db, cross_region_ok=cross_region_ok)
        if reject:
            continue
        row.proposed_tvg_id = pick.tvg_id
        row.confidence = pick.confidence
        row.tier = pick.tier
        row.method = pick.method
        row.epgshare_feed_id = lookup_epgshare_feed(
            index, norm_tvg_id_fn, loose_norm_tvg_id_fn, pick.tvg_id
        )
        row.feed_verified = bool(index is not None and index.has_tvg_id(pick.tvg_id))
        if row.gap_kind == "placeholder_epg" and row.current_tvg_id and not row.feed_verified:
            row.placeholder_risk = True
        return row
    if ranked:
        row.reject_reason = hard_reject(name, tags, ranked[0].tvg_id, db) or "all candidates rejected"
        row.tier = "rejected"
    return row


def _fuzzy_with_tags(
    name: str,
    tags: list[str],
    db,
    token_index,
    *,
    min_conf: float = 0.45,
) -> list[Candidate]:
    cands = fuzzy_iptv_candidates(name, tags, db, token_index)
    return [c for c in cands if c.confidence >= min_conf]


def research_fast_bucket(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    fast_catalog: FastEpgCatalogSimulator,
) -> SupplementResearchRow:
    name = entry.display_name
    tags = enrich_tags(name, [])
    provider = (
        parse_provider_from_name(name)
        or parse_provider_from_group(entry.group_title)
        or ""
    )
    row = SupplementResearchRow(
        channel_id=entry.channel_id,
        channel_name=name,
        tags=tags,
        group_title=entry.group_title,
        bucket=entry.bucket,
        provider_tag=provider,
        stream_host=stream_host(entry.stream_url),
        current_tvg_id=entry.current_tvg_id,
        epg_status=entry.epg_status,
        gap_kind=gap_kind(entry.epg_status, entry.current_tvg_id),
    )

    skip = should_skip_channel(name, entry.group_title)
    if skip:
        row.tier = "skip"
        row.reject_reason = skip
        return row

    pipeline: list[Candidate] = []

    if provider:
        fast_id = fast_catalog.lookup(name, provider)
        if fast_id:
            row.fast_catalog_candidate = fast_id
            if tvg_id_matches_provider(fast_id, provider):
                pipeline.append(Candidate(fast_id, 0.94, "fast_catalog", f"fast_catalog:{provider}"))

    exact = exact_iptv_match(name, name_index, db)
    if exact and (not provider or tvg_id_matches_provider(exact.tvg_id, provider)):
        pipeline.append(exact)

    if exact is None or exact.confidence < 0.95:
        fuzzy = _fuzzy_with_tags(name, tags, db, token_index)
        if provider:
            fuzzy = [c for c in fuzzy if tvg_id_matches_provider(c.tvg_id, provider)]
        pipeline.extend(fuzzy[:5])

    if index is not None:
        es_exact = epgshare_exact_match(index, name)
        if es_exact:
            pipeline.append(es_exact)
        best_so_far = max((c.confidence for c in pipeline), default=0.0)
        if best_so_far < 0.75:
            es_fuzzy = epgshare_fuzzy_match(index, name)
            if es_fuzzy:
                pipeline.append(es_fuzzy)

    seed = next((c.tvg_id for c in pipeline if c.tier in ("manual", "exact_iptv", "fuzzy_iptv", "fast_catalog")), None)
    if seed and index is not None:
        cross = norm_crosswalk_match(index, norm_tvg_id_fn, loose_norm_tvg_id_fn, seed, name, tags)
        if cross:
            pipeline.append(cross)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        if provider and not tvg_id_matches_provider(cand.tvg_id, provider):
            continue
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    return _finalize_row(
        row, ranked, db=db, index=index,
        norm_tvg_id_fn=norm_tvg_id_fn, loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
        name=name, tags=tags,
    )


def research_ntv_bucket(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> SupplementResearchRow:
    raw_name = entry.display_name
    clean = strip_ntv_name(raw_name)
    tags = enrich_tags(clean, [])
    row = SupplementResearchRow(
        channel_id=entry.channel_id,
        channel_name=raw_name,
        tags=tags,
        group_title=entry.group_title,
        bucket=entry.bucket,
        provider_tag="ntv",
        stream_host=stream_host(entry.stream_url),
        current_tvg_id=entry.current_tvg_id,
        epg_status=entry.epg_status,
        gap_kind=gap_kind(entry.epg_status, entry.current_tvg_id),
    )

    skip = should_skip_channel(raw_name, entry.group_title)
    if skip:
        row.tier = "skip"
        row.reject_reason = skip
        return row

    pipeline: list[Candidate] = []
    exact = exact_iptv_match(clean, name_index, db)
    if exact:
        pipeline.append(exact)
    if exact is None or exact.confidence < 0.95:
        pipeline.extend(_fuzzy_with_tags(clean, tags, db, token_index)[:5])

    if index is not None:
        es_exact = epgshare_exact_match(index, clean)
        if es_exact:
            pipeline.append(es_exact)
        if max((c.confidence for c in pipeline), default=0.0) < 0.75:
            es_fuzzy = epgshare_fuzzy_match(index, clean)
            if es_fuzzy:
                pipeline.append(es_fuzzy)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    return _finalize_row(
        row, ranked, db=db, index=index,
        norm_tvg_id_fn=norm_tvg_id_fn, loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
        name=clean, tags=tags,
    )


def research_moveonjoy_bucket(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> SupplementResearchRow:
    raw_name = entry.display_name
    clean = strip_moj_name(raw_name)
    tags = enrich_tags(clean, ["🇺🇸"])
    row = SupplementResearchRow(
        channel_id=entry.channel_id,
        channel_name=raw_name,
        tags=tags,
        group_title=entry.group_title,
        bucket=entry.bucket,
        provider_tag="moveonjoy",
        stream_host=stream_host(entry.stream_url),
        current_tvg_id=entry.current_tvg_id,
        epg_status=entry.epg_status,
        gap_kind=gap_kind(entry.epg_status, entry.current_tvg_id),
    )

    pipeline: list[Candidate] = []
    if index is not None:
        es_exact = epgshare_exact_match(index, clean)
        if es_exact:
            pipeline.append(es_exact)

    fuzzy = _fuzzy_with_tags(clean, tags, db, token_index)
    pipeline.extend(fuzzy[:5])

    current = entry.current_tvg_id
    if current and _TVAPP2_SUFFIX.search(current) and index is not None:
        cross = norm_crosswalk_match(index, norm_tvg_id_fn, loose_norm_tvg_id_fn, current, clean, tags)
        if cross:
            pipeline.append(cross)
        else:
            for cand in list(pipeline):
                if cand.tier in ("exact_iptv", "fuzzy_iptv") and not _TVAPP2_SUFFIX.search(cand.tvg_id):
                    pipeline.remove(cand)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    return _finalize_row(
        row, ranked, db=db, index=index,
        norm_tvg_id_fn=norm_tvg_id_fn, loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
        name=clean, tags=tags,
    )


def research_locals_bucket(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> SupplementResearchRow:
    name = entry.display_name
    tags = enrich_tags(name, ["🇺🇸"])
    row = SupplementResearchRow(
        channel_id=entry.channel_id,
        channel_name=name,
        tags=tags,
        group_title=entry.group_title,
        bucket=entry.bucket,
        provider_tag="locals",
        stream_host=stream_host(entry.stream_url),
        current_tvg_id=entry.current_tvg_id,
        epg_status=entry.epg_status,
        gap_kind=gap_kind(entry.epg_status, entry.current_tvg_id),
    )

    pipeline: list[Candidate] = []
    exact = exact_iptv_match(name, name_index, db)
    if exact:
        pipeline.append(exact)

    if index is not None:
        for suffix in ("", "@HD", "@SD"):
            variant = f"{name}{suffix}" if suffix else name
            es = epgshare_exact_match(index, variant)
            if es:
                pipeline.append(es)

    if exact is None or exact.confidence < 0.95:
        pipeline.extend(_fuzzy_with_tags(name, tags, db, token_index)[:5])

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    return _finalize_row(
        row, ranked, db=db, index=index,
        norm_tvg_id_fn=norm_tvg_id_fn, loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
        name=name, tags=tags,
    )


def research_other_bucket(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> SupplementResearchRow:
    name = entry.display_name
    tags = enrich_tags(name, [])
    row = SupplementResearchRow(
        channel_id=entry.channel_id,
        channel_name=name,
        tags=tags,
        group_title=entry.group_title,
        bucket=entry.bucket,
        provider_tag="other",
        stream_host=stream_host(entry.stream_url),
        current_tvg_id=entry.current_tvg_id,
        epg_status=entry.epg_status,
        gap_kind=gap_kind(entry.epg_status, entry.current_tvg_id),
    )

    skip = should_skip_channel(name, entry.group_title)
    if skip:
        row.tier = "skip"
        row.reject_reason = skip
        return row

    pipeline: list[Candidate] = []
    slug = adult_swim_slug(name)
    if slug:
        as_id = f"adultswim:{slug}"
        pipeline.append(Candidate(as_id, 0.88, "adult_swim_slug", "adult_swim_catalog"))

    exact = exact_iptv_match(name, name_index, db)
    if exact:
        pipeline.append(exact)
    if exact is None or exact.confidence < 0.95:
        pipeline.extend(_fuzzy_with_tags(name, tags, db, token_index)[:5])

    if index is not None:
        es_exact = epgshare_exact_match(index, name)
        if es_exact:
            pipeline.append(es_exact)
        if max((c.confidence for c in pipeline), default=0.0) < 0.75:
            es_fuzzy = epgshare_fuzzy_match(index, name)
            if es_fuzzy:
                pipeline.append(es_fuzzy)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    return _finalize_row(
        row, ranked, db=db, index=index,
        norm_tvg_id_fn=norm_tvg_id_fn, loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
        name=name, tags=tags,
    )


BUCKET_RESEARCHERS = {
    "fast": research_fast_bucket,
    "ntv": research_ntv_bucket,
    "moveonjoy": research_moveonjoy_bucket,
    "locals": research_locals_bucket,
    "other": research_other_bucket,
}


def research_supplement_entry(
    entry: SupplementQueueEntry,
    *,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    fast_catalog: FastEpgCatalogSimulator,
) -> SupplementResearchRow:
    fn = BUCKET_RESEARCHERS.get(entry.bucket, research_other_bucket)
    if fn is research_fast_bucket:
        return fn(
            entry,
            db=db,
            name_index=name_index,
            token_index=token_index,
            index=index,
            norm_tvg_id_fn=norm_tvg_id_fn,
            loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
            fast_catalog=fast_catalog,
        )
    return fn(
        entry,
        db=db,
        name_index=name_index,
        token_index=token_index,
        index=index,
        norm_tvg_id_fn=norm_tvg_id_fn,
        loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
    )


def apply_high_confidence_supplement(rows: list[SupplementResearchRow], min_confidence: float = 0.90) -> int:
    existing: dict[str, str] = {}
    if OVERRIDES_PATH.is_file():
        existing = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    changed = 0
    for row in rows:
        if not row.proposed_tvg_id or row.confidence < min_confidence:
            continue
        if row.tier in ("skip", "rejected", "no_match"):
            continue
        if row.gap_kind == "placeholder_epg":
            provider_ok = bool(
                row.fast_catalog_candidate
                and row.provider_tag
                and row.proposed_tvg_id == row.fast_catalog_candidate
                and tvg_id_matches_provider(row.proposed_tvg_id, row.provider_tag)
            )
            if not row.feed_verified and not provider_ok:
                continue
        if existing.get(row.channel_name) == row.proposed_tvg_id:
            continue
        existing[row.channel_name] = row.proposed_tvg_id
        changed += 1
    OVERRIDES_PATH.write_text(
        json.dumps(dict(sorted(existing.items(), key=lambda kv: kv[0].lower())), indent=2, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    return changed


def write_supplement_json(path: Path, rows: list[SupplementResearchRow], meta: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {**meta, "channels": [asdict(r) for r in rows]}
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_supplement_csv(path: Path, rows: list[SupplementResearchRow], min_review: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "channel_id",
        "channel_name",
        "bucket",
        "provider_tag",
        "group_title",
        "current_tvg_id",
        "epg_status",
        "gap_kind",
        "proposed_tvg_id",
        "fast_catalog_candidate",
        "epgshare_feed_id",
        "feed_verified",
        "placeholder_risk",
        "confidence",
        "tier",
        "method",
        "reject_reason",
        "stream_host",
        "top_candidates",
    ]
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for row in rows:
            if row.confidence < min_review and row.tier not in ("skip", "rejected"):
                continue
            w.writerow(
                {
                    "channel_id": row.channel_id,
                    "channel_name": row.channel_name,
                    "bucket": row.bucket,
                    "provider_tag": row.provider_tag,
                    "group_title": row.group_title,
                    "current_tvg_id": row.current_tvg_id,
                    "epg_status": row.epg_status,
                    "gap_kind": row.gap_kind,
                    "proposed_tvg_id": row.proposed_tvg_id or "",
                    "fast_catalog_candidate": row.fast_catalog_candidate or "",
                    "epgshare_feed_id": row.epgshare_feed_id or "",
                    "feed_verified": str(row.feed_verified),
                    "placeholder_risk": str(row.placeholder_risk),
                    "confidence": f"{row.confidence:.3f}",
                    "tier": row.tier,
                    "method": row.method,
                    "reject_reason": row.reject_reason,
                    "stream_host": row.stream_host,
                    "top_candidates": ";".join(
                        f"{c['tvg_id']}({c['confidence']:.2f})" for c in row.candidates[:3]
                    ),
                }
            )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Research tvg-id mappings for supplement EPG gaps")
    parser.add_argument(
        "--only-gaps",
        action="store_true",
        help="Process supplement rows from ~/Desktop/stepdaddy-epg-gaps.csv (excludes tivimate-stream)",
    )
    parser.add_argument(
        "--bucket",
        choices=["fast", "ntv", "moveonjoy", "locals", "other", "all"],
        default="all",
        help="Restrict to a supplement bucket",
    )
    parser.add_argument(
        "--apply-high",
        action="store_true",
        help="Merge high-confidence proposals into epg_name_overrides.json",
    )
    parser.add_argument("--min-review", type=float, default=0.65, help="Min confidence for review CSV")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=REPORTS_DIR / "supplement_epg_research.json",
        help="JSON research report path",
    )
    parser.add_argument(
        "--csv",
        type=Path,
        default=REPORTS_DIR / "supplement_epg_review.csv",
        help="CSV review export path",
    )
    parser.add_argument("--limit", type=int, default=None, help="Process at most N channels (smoke test)")
    parser.add_argument(
        "--import-research",
        action="store_true",
        help="Import high-confidence rows via import-daddylive-research.py",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if not args.only_gaps:
        print("--only-gaps is required (supplement queue is gaps CSV only)", file=sys.stderr)
        return 1
    if not GAPS_CSV.is_file():
        print(f"Gaps CSV not found: {GAPS_CSV}", file=sys.stderr)
        return 1

    mapping, overrides = load_existing_maps()
    print("Loading iptv-org index...", file=sys.stderr)
    db = load_channels_db()
    name_index = build_name_index(db)
    token_index = build_token_index(db)
    print("Loading epgshare index...", file=sys.stderr)
    index, norm_tvg_id_fn, loose_norm_tvg_id_fn = try_load_epgshare()
    epgshare_status = "loaded" if index is not None else "skipped"

    bucket_filter = None if args.bucket == "all" else args.bucket
    queue = load_supplement_gaps_queue(bucket=bucket_filter)
    if not queue:
        print("No supplement gap rows matched filters", file=sys.stderr)
        return 1

    fast_catalog = FastEpgCatalogSimulator()
    needs_fast = bucket_filter in (None, "all", "fast") or any(e.bucket == "fast" for e in queue[: args.limit or 50])
    if needs_fast:
        print("Loading FAST EPG catalog...", file=sys.stderr)
        n_fast = fast_catalog.refresh()
        print(f"FAST catalog: {n_fast} name mappings", file=sys.stderr)

    rows: list[SupplementResearchRow] = []
    for entry in queue:
        if args.limit is not None and len(rows) >= args.limit:
            break
        if is_already_mapped(entry.channel_id, entry.display_name, mapping, overrides):
            continue
        rows.append(
            research_supplement_entry(
                entry,
                db=db,
                name_index=name_index,
                token_index=token_index,
                index=index,
                norm_tvg_id_fn=norm_tvg_id_fn,
                loose_norm_tvg_id_fn=loose_norm_tvg_id_fn,
                fast_catalog=fast_catalog,
            )
        )

    tier_counts: dict[str, int] = {}
    bucket_tier_counts: dict[str, dict[str, int]] = {}
    bucket_counts: dict[str, int] = {}
    for row in rows:
        tier_counts[row.tier] = tier_counts.get(row.tier, 0) + 1
        bucket_counts[row.bucket] = bucket_counts.get(row.bucket, 0) + 1
        bt = bucket_tier_counts.setdefault(row.bucket, {})
        bt[row.tier] = bt.get(row.tier, 0) + 1

    meta = {
        "gaps_csv": str(GAPS_CSV),
        "epgshare": epgshare_status,
        "bucket_filter": args.bucket,
        "processed": len(rows),
        "tier_counts": dict(sorted(tier_counts.items())),
        "bucket_counts": dict(sorted(bucket_counts.items())),
        "bucket_tier_counts": {b: dict(sorted(t.items())) for b, t in sorted(bucket_tier_counts.items())},
    }

    write_supplement_json(args.output, rows, meta)
    write_supplement_csv(args.csv, rows, args.min_review)

    print(f"Wrote {args.output} ({len(rows)} channels)")
    print(f"Wrote {args.csv}")
    print(f"epgshare: {epgshare_status}")
    print("tier counts:")
    for tier, count in sorted(tier_counts.items()):
        print(f"  {tier}: {count}")

    if args.apply_high:
        n = apply_high_confidence_supplement(rows)
        print(f"Applied {n} high-confidence overrides to {OVERRIDES_PATH}")

    if args.import_research:
        import_script = ROOT / "scripts" / "import-daddylive-research.py"
        if import_script.is_file():
            rc = subprocess.call(
                [sys.executable, str(import_script), str(args.output), "--min-confidence", "0.90"],
            )
            if rc != 0:
                print("import-daddylive-research.py failed", file=sys.stderr)
                return rc

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

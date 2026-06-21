#!/usr/bin/env python3
"""Shared EPG research utilities for DaddyLive and supplement scripts."""

from __future__ import annotations

import csv
import json
import os
import re
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
OVERRIDES_PATH = ASSETS / "epg_name_overrides.json"
CHANNELS_DB = ASSETS / "channels_db_cache.csv"
REPORTS_DIR = ROOT / "reports"

UNMAPPED_CANDIDATES = (
    Path.home() / "Desktop" / "unmapped_channels.csv",
    Path.home() / "Desktop" / "epg-repair" / "unmapped_channels.csv",
)
GAPS_CSV = Path.home() / "Desktop" / "stepdaddy-epg-gaps.csv"

# High-confidence manual corrections (channel display name -> tvg_id).
MANUAL: dict[str, str] = {
    "5 USA": "5USA.uk",
    "Channel 5 UK": "Channel5.uk",
    "ABC USA": "ABC.us",
    "CBS USA": "CBS.us",
    "NBC USA": "NBC.us",
    "FOX USA": "Fox.us",
    "CW USA": "WUCW-DT.us_locals1",
    "ESPN2 USA": "ESPN2.us",
    "FX USA": "FX.us",
    "PBS USA": "PBS.us",
    "ION USA": "IonTV.us",
    "VH1 USA": "VH1.us",
    "Dave": "Dave.uk",
    "DAZN 1 UK": "DAZN1.uk",
    "ITV 2 UK": "ITV2.uk",
    "ITV 3 UK": "ITV3.uk",
    "ITV 4 UK": "ITV4.uk",
    "Telemundo": "Telemundo.us",
    "Univision": "Univision.us",
    "Unimas": "UniMas.us",
}

COUNTRY_SUFFIX = {
    "uk": "UK",
    "us": "US",
    "us2": "US",
    "ca": "CA",
    "ca2": "CA",
    "de": "DE",
    "fr": "FR",
    "it": "IT",
    "es": "ES",
    "tr": "TR",
    "ae": "AE",
    "eg": "EG",
    "qa": "QA",
    "nl": "NL",
    "be": "BE",
    "pl": "PL",
    "cz": "CZ",
    "sk": "SK",
    "at": "AT",
    "ch": "CH",
    "au": "AU",
    "in": "IN",
    "pk": "PK",
    "sg": "SG",
    "my": "MY",
    "za": "ZA",
    "br": "BR",
    "ar": "AR",
    "mx": "MX",
    "ru": "RU",
    "ua": "UA",
    "ba": "BA",
    "dk": "DK",
    "se": "SE",
    "no": "NO",
    "fi": "FI",
    "pt": "PT",
    "gr": "GR",
    "hu": "HU",
    "ro": "RO",
    "bg": "BG",
    "hr": "HR",
    "rs": "RS",
    "ie": "IE",
}

NAME_COUNTRY_HINTS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\bUK\b", re.I), "UK"),
    (re.compile(r"\bGB\b", re.I), "UK"),
    (re.compile(r"\bUSA\b", re.I), "US"),
    (re.compile(r"\bUS\b", re.I), "US"),
    (re.compile(r"\bDE\b|\bGermany\b", re.I), "DE"),
    (re.compile(r"\bFrance\b|\bFR\b", re.I), "FR"),
    (re.compile(r"\bItaly\b|\bIT\b", re.I), "IT"),
    (re.compile(r"\bSpain\b|\bES\b", re.I), "ES"),
    (re.compile(r"\bTurkey\b|\bTR\b", re.I), "TR"),
    (re.compile(r"\bUAE\b|\bAE\b", re.I), "AE"),
    (re.compile(r"\bEgypt\b|\bEG\b", re.I), "EG"),
    (re.compile(r"\bCanada\b|\bCA\b", re.I), "CA"),
    (re.compile(r"\bAustralia\b|\bAU\b", re.I), "AU"),
    (re.compile(r"\bDenmark\b|\bDK\b", re.I), "DK"),
    (re.compile(r"\bNetherlands\b|\bNL\b", re.I), "NL"),
    (re.compile(r"\bCzech\b|\bCZ\b", re.I), "CZ"),
    (re.compile(r"\bSlovakia\b|\bSK\b", re.I), "SK"),
    (re.compile(r"\bPoland\b|\bPL\b", re.I), "PL"),
    (re.compile(r"\bSwitzerland\b|\bCH\b", re.I), "CH"),
    (re.compile(r"\bAustria\b|\bAT\b", re.I), "AT"),
    (re.compile(r"\bBelgium\b|\bBE\b", re.I), "BE"),
    (re.compile(r"\bQatar\b|\bQA\b", re.I), "QA"),
    (re.compile(r"\bSaudi\b|\bSA\b", re.I), "SA"),
    (re.compile(r"\bIndia\b|\bIN\b", re.I), "IN"),
    (re.compile(r"\bPakistan\b|\bPK\b", re.I), "PK"),
    (re.compile(r"\bBrazil\b|\bBR\b", re.I), "BR"),
    (re.compile(r"\bArgentina\b|\bAR\b", re.I), "AR"),
    (re.compile(r"\bMexico\b|\bMX\b", re.I), "MX"),
    (re.compile(r"\bRussia\b|\bRU\b", re.I), "RU"),
    (re.compile(r"\bUkraine\b|\bUA\b", re.I), "UA"),
    (re.compile(r"\bIreland\b|\bIE\b", re.I), "IE"),
    (re.compile(r"\bNorway\b|\bNO\b", re.I), "NO"),
    (re.compile(r"\bSweden\b|\bSE\b", re.I), "SE"),
    (re.compile(r"\bFinland\b|\bFI\b", re.I), "FI"),
    (re.compile(r"\bPortugal\b|\bPT\b", re.I), "PT"),
    (re.compile(r"\bGreece\b|\bGR\b", re.I), "GR"),
    (re.compile(r"\bHungary\b|\bHU\b", re.I), "HU"),
    (re.compile(r"\bRomania\b|\bRO\b", re.I), "RO"),
    (re.compile(r"\bSouth Africa\b|\bZA\b", re.I), "ZA"),
    (re.compile(r"\bBIH\b|\bBosnia\b", re.I), "BA"),
    (re.compile(r"\bSingapore\b|\bSG\b", re.I), "SG"),
    (re.compile(r"\bMalaysia\b|\bMY\b", re.I), "MY"),
]

TAG_COUNTRY = {
    "🇬🇧": "UK",
    "🇺🇸": "US",
    "🇩🇪": "DE",
    "🇫🇷": "FR",
    "🇮🇹": "IT",
    "🇪🇸": "ES",
    "🇹🇷": "TR",
    "🇦🇪": "AE",
    "🇪🇬": "EG",
    "🇨🇦": "CA",
    "🇦🇺": "AU",
    "🇩🇰": "DK",
    "🇳🇱": "NL",
    "🇨🇿": "CZ",
    "🇸🇰": "SK",
    "🇵🇱": "PL",
    "🇨🇭": "CH",
    "🇦🇹": "AT",
    "🇧🇪": "BE",
    "🇶🇦": "QA",
    "🇸🇦": "SA",
    "🇮🇳": "IN",
    "🇵🇰": "PK",
    "🇧🇷": "BR",
    "🇦🇷": "AR",
    "🇲🇽": "MX",
    "🇷🇺": "RU",
    "🇺🇦": "UA",
    "🇮🇪": "IE",
    "🇳🇴": "NO",
    "🇸🇪": "SE",
    "🇫🇮": "FI",
    "🇵🇹": "PT",
    "🇬🇷": "GR",
    "🇭🇺": "HU",
    "🇷🇴": "RO",
    "🇿🇦": "ZA",
    "🇧🇦": "BA",
    "🇸🇬": "SG",
    "🇲🇾": "MY",
}

_GREEK_CYRILLIC = re.compile(r"[\u0370-\u03FF\u0400-\u04FF]")
_ARABIC = re.compile(r"[\u0600-\u06FF]")
_EVENT_VS = re.compile(r"\bvs\.?\b", re.I)
_247_EXTRA = re.compile(r"24\s*/\s*7", re.I)


def _resolve_web_root() -> Path:
    env = (os.environ.get("STEPDADDY_WEB_ROOT") or os.environ.get("STEPDADDY_APP_ROOT") or "").strip()
    if env:
        return Path(env)
    programs = Path.home() / "Programs" / "stepdaddy-web"
    if programs.is_dir():
        return programs
    sibling = ROOT.parent / "stepdaddy-web"
    if sibling.is_dir():
        return sibling.resolve()
    return programs


WEB_ROOT = _resolve_web_root()


def resolve_dlhd_cache() -> Path:
    web = WEB_ROOT / "app" / "dlhd_channels_cache.json"
    if web.is_file():
        return web
    for candidate in (
        ROOT / "app" / "dlhd_channels_cache.json",
        ROOT / "dlhd_channels_cache.json",
    ):
        if candidate.is_file():
            return candidate
    return web


def normalize_name(name: str) -> str:
    s = name.lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def strip_quality(name: str) -> str:
    return re.sub(r"\(\s*\d+p\s*\)", "", name, flags=re.I).strip()


def tokens(name: str) -> set[str]:
    t = {x for x in normalize_name(name).split() if len(x) > 1 or x.isdigit()}
    return {x for x in t if x not in {"plus", "and", "the"}}


def tvg_country(tvg_id: str) -> str | None:
    if not tvg_id:
        return None
    low = tvg_id.lower()
    m = re.search(r"\.([a-z0-9]{2,3})(?:\d)?$", low)
    if m:
        return COUNTRY_SUFFIX.get(m.group(1), m.group(1).upper())
    for part in re.split(r"[.\s]+", low):
        if part in COUNTRY_SUFFIX:
            return COUNTRY_SUFFIX[part]
    return None


def expected_countries(name: str, tags: list[str]) -> set[str]:
    out: set[str] = set()
    for pat, cc in NAME_COUNTRY_HINTS:
        if pat.search(name):
            out.add(cc)
    for tag in tags:
        for emoji, cc in TAG_COUNTRY.items():
            if emoji in tag:
                out.add(cc)
    if name.strip() == "5 USA":
        out.add("UK")
        out.discard("US")
    return out


@dataclass
class ChannelMeta:
    tvg_id: str
    name: str
    country: str
    alt_names: str


@dataclass
class Candidate:
    tvg_id: str
    confidence: float
    tier: str
    method: str


@dataclass
class ResearchRow:
    channel_id: str
    channel_name: str
    tags: list[str] = field(default_factory=list)
    group_title: str = ""
    proposed_tvg_id: str | None = None
    epgshare_feed_id: str | None = None
    confidence: float = 0.0
    tier: str = "no_match"
    method: str = ""
    candidates: list[dict] = field(default_factory=list)
    reject_reason: str = ""


def load_channels_db() -> dict[str, ChannelMeta]:
    out: dict[str, ChannelMeta] = {}
    with CHANNELS_DB.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            cid = (row.get("id") or "").strip()
            if cid:
                out[cid] = ChannelMeta(
                    tvg_id=cid,
                    name=(row.get("name") or "").strip(),
                    country=(row.get("country") or "").strip().upper(),
                    alt_names=(row.get("alt_names") or "").strip(),
                )
    return out


def build_name_index(db: dict[str, ChannelMeta]) -> dict[str, list[str]]:
    idx: dict[str, list[str]] = {}
    for cid, meta in db.items():
        for label in [meta.name, *meta.alt_names.split(";")]:
            label = label.strip()
            if not label:
                continue
            key = normalize_name(label)
            idx.setdefault(key, []).append(cid)
    return idx


def build_token_index(db: dict[str, ChannelMeta]) -> dict[str, set[str]]:
    """Inverted token index for fuzzy iptv-org candidate pruning."""
    idx: dict[str, set[str]] = {}
    for cid, meta in db.items():
        for label in [meta.name, *meta.alt_names.split(";")]:
            for tok in tokens(label):
                idx.setdefault(tok, set()).add(cid)
    return idx


def score_candidate(channel_name: str, channel_tags: list[str], meta: ChannelMeta) -> float:
    ch_tok = tokens(channel_name)
    labels = [meta.name, *meta.alt_names.split(";")]
    best = 0.0
    for label in labels:
        lab_tok = tokens(label)
        if not ch_tok or not lab_tok:
            continue
        inter = ch_tok & lab_tok
        union = ch_tok | lab_tok
        jaccard = len(inter) / len(union)
        if inter == {x for x in inter if x.isdigit() and len(x) <= 1} and len(inter) <= 1:
            jaccard *= 0.2
        best = max(best, jaccard)
    exp = expected_countries(channel_name, channel_tags)
    if exp and meta.country and meta.country in exp:
        best += 0.35
    elif exp and meta.country and meta.country not in exp:
        best -= 0.5
    return best


def fuzzy_iptv_candidates(
    channel_name: str,
    channel_tags: list[str],
    db: dict[str, ChannelMeta],
    token_index: dict[str, set[str]],
) -> list[Candidate]:
    exp = expected_countries(channel_name, channel_tags)
    ch_tok = tokens(channel_name)
    pool: set[str] = set()
    for tok in ch_tok:
        pool.update(token_index.get(tok, ()))
    if not pool and exp:
        pool = {cid for cid, meta in db.items() if meta.country in exp}
    scored: list[tuple[float, str, str]] = []
    for cid in pool:
        meta = db.get(cid)
        if meta is None:
            continue
        if exp and meta.country and meta.country not in exp:
            continue
        raw = score_candidate(channel_name, channel_tags, meta)
        if raw < 0.45:
            continue
        conf = min(0.88, max(0.55, raw))
        scored.append((conf, cid, "iptv_fuzzy_jaccard"))
    scored.sort(reverse=True)
    return [
        Candidate(tvg_id=cid, confidence=conf, tier="fuzzy_iptv", method=method)
        for conf, cid, method in scored[:8]
    ]


def exact_iptv_match(
    channel_name: str,
    name_index: dict[str, list[str]],
    db: dict[str, ChannelMeta],
) -> Candidate | None:
    if channel_name in MANUAL and MANUAL[channel_name] in db:
        return Candidate(MANUAL[channel_name], 0.99, "manual", "manual_dict")
    key = normalize_name(channel_name)
    if key in name_index:
        cands = name_index[key]
        if len(cands) == 1:
            return Candidate(cands[0], 0.95, "exact_iptv", "iptv_exact_name")
        if cands:
            return Candidate(cands[0], 0.93, "exact_iptv", "iptv_exact_ambiguous")
    return None


def enrich_tags(name: str, tags: list[str]) -> list[str]:
    """Merge dlhd cache tags with country flag emojis parsed from the display name."""
    out = list(tags)
    seen = set(out)
    for emoji in TAG_COUNTRY:
        if emoji in name and emoji not in seen:
            out.append(emoji)
            seen.add(emoji)
    return out


def group_title_from_tags(tags: list[str]) -> str:
    for tag in tags:
        if tag.startswith("#") and len(tag) > 1:
            return tag[1:].replace("_", " ").title()
    return ""


def load_dlhd() -> dict[str, dict]:
    cache_path = resolve_dlhd_cache()
    if not cache_path.is_file():
        print(f"dlhd cache not found: {cache_path}", file=sys.stderr)
        sys.exit(1)
    data = json.loads(cache_path.read_text(encoding="utf-8"))
    channels = data.get("channels") or data
    if not isinstance(channels, list):
        print("invalid dlhd cache format", file=sys.stderr)
        sys.exit(1)
    out: dict[str, dict] = {}
    for ch in channels:
        cid = str(ch.get("channel_id") or ch.get("id") or "").strip()
        if cid:
            out[cid] = ch
    return out


def load_existing_maps() -> tuple[dict[str, str], dict[str, str]]:
    mapping: dict[str, str] = {}
    if MAPPING_PATH.is_file():
        mapping = json.loads(MAPPING_PATH.read_text(encoding="utf-8")).get("mapping", {})
    overrides: dict[str, str] = {}
    if OVERRIDES_PATH.is_file():
        overrides = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    return mapping, overrides


def is_already_mapped(
    channel_id: str,
    channel_name: str,
    mapping: dict[str, str],
    overrides: dict[str, str],
) -> bool:
    if channel_id in mapping and (mapping[channel_id] or "").strip():
        return True
    if channel_name in overrides and (overrides[channel_name] or "").strip():
        return True
    return False


@dataclass
class QueueEntry:
    channel_id: str
    group_title: str = ""
    display_name: str = ""
    stream_url: str = ""


def _load_gaps_queue(*, daddylive_only: bool) -> list[QueueEntry]:
    if not GAPS_CSV.is_file():
        return []
    entries: list[QueueEntry] = []
    with GAPS_CSV.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            tvg_id = (row.get("tvg_id") or row.get("current_tvg_id") or "").strip()
            if tvg_id:
                continue
            cid = (row.get("chno") or row.get("channel_id") or "").strip()
            if not cid:
                continue
            stream = (row.get("stream_url") or "").strip()
            if daddylive_only and "tivimate-stream" not in stream:
                continue
            entries.append(
                QueueEntry(
                    channel_id=cid,
                    group_title=(row.get("group_title") or "").strip(),
                    display_name=(row.get("display_name") or "").strip(),
                    stream_url=stream,
                )
            )
    return entries


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


def try_load_epgshare():
    sys.path.insert(0, str(WEB_ROOT))
    try:
        from app.epgshare_mapping import EpgShareChannelIndex, norm_tvg_id, loose_norm_tvg_id
    except ImportError:
        return None, None, None
    index = EpgShareChannelIndex()
    if not index._load_cache():
        return None, norm_tvg_id, loose_norm_tvg_id
    return index, norm_tvg_id, loose_norm_tvg_id


def epgshare_exact_match(
    index,
    channel_name: str,
) -> Candidate | None:
    norm = normalize_name(channel_name)
    matched, conf, method = index.match(channel_name, norm, enable_fuzzy=False)
    if not matched:
        return None
    tier_conf = 0.92 if conf >= 0.9 else min(0.92, conf)
    return Candidate(matched, tier_conf, "exact_epgshare", method)


def epgshare_fuzzy_match(
    index,
    channel_name: str,
) -> Candidate | None:
    norm = normalize_name(channel_name)
    matched, conf, method = index.match(channel_name, norm, enable_fuzzy=True)
    if not matched:
        return None
    tier_conf = min(0.85, max(0.55, conf * 0.9))
    return Candidate(matched, tier_conf, "fuzzy_epgshare", method)


def norm_crosswalk_match(
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    seed_tvg_id: str,
    channel_name: str,
    channel_tags: list[str],
) -> Candidate | None:
    if index is None:
        return None
    for norm_fn in (norm_tvg_id_fn, loose_norm_tvg_id_fn):
        if norm_fn is None:
            continue
        norm = norm_fn(seed_tvg_id)
        if not norm:
            continue
        compact = norm.replace(" ", "")
        candidates = index._by_norm.get(norm) or index._by_compact.get(compact)
        if not candidates:
            continue
        exp = expected_countries(channel_name, channel_tags)
        filtered = []
        for cid in candidates:
            cc = tvg_country(cid)
            if exp and cc and cc not in exp:
                continue
            filtered.append(cid)
        if not filtered:
            continue
        pick = filtered[0]
        if pick == seed_tvg_id:
            continue
        return Candidate(pick, 0.8, "norm_crosswalk", f"norm_crosswalk:{norm_fn.__name__}")
    return None


def lookup_epgshare_feed(
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
    tvg_id: str,
) -> str | None:
    if index is None or not tvg_id:
        return None
    if index.has_tvg_id(tvg_id):
        return tvg_id
    cross = norm_crosswalk_match(index, norm_tvg_id_fn, loose_norm_tvg_id_fn, tvg_id, "", [])
    return cross.tvg_id if cross else None


def should_skip_channel(name: str, group_title: str) -> str | None:
    if re.search(r"\b18\+\b", name) or name.strip().startswith("18+"):
        return "skip: 18+ channel"
    if _EVENT_VS.search(name):
        return "skip: event stream (vs in name)"
    gt_low = (group_title or "").lower()
    name_low = name.lower()
    is_extra_247 = "24/7" in gt_low or "extra" in gt_low or _247_EXTRA.search(group_title or "")
    falcon_cdn = "falcon" in name_low or "cdn" in name_low
    if is_extra_247 and falcon_cdn:
        return "skip: 24/7 extra falcon CDN without linear brand"
    if _247_EXTRA.search(name) and falcon_cdn and "extra" in gt_low:
        return "skip: 24/7 extra falcon CDN without linear brand"
    return None


def hard_reject(
    channel_name: str,
    channel_tags: list[str],
    proposed: str,
    db: dict[str, ChannelMeta],
    *,
    cross_region_ok: bool = False,
) -> str | None:
    meta = db.get(proposed)
    exp = expected_countries(channel_name, channel_tags)
    mapped_cc = (meta.country if meta else None) or tvg_country(proposed)
    if exp and mapped_cc and mapped_cc not in exp:
        return f"country mismatch: expects {sorted(exp)}, got {mapped_cc}"

    ch_tok = tokens(channel_name)
    map_tok = tokens(meta.name if meta else proposed)
    if ch_tok and map_tok:
        inter = ch_tok & map_tok
        if inter:
            digit_only = all(t.isdigit() for t in inter) and len(inter) <= 1
            if digit_only and len(ch_tok) > 1:
                return f"digit-only overlap: {sorted(inter)}"

    label = (meta.name if meta else "") + proposed
    if exp & {"US", "UK"} and _GREEK_CYRILLIC.search(label):
        if not _GREEK_CYRILLIC.search(channel_name):
            return "greek/cyrillic tvg for US/UK channel"

    if exp & {"US", "UK"} and _ARABIC.search(label):
        if not _ARABIC.search(channel_name):
            s = score_candidate(channel_name, channel_tags, meta) if meta else 0.0
            if s < 0.35:
                return "arabic tvg on non-arabic US/UK channel"

    if not cross_region_ok and exp and mapped_cc and mapped_cc not in exp:
        return f"cross-region without reason: {mapped_cc} not in {sorted(exp)}"

    return None


def research_channel(
    channel_id: str,
    ch: dict,
    group_title: str,
    db: dict[str, ChannelMeta],
    name_index: dict[str, list[str]],
    token_index: dict[str, set[str]],
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> ResearchRow:
    name = (ch.get("channel_name") or ch.get("name") or "").strip()
    tags = enrich_tags(name, list(ch.get("tags") or []))
    if not group_title:
        group_title = group_title_from_tags(tags)
    row = ResearchRow(
        channel_id=channel_id,
        channel_name=name,
        tags=tags,
        group_title=group_title,
    )

    skip = should_skip_channel(name, group_title)
    if skip:
        row.tier = "skip"
        row.reject_reason = skip
        return row

    pipeline: list[Candidate] = []

    exact = exact_iptv_match(name, name_index, db)
    if exact:
        pipeline.append(exact)

    es_exact: Candidate | None = None
    if index is not None and (exact is None or exact.confidence < 0.95):
        es_exact = epgshare_exact_match(index, name)
        if es_exact:
            pipeline.append(es_exact)

    fuzzy: list[Candidate] = []
    if exact is None or exact.confidence < 0.95:
        fuzzy = fuzzy_iptv_candidates(name, tags, db, token_index)
        pipeline.extend(fuzzy[:5])

    best_so_far = max((c.confidence for c in pipeline), default=0.0)
    if index is not None and best_so_far < 0.75:
        es_fuzzy = epgshare_fuzzy_match(index, name)
        if es_fuzzy:
            pipeline.append(es_fuzzy)

    seed = None
    for cand in pipeline:
        if cand.tier in ("manual", "exact_iptv", "fuzzy_iptv"):
            seed = cand.tvg_id
            break
    if seed and index is not None:
        cross = norm_crosswalk_match(index, norm_tvg_id_fn, loose_norm_tvg_id_fn, seed, name, tags)
        if cross:
            pipeline.append(cross)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    row.candidates = [
        {"tvg_id": c.tvg_id, "confidence": round(c.confidence, 3), "tier": c.tier, "method": c.method}
        for c in ranked[:8]
    ]

    for pick in ranked:
        reject = hard_reject(
            name,
            tags,
            pick.tvg_id,
            db,
            cross_region_ok=(pick.tier == "manual"),
        )
        if reject:
            continue
        row.proposed_tvg_id = pick.tvg_id
        row.confidence = pick.confidence
        row.tier = pick.tier
        row.method = pick.method
        row.epgshare_feed_id = lookup_epgshare_feed(
            index, norm_tvg_id_fn, loose_norm_tvg_id_fn, pick.tvg_id
        )
        return row

    if ranked:
        row.reject_reason = hard_reject(name, tags, ranked[0].tvg_id, db) or "all candidates rejected"
        row.tier = "rejected"
    return row


def apply_high_confidence(rows: list[ResearchRow], min_confidence: float = 0.90) -> int:
    existing: dict[str, str] = {}
    if OVERRIDES_PATH.is_file():
        existing = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    changed = 0
    for row in rows:
        if not row.proposed_tvg_id or row.confidence < min_confidence:
            continue
        if row.tier in ("skip", "rejected", "no_match"):
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


def write_json(path: Path, rows: list[ResearchRow], meta: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        **meta,
        "channels": [asdict(r) for r in rows],
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[ResearchRow], min_review: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "channel_id",
        "channel_name",
        "group_title",
        "proposed_tvg_id",
        "epgshare_feed_id",
        "confidence",
        "tier",
        "method",
        "reject_reason",
        "tags",
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
                    "group_title": row.group_title,
                    "proposed_tvg_id": row.proposed_tvg_id or "",
                    "epgshare_feed_id": row.epgshare_feed_id or "",
                    "confidence": f"{row.confidence:.3f}",
                    "tier": row.tier,
                    "method": row.method,
                    "reject_reason": row.reject_reason,
                    "tags": "|".join(row.tags),
                    "top_candidates": ";".join(
                        f"{c['tvg_id']}({c['confidence']:.2f})" for c in row.candidates[:3]
                    ),
                }
            )



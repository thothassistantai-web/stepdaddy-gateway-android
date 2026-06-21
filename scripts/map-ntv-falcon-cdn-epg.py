#!/usr/bin/env python3
"""Map ntv.cx CDN/Falcon 24/7 channels to iptv-org tvg-ids and epgshare feeds."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from research_epg_common import (  # noqa: E402
    Candidate,
    OVERRIDES_PATH,
    REPORTS_DIR,
    TAG_COUNTRY,
    build_name_index,
    build_token_index,
    enrich_tags,
    epgshare_exact_match,
    epgshare_fuzzy_match,
    exact_iptv_match,
    expected_countries,
    fuzzy_iptv_candidates,
    hard_reject,
    load_channels_db,
    lookup_epgshare_feed,
    norm_crosswalk_match,
    normalize_name,
    try_load_epgshare,
)

NTV_CHANNELS_API = "https://www.ntv.cx/api/get-channels"
REPORT_PATH = REPORTS_DIR / "ntv_falcon_cdn_mappings.json"
NTV_SERVERS = frozenset({"cdnlive", "hesgoales"})
PROVIDER_BY_SERVER = {"cdnlive": "CDN", "hesgoales": "Falcon"}
HIGH_CONFIDENCE = 0.90

_STRIP_SUFFIX_RE = re.compile(r"\s+(?:cdn|falcon|mena)\s*$", re.I)

CHANNEL_CODE_MAP: dict[str, str] = {
    "gb": "UK",
    "uk": "UK",
    "us": "US",
    "ca": "CA",
    "de": "DE",
    "fr": "FR",
    "it": "IT",
    "es": "ES",
    "pt": "PT",
    "nl": "NL",
    "be": "BE",
    "pl": "PL",
    "cz": "CZ",
    "sk": "SK",
    "at": "AT",
    "ch": "CH",
    "au": "AU",
    "nz": "NZ",
    "dk": "DK",
    "no": "NO",
    "se": "SE",
    "fi": "FI",
    "gr": "GR",
    "hu": "HU",
    "ro": "RO",
    "bg": "BG",
    "hr": "HR",
    "rs": "RS",
    "ba": "BA",
    "ie": "IE",
    "tr": "TR",
    "ae": "AE",
    "eg": "EG",
    "qa": "QA",
    "sa": "SA",
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
    "me": "ME",
    "mk": "MK",
    "si": "SI",
}


@dataclass
class NtvMappingRow:
    channel_id: str
    server: str
    channel_name: str
    match_name: str
    channel_code: str
    provider_tag: str
    override_key: str
    proposed_tvg_id: str | None = None
    epgshare_feed_id: str | None = None
    suggested_epgshare_feed: str | None = None
    confidence: float = 0.0
    tier: str = "no_match"
    method: str = ""
    candidates: list[dict] = field(default_factory=list)
    reject_reason: str = ""
    expected_countries: list[str] = field(default_factory=list)


def strip_provider_suffix(name: str) -> str:
    return _STRIP_SUFFIX_RE.sub("", name.strip()).strip()


def gateway_override_key(raw_name: str, provider_tag: str) -> str:
    name = raw_name.strip()
    tag = provider_tag.strip()
    if not tag:
        return name
    if name.lower().endswith(tag.lower()):
        return name
    return f"{name} {tag}"


def country_from_channel_code(channel_code: str) -> str | None:
    code = channel_code.strip().lower()
    if not code:
        return None
    return CHANNEL_CODE_MAP.get(code, code.upper())


def tags_for_channel_code(channel_code: str) -> list[str]:
    country = country_from_channel_code(channel_code)
    if not country:
        return []
    for emoji, cc in TAG_COUNTRY.items():
        if cc == country:
            return [emoji]
    return []


def ntv_expected_countries(name: str, channel_code: str, tags: list[str]) -> set[str]:
    exp = expected_countries(name, tags)
    country = country_from_channel_code(channel_code)
    if country:
        exp.add(country)
    if re.search(r"\bmena\b", name, re.I):
        exp.update({"AE", "QA", "SA"})
    return exp


def suggest_regional_feed(tvg_id: str) -> str | None:
    """Primary epgshare regional feed code (DE1, UK1, BEIN1, ...)."""
    if not tvg_id:
        return None
    tl = tvg_id.lower()
    routes: list[tuple[tuple[str, ...], str]] = [
        ((".us2", ".us", "us_locals", "milb-", "fanduel", "draftkings"), "US2"),
        ((".uk", ".gb", ".ie"), "UK1"),
        ((".de",), "DE1"),
        ((".fr",), "FR1"),
        ((".it",), "IT1"),
        ((".es",), "ES1"),
        ((".pt",), "PT1"),
        ((".tr",), "TR1"),
        ((".ae",), "AE1"),
        ((".ba", ".hr", ".rs", ".me", ".mk", ".si"), "BA1"),
        ((".dk",), "DK1"),
        ((".no",), "NO1"),
        ((".se",), "SE1"),
        ((".fi",), "FI1"),
        ((".ca",), "CA2"),
        ((".au", ".nz"), "AU1"),
        ((".gr",), "GR1"),
        ((".mx",), "MX1"),
        ((".br",), "BR1"),
        ((".pl",), "PL1"),
        ((".nl",), "NL1"),
        ((".in",), "IN1"),
        ((".pk",), "PK1"),
        (("bein",), "BEIN1"),
    ]
    for markers, feed in routes:
        if any(marker in tl for marker in markers):
            return feed
    return "US2"


def fetch_ntv_channels(url: str = NTV_CHANNELS_API, timeout: float = 60.0) -> list[dict]:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/137.0",
            "Accept": "application/json, text/plain, */*",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if not payload.get("success"):
        raise RuntimeError("ntv.cx API returned success=false")
    channels = payload.get("channels") or []
    if not isinstance(channels, list):
        raise RuntimeError("ntv.cx API channels payload invalid")
    return channels


def filter_ntv_channels(channels: list[dict]) -> list[dict]:
    out: list[dict] = []
    for row in channels:
        server = (row.get("server") or "").strip()
        if server not in NTV_SERVERS:
            continue
        name = (row.get("channel_name") or "").strip()
        if not name:
            continue
        if server == "hesgoales" and not (row.get("channel_url") or "").strip():
            continue
        out.append(row)
    return out


def hard_reject_ntv(
    match_name: str,
    channel_code: str,
    tags: list[str],
    proposed: str,
    db,
) -> str | None:
    exp = ntv_expected_countries(match_name, channel_code, tags)
    base_reject = hard_reject(match_name, tags, proposed, db)
    if base_reject and "country mismatch" in base_reject:
        return base_reject
    if base_reject and "cross-region" in base_reject:
        return base_reject

    meta = db.get(proposed)
    mapped_cc = (meta.country if meta else None) or None
    if exp and mapped_cc and mapped_cc not in exp:
        return f"country gate: expects {sorted(exp)}, got {mapped_cc}"
    return base_reject


def map_ntv_channel(
    row: dict,
    db,
    name_index,
    token_index,
    index,
    norm_tvg_id_fn,
    loose_norm_tvg_id_fn,
) -> NtvMappingRow:
    server = (row.get("server") or "").strip()
    raw_name = (row.get("channel_name") or "").strip()
    channel_code = (row.get("channel_code") or "").strip().lower() or "us"
    if server == "hesgoales" and not (row.get("channel_code") or "").strip():
        channel_code = ""
    provider_tag = PROVIDER_BY_SERVER.get(server, server)
    match_name = strip_provider_suffix(raw_name)
    tags = enrich_tags(match_name, tags_for_channel_code(channel_code))
    exp = sorted(ntv_expected_countries(match_name, channel_code, tags))

    out = NtvMappingRow(
        channel_id=(row.get("channel_id") or "").strip(),
        server=server,
        channel_name=raw_name,
        match_name=match_name,
        channel_code=channel_code,
        provider_tag=provider_tag,
        override_key=gateway_override_key(raw_name, provider_tag),
        expected_countries=exp,
    )

    pipeline: list[Candidate] = []
    exact = exact_iptv_match(match_name, name_index, db)
    if exact:
        pipeline.append(exact)

    if index is not None and (exact is None or exact.confidence < 0.95):
        es_exact = epgshare_exact_match(index, match_name)
        if es_exact:
            pipeline.append(es_exact)

    if exact is None or exact.confidence < 0.95:
        fuzzy = fuzzy_iptv_candidates(match_name, tags, db, token_index)
        pipeline.extend(fuzzy[:5])

    best_so_far = max((c.confidence for c in pipeline), default=0.0)
    if index is not None and best_so_far < 0.75:
        es_fuzzy = epgshare_fuzzy_match(index, match_name)
        if es_fuzzy:
            pipeline.append(es_fuzzy)

    seed = None
    for cand in pipeline:
        if cand.tier in ("manual", "exact_iptv", "fuzzy_iptv"):
            seed = cand.tvg_id
            break
    if seed and index is not None:
        cross = norm_crosswalk_match(index, norm_tvg_id_fn, loose_norm_tvg_id_fn, seed, match_name, tags)
        if cross:
            pipeline.append(cross)

    best_by_id: dict[str, Candidate] = {}
    for cand in pipeline:
        prev = best_by_id.get(cand.tvg_id)
        if prev is None or cand.confidence > prev.confidence:
            best_by_id[cand.tvg_id] = cand
    ranked = sorted(best_by_id.values(), key=lambda c: c.confidence, reverse=True)
    out.candidates = [
        {"tvg_id": c.tvg_id, "confidence": round(c.confidence, 3), "tier": c.tier, "method": c.method}
        for c in ranked[:8]
    ]

    for pick in ranked:
        reject = hard_reject_ntv(match_name, channel_code, tags, pick.tvg_id, db)
        if reject:
            continue
        out.proposed_tvg_id = pick.tvg_id
        out.confidence = pick.confidence
        out.tier = pick.tier
        out.method = pick.method
        out.epgshare_feed_id = lookup_epgshare_feed(
            index, norm_tvg_id_fn, loose_norm_tvg_id_fn, pick.tvg_id
        )
        out.suggested_epgshare_feed = suggest_regional_feed(pick.tvg_id)
        return out

    if ranked:
        out.reject_reason = (
            hard_reject_ntv(match_name, channel_code, tags, ranked[0].tvg_id, db)
            or "all candidates rejected"
        )
        out.tier = "rejected"
    return out


def merge_high_confidence_overrides(rows: list[NtvMappingRow], min_confidence: float = HIGH_CONFIDENCE) -> int:
    existing: dict[str, str] = {}
    if OVERRIDES_PATH.is_file():
        existing = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    changed = 0
    for row in rows:
        if not row.proposed_tvg_id or row.confidence < min_confidence:
            continue
        if row.tier in ("rejected", "no_match"):
            continue
        key = row.override_key
        if existing.get(key) == row.proposed_tvg_id:
            continue
        existing[key] = row.proposed_tvg_id
        changed += 1
    OVERRIDES_PATH.write_text(
        json.dumps(dict(sorted(existing.items(), key=lambda kv: kv[0].lower())), indent=2, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    return changed


def write_report(path: Path, rows: list[NtvMappingRow], meta: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        **meta,
        "channels": [asdict(r) for r in rows],
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Map ntv.cx CDN/Falcon channels to EPG tvg-ids")
    parser.add_argument(
        "--output",
        type=Path,
        default=REPORT_PATH,
        help="JSON research report path",
    )
    parser.add_argument(
        "--apply-high",
        action="store_true",
        help=f"Merge mappings with confidence >= {HIGH_CONFIDENCE} into epg_name_overrides.json",
    )
    parser.add_argument("--min-confidence", type=float, default=HIGH_CONFIDENCE)
    parser.add_argument("--api-url", default=NTV_CHANNELS_API)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    print("Fetching ntv.cx catalog...", file=sys.stderr)
    raw = fetch_ntv_channels(args.api_url)
    channels = filter_ntv_channels(raw)

    print("Loading iptv-org index...", file=sys.stderr)
    db = load_channels_db()
    name_index = build_name_index(db)
    token_index = build_token_index(db)
    print("Loading epgshare index...", file=sys.stderr)
    index, norm_tvg_id_fn, loose_norm_tvg_id_fn = try_load_epgshare()

    rows = [
        map_ntv_channel(row, db, name_index, token_index, index, norm_tvg_id_fn, loose_norm_tvg_id_fn)
        for row in channels
    ]

    mapped = [r for r in rows if r.proposed_tvg_id]
    high = [r for r in mapped if r.confidence >= args.min_confidence]

    meta = {
        "source": args.api_url,
        "servers": sorted(NTV_SERVERS),
        "epgshare": "loaded" if index is not None else "skipped",
        "total_channels": len(rows),
        "mapped_count": len(mapped),
        "high_confidence_count": len(high),
        "min_confidence": args.min_confidence,
    }
    write_report(args.output, rows, meta)

    print(f"total_channels={meta['total_channels']}")
    print(f"mapped_count={meta['mapped_count']}")
    print(f"high_confidence_count={meta['high_confidence_count']}")
    print(f"Wrote {args.output}")

    sample = high[:10] if high else mapped[:10]
    if sample:
        print("sample_mappings:")
        for row in sample:
            print(
                f"  {row.override_key!r} -> {row.proposed_tvg_id} "
                f"({row.confidence:.2f}, feed={row.suggested_epgshare_feed})"
            )

    if args.apply_high:
        n = merge_high_confidence_overrides(rows, min_confidence=args.min_confidence)
        print(f"Applied {n} high-confidence overrides to {OVERRIDES_PATH}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

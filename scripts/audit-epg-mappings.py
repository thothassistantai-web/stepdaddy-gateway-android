#!/usr/bin/env python3
"""Audit and fix channelId -> tvg-id EPG mappings."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


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
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
OVERRIDES_PATH = WEB_ROOT / "app" / "epg_overrides.json"
CACHE_PATH = WEB_ROOT / "app" / "dlhd_channels_cache.json"
CHANNELS_DB = ASSETS / "channels_db_cache.csv"

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


def normalize_name(name: str) -> str:
    s = name.lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


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
    # 5 USA is a UK channel despite the US tag in DaddyLive metadata.
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
class AuditRow:
    channel_id: str
    channel_name: str
    old_tvg: str
    new_tvg: str | None
    reason: str
    confidence: float


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
        # penalize single-digit-only overlap
        if inter == {x for x in inter if x.isdigit() and len(x) == 1} and len(inter) <= 1:
            jaccard *= 0.2
        best = max(best, jaccard)
    exp = expected_countries(channel_name, channel_tags)
    if exp and meta.country and meta.country in exp:
        best += 0.35
    elif exp and meta.country and meta.country not in exp:
        best -= 0.5
    return best


def best_match(
    channel_name: str,
    channel_tags: list[str],
    db: dict[str, ChannelMeta],
    name_index: dict[str, list[str]],
) -> tuple[str | None, float]:
    if channel_name in MANUAL and MANUAL[channel_name] in db:
        return MANUAL[channel_name], 0.99
    key = normalize_name(channel_name)
    if key in name_index:
        cands = name_index[key]
        if len(cands) == 1:
            return cands[0], 0.95
    exp = expected_countries(channel_name, channel_tags)
    scored: list[tuple[float, str]] = []
    for cid, meta in db.items():
        if exp and meta.country and meta.country not in exp:
            continue
        s = score_candidate(channel_name, channel_tags, meta)
        if s >= 0.45:
            scored.append((s, cid))
    if not scored:
        return None, 0.0
    scored.sort(reverse=True)
    return scored[0][1], scored[0][0]


def is_suspect(
    channel_name: str,
    channel_tags: list[str],
    old_tvg: str,
    db: dict[str, ChannelMeta],
) -> tuple[bool, str]:
    meta = db.get(old_tvg)
    if meta is None:
        return True, "tvg_id not in channels_db"

    exp = expected_countries(channel_name, channel_tags)
    mapped_cc = meta.country or tvg_country(old_tvg)
    if exp and mapped_cc and mapped_cc not in exp:
        return True, f"country mismatch: channel expects {sorted(exp)}, mapped {mapped_cc}"

    ch_tok = tokens(channel_name)
    map_tok = tokens(meta.name)
    if ch_tok and map_tok:
        inter = ch_tok & map_tok
        if not inter:
            return True, f"no token overlap: '{channel_name}' vs '{meta.name}'"
        digit_only = all(t.isdigit() for t in inter) and len(inter) <= 1
        if digit_only and len(ch_tok) > 1:
            return True, f"weak digit-only overlap: {inter}"

    # Arabic mapped tvg for Latin channel name
    if re.search(r"[\u0600-\u06FF]", old_tvg + meta.name):
        if not re.search(r"[\u0600-\u06FF]", channel_name):
            if score_candidate(channel_name, channel_tags, meta) < 0.35:
                return True, "arabic tvg on non-arabic channel name"

    return False, ""


def load_dlhd() -> dict[str, dict]:
    data = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    return {str(c["id"]): c for c in data.get("channels", [])}


def audit_chunk(
    mapping: dict[str, str],
    dlhd: dict[str, dict],
    db: dict[str, ChannelMeta],
    name_index: dict[str, list[str]],
    *,
    id_min: int | None = None,
    id_max: int | None = None,
) -> list[AuditRow]:
    rows: list[AuditRow] = []
    for cid, old_tvg in sorted(mapping.items(), key=lambda x: int(x[0]) if x[0].isdigit() else 0):
        if id_min is not None or id_max is not None:
            if not cid.isdigit():
                continue
            n = int(cid)
            if id_min is not None and n < id_min:
                continue
            if id_max is not None and n > id_max:
                continue
        ch = dlhd.get(cid)
        if not ch:
            continue
        name = ch.get("name") or ""
        tags = ch.get("tags") or []
        suspect, reason = is_suspect(name, tags, old_tvg, db)
        if not suspect:
            continue
        new_tvg, conf = best_match(name, tags, db, name_index)
        if new_tvg and new_tvg != old_tvg and conf >= 0.55:
            rows.append(AuditRow(cid, name, old_tvg, new_tvg, reason, conf))
        else:
            rows.append(AuditRow(cid, name, old_tvg, None, reason + " (no confident fix)", conf))
    return rows


SAFE_CROSS_REGION_REASONS = (
    "country mismatch",
    "arabic tvg on non-arabic channel name",
    "tvg_id not in channels_db",
)


def is_risky_cross_region(
    row: AuditRow,
    db: dict[str, ChannelMeta],
) -> bool:
    old_meta = db.get(row.old_tvg)
    new_meta = db.get(row.new_tvg or "")
    old_cc = (old_meta.country if old_meta else None) or tvg_country(row.old_tvg)
    new_cc = (new_meta.country if new_meta else None) or tvg_country(row.new_tvg or "")
    if not old_cc or not new_cc or old_cc == new_cc:
        return False
    if any(marker in row.reason for marker in SAFE_CROSS_REGION_REASONS):
        return False
    return True


def apply_fixes(
    rows: list[AuditRow],
    db: dict[str, ChannelMeta],
    *,
    min_confidence: float,
) -> tuple[int, list[AuditRow]]:
    overrides = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    changed = 0
    deferred: list[AuditRow] = []
    for row in rows:
        if not row.new_tvg:
            continue
        if row.confidence < min_confidence:
            deferred.append(row)
            continue
        if is_risky_cross_region(row, db):
            deferred.append(row)
            continue
        if overrides.get(row.channel_name) == row.new_tvg:
            continue
        overrides[row.channel_name] = row.new_tvg
        changed += 1
    OVERRIDES_PATH.write_text(json.dumps(overrides, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return changed, deferred


def export_mapping() -> None:
    import subprocess

    subprocess.run([str(ROOT / "scripts" / "export-epg-mapping.sh")], check=True)


def sync_android_shell_overrides() -> None:
    shell_path = WEB_ROOT / "android-shell" / "app" / "src" / "main" / "assets" / "stepdaddy" / "app" / "epg_overrides.json"
    if shell_path.parent.exists():
        shell_path.write_text(OVERRIDES_PATH.read_text(encoding="utf-8"), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunk", type=int, default=None, help="1-9 chunk index")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--min-confidence", type=float, default=0.6)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    mapping_data = json.loads(MAPPING_PATH.read_text(encoding="utf-8"))
    mapping = mapping_data.get("mapping", {})
    dlhd = load_dlhd()
    db = load_channels_db()
    name_index = build_name_index(db)

    id_min = id_max = None
    if args.chunk is not None:
        numeric_ids = sorted(int(k) for k in mapping if k.isdigit())
        if not numeric_ids:
            print("no numeric ids", file=sys.stderr)
            return 1
        lo, hi = numeric_ids[0], numeric_ids[-1]
        span = hi - lo + 1
        size = (span + 8) // 9
        idx = max(1, min(9, args.chunk)) - 1
        id_min = lo + idx * size
        id_max = lo + (idx + 1) * size - 1 if idx < 8 else hi

    scanned = len(mapping)
    if id_min is not None or id_max is not None:
        scanned = sum(
            1
            for cid in mapping
            if cid.isdigit() and (id_min is None or int(cid) >= id_min) and (id_max is None or int(cid) <= id_max)
        )

    rows = audit_chunk(mapping, dlhd, db, name_index, id_min=id_min, id_max=id_max)
    fixable = [r for r in rows if r.new_tvg and r.confidence >= args.min_confidence]
    would_defer = [
        r
        for r in rows
        if r.new_tvg and (r.confidence < args.min_confidence or is_risky_cross_region(r, db))
    ]

    if args.json:
        print(
            json.dumps(
                {
                    "scanned": scanned,
                    "suspect": len(rows),
                    "fixable": len(fixable),
                    "deferred": len(would_defer),
                    "rows": [r.__dict__ for r in rows],
                },
                indent=2,
                ensure_ascii=False,
            )
        )
    else:
        print(
            f"scanned={scanned} suspect={len(rows)} "
            f"fixable(>={args.min_confidence:.2f})={len(fixable)} deferred={len(would_defer)} "
            f"chunk={args.chunk} range={id_min}-{id_max}"
        )
        for r in rows:
            fix = f" -> {r.new_tvg} ({r.confidence:.2f})" if r.new_tvg else ""
            defer = ""
            if r.new_tvg and (r.confidence < args.min_confidence or is_risky_cross_region(r, db)):
                defer = " [DEFERRED]"
            print(f"  [{r.channel_id}] {r.channel_name}: {r.old_tvg}{fix}{defer} | {r.reason}")

    if args.apply:
        n, deferred = apply_fixes(fixable, db, min_confidence=args.min_confidence)
        print(f"applied {n} override fixes; deferred {len(deferred)}")
        sync_android_shell_overrides()
        export_mapping()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

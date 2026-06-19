#!/usr/bin/env python3
"""Fix epg_overrides.json country mismatches and re-export channel_epg_map.json."""

from __future__ import annotations

import csv
import json
import os
import re
import subprocess
import sys
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
OVERRIDES = WEB_ROOT / "app" / "epg_overrides.json"
CACHE = WEB_ROOT / "app" / "dlhd_channels_cache.json"
CHANNELS_DB = ASSETS / "channels_db_cache.csv"

# Manual high-confidence corrections (channel name -> tvg_id)
MANUAL: dict[str, str] = {
    "5 USA": "5USA.uk",
    "Channel 5 UK": "Channel5.uk",
    "ABC USA": "ABC.us",
    "CBS USA": "CBS.us",
    "NBC USA": "NBC.us",
    "FOX USA": "Fox.us",
    "ESPN2 USA": "ESPN2.us",
    "FX USA": "FX.us",
    "PBS USA": "PBS.us",
    "ION USA": "IonTV.us",
    "VH1 USA": "VH1.us",
    "Dave": "Dave.uk",
    "DAZN 1 UK": "DAZN1.uk",
    "Canal+ Sport France": "CanalPlusSport.fr",
    "Canal+ Sport Poland": "CanalPlusSport.pl",
    "Canal+ Sport 2 Poland": "CanalPlusSport2.pl",
    "Canal+ Sport 3 Poland": "CanalPlusSport3.pl",
    "Canal+ Sport 5 Poland": "CanalPlusSport5.pl",
    "Eleven Sports 1 Poland": "ElevenSports1.pl",
    "Eleven Sports 2 Poland": "ElevenSports2.pl",
    "Eleven Sports 3 Poland": "ElevenSports3.pl",
    "Eleven Sports 4 Poland": "ElevenSports4.pl",
    "Eleven Sports 1 Portugal": "ElevenSports1.pt",
    "Eleven Sports 2 Portugal": "ElevenSports2.pt",
    "Eleven Sports 3 Portugal": "ElevenSports3.pt",
    "Eleven Sports 4 Portugal": "ElevenSports4.pt",
    "Sport TV1 Portugal": "SportTV1.pt",
    "Sport TV2 Portugal": "SportTV2.pt",
    "Sport TV3 Portugal": "SportTV3.pt",
    "Sport TV4 Portugal": "SportTV4.pt",
    "Sport TV5 Portugal": "SportTV5.pt",
    "Sport TV6 Portugal": "SportTV6.pt",
    "SporTV2 Brasil": "SporTV2.br",
    "SporTV3 Brasil": "SporTV3.br",
    "ESPN Brasil": "ESPN.br",
    "ESPN3 Brasil": "ESPN3.br",
    "ESPN4 Brasil": "ESPN4.br",
    "Premier Brasil": "PremiereClubes.br",
    "Bandsports Brasil": "BandSports.br",
    "Match TV Russia": "Match.ru",
    "Match Football 1 Russia": "MatchFutbol1.ru",
    "Match Football 2 Russia": "MatchFutbol2.ru",
    "Match Football 3 Russia": "MatchFutbol3.ru",
    "Sport 1 Israel": "Sport1.il",
    "Sport 2 Israel": "Sport2.il",
    "Sport 3 Israel": "Sport3.il",
    "Sport 4 Israel": "Sport4.il",
    "Sport 5 Israel": "5Sport.il",
    "Sport 5 PLUS Israel": "5Plus.il",
    "Sport 5 Live Israel": "5Live.il",
    "Sport 5 Star Israel": "5Stars.il",
    "Sport 5 Gold Israel": "5Gold.il",
    "TV3 Max Denmark": "TV3Max.dk",
    "DR1 Denmark": "DR1.dk",
    "DR2 Denmark": "DR2.dk",
    "Sky Sports 1 DE": "SkySport1.de",
    "ITV 2 UK": "ITV2.uk",
    "ITV 3 UK": "ITV3.uk",
    "ITV 4 UK": "ITV4.uk",
    "AHC (American Heroes Channel)": "AmericanHeroesChannel.us",
    "Unimas": "UniMas.us",
    "Telemundo": "Telemundo.us",
    "Univision": "Univision.us",
    "OnTime Sports": "OnTimeSports.eg",  # keep if Egyptian sports; else drop
}

TAG_CC = {"🇬🇧": "UK", "🇺🇸": "US", "🇩🇪": "DE", "🇫🇷": "FR", "🇮🇹": "IT", "🇪🇸": "ES",
          "🇹🇷": "TR", "🇦🇪": "AE", "🇪🇬": "EG", "🇨🇦": "CA", "🇧🇷": "BR", "🇵🇱": "PL",
          "🇮🇱": "IL", "🇷🇺": "RU", "🇩🇰": "DK", "🇵🇹": "PT", "🇮🇳": "IN", "🇳🇱": "NL",
          "🇨🇿": "CZ", "🇦🇹": "AT", "🇮🇪": "IE", "🇶🇦": "QA", "🇧🇦": "BA", "🇱🇻": "LV"}

NAME_CC = [
    (re.compile(r"\bUK\b", re.I), "UK"),
    (re.compile(r"\bUSA\b", re.I), "US"),
    (re.compile(r"\bUS\b", re.I), "US"),
    (re.compile(r"\bFrance\b", re.I), "FR"),
    (re.compile(r"\bGermany\b|\bDE\b", re.I), "DE"),
    (re.compile(r"\bItaly\b", re.I), "IT"),
    (re.compile(r"\bSpain\b", re.I), "ES"),
    (re.compile(r"\bTurkey\b", re.I), "TR"),
    (re.compile(r"\bEgypt\b", re.I), "EG"),
    (re.compile(r"\bUAE\b", re.I), "AE"),
    (re.compile(r"\bCanada\b", re.I), "CA"),
    (re.compile(r"\bBrasil\b", re.I), "BR"),
    (re.compile(r"\bPoland\b", re.I), "PL"),
    (re.compile(r"\bIsrael\b", re.I), "IL"),
    (re.compile(r"\bRussia\b", re.I), "RU"),
    (re.compile(r"\bDenmark\b", re.I), "DK"),
    (re.compile(r"\bPortugal\b", re.I), "PT"),
    (re.compile(r"\bIndia\b", re.I), "IN"),
    (re.compile(r"\bIreland\b", re.I), "IE"),
    (re.compile(r"\bNetherlands\b", re.I), "NL"),
    (re.compile(r"\bCzech\b", re.I), "CZ"),
    (re.compile(r"\bAustria\b", re.I), "AT"),
]


def norm(s: str) -> str:
    s = s.lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def hints(name: str, tags: list[str]) -> set[str]:
    out: set[str] = set()
    for pat, cc in NAME_CC:
        if pat.search(name):
            out.add(cc)
    for t in tags:
        for em, cc in TAG_CC.items():
            if em in t:
                out.add(cc)
    # 5 USA is a UK channel despite US tag
    if name.strip() == "5 USA":
        out.add("UK")
        out.discard("US")
    return out


def tvg_cc(tvg: str, db_country: str = "") -> str | None:
    if db_country:
        return db_country.upper()
    m = re.search(r"\.([a-z]{2,3})(?:\d)?$", tvg.lower())
    return m.group(1).upper() if m else None


def load_db() -> dict[str, dict]:
    out = {}
    with CHANNELS_DB.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            out[row["id"]] = row
    return out


def build_index(db: dict[str, dict]) -> dict[str, list[str]]:
    idx: dict[str, list[str]] = {}
    for cid, row in db.items():
        for label in [row["name"], *row.get("alt_names", "").split(";")]:
            label = label.strip()
            if label:
                idx.setdefault(norm(label), []).append(cid)
    return idx


def best_tvg(name: str, tags: list[str], db: dict[str, dict], idx: dict[str, list[str]]) -> str | None:
    if name in MANUAL:
        return MANUAL[name]
    key = norm(name)
    exp = hints(name, tags)
    if key in idx:
        for cid in idx[key]:
            row = db[cid]
            if not exp or row.get("country", "").upper() in exp:
                return cid
    # token search
    ch_tok = {t for t in key.split() if len(t) > 1}
    best = (0.0, None)
    for cid, row in db.items():
        cc = row.get("country", "").upper()
        if exp and cc and cc not in exp:
            continue
        labels = [row["name"], *row.get("alt_names", "").split(";")]
        for label in labels:
            lt = {t for t in norm(label).split() if len(t) > 1}
            if not ch_tok or not lt:
                continue
            inter = ch_tok & lt
            if not inter:
                continue
            if all(x.isdigit() for x in inter) and len(inter) <= 1:
                continue
            score = len(inter) / len(ch_tok | lt)
            if exp and cc in exp:
                score += 0.3
            if score > best[0]:
                best = (score, cid)
    return best[1] if best[0] >= 0.55 else None


def main() -> int:
    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8"))
    dlhd = {c["name"]: c for c in json.loads(CACHE.read_text())["channels"]}
    db = load_db()
    idx = build_index(db)

    changes: list[tuple[str, str, str]] = []
    for name, old in list(overrides.items()):
        ch = dlhd.get(name, {})
        tags = ch.get("tags", [])
        exp = hints(name, tags)
        old_cc = tvg_cc(old, db.get(old, {}).get("country", ""))
        if re.search(r"[\u0600-\u06FF]", old) and not re.search(r"[\u0600-\u06FF]", name):
            if name != "OnTime Sports":
                new = best_tvg(name, tags, db, idx)
                if new and new != old:
                    changes.append((name, old, new))
                continue
        if not exp or not old_cc:
            continue
        if old_cc in exp:
            continue
        new = best_tvg(name, tags, db, idx)
        if new and new != old:
            changes.append((name, old, new))

    for name, old, new in changes:
        overrides[name] = new

    OVERRIDES.write_text(json.dumps(overrides, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"updated {len(changes)} overrides in {OVERRIDES}")
    for name, old, new in changes[:50]:
        print(f"  {name}: {old} -> {new}")
    if len(changes) > 50:
        print(f"  ... +{len(changes)-50} more")

    subprocess.run([str(ROOT / "scripts" / "export-epg-mapping.sh")], check=True)

    # verify 5 USA
    mapping = json.loads((ASSETS / "channel_epg_map.json").read_text())["mapping"]
    print("5 USA mapping:", mapping.get("360"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

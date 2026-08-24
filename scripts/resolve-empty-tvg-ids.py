#!/usr/bin/env python3
"""Resolve empty playlist tvg-ids with HIGH/MEDIUM confidence only.

Inventories live (or /tmp) playlist empties, applies curated ACCNX-style proxies
plus country-aware channels_db matches, writes channel_epg_map.json /
epg_name_overrides.json, and emits reports/empty-tvg-id-resolution.md.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import urllib.request
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
OVERRIDES_PATH = ASSETS / "epg_name_overrides.json"
CHANNELS_DB = ASSETS / "channels_db_cache.csv"
BRIDGE_PATH = ASSETS / "epg_id_bridge.json"
REPORT_PATH = ROOT / "reports" / "empty-tvg-id-resolution.md"
JSON_REPORT = ROOT / "reports" / "empty-tvg-id-resolution.json"
PROG_IDS = Path("/tmp/epg-audit-prog-ids.txt")
WOFTV_US = Path("/tmp/woftv-us.json")

SUPERSCRIPTS = re.compile(r"[\u1d2c-\u1d61\u2070-\u209f\u02b0-\u02ff]+")
COUNTRY_PREFIX = re.compile(
    r"^(US|UK|CA|INT|ES|DE|FR|IT|AU|NZ|MX|BR|CZ|CO|HR|IL|GR|TR|PL|NL|BE|PT|"
    r"SE|NO|DK|FI|AT|CH|IE|AR|CL|PE|VE|JP|KR|IN|PK|AE|EG|QA|SA|RO|RU|AL|SK|"
    r"HU|FI|AZ|RS|SI|EE|IS|UY|PE):\s*",
    re.I,
)
COUNTRY_WORDS = re.compile(
    r"\b(usa|united states|uk|britain|england|canada|spain|france|germany|"
    r"italy|australia|austrialia|austrelia|austrlia|mexico|colombia|croatia|"
    r"israel|israell|greece|turkey|poland|netherlands|belgium|belgim|"
    r"portugal|poutugal|sweden|norway|denmark|finland|austria|switzerland|"
    r"ireland|argentina|chile|romania|russia|albania|slovakia|hungary|"
    r"azerbaijan|serbia|slovenia|estonia|iceland|island|ukraine|ukrain|"
    r"hungry|peru|morocco|kazakhstan|kazach|moldova|armenia)\b",
    re.I,
)
ADULT_RE = re.compile(
    r"\b(bangbros|eporner|beeg|drtuber|motherless|naughtyamerica|pornhd|"
    r"porntrex|redtube|spankbang|tube8|xhamster|xnxx|xvideos|youporn|"
    r"sexvid|tnaflix)\b",
    re.I,
)
EVENT_RE = re.compile(
    r"\b(ppv|event stream|event streams|event sd|live stream|soccer stream|"
    r"tennis stream|tennis hd stream|court no|court \d|center court|"
    r"suzanne lenglen|simonne mathieu|fight night|game pass|gamecenter|"
    r"league pass|season pass|peacock feed|nfl game|nhl game|"
    r"dirttrackppv|flo ?racing|netflix ppv|prime video|appletv|"
    r"canal\+ live|ligue1\+|oneplay md|voyo special|magenta sport 30|"
    r"wnba|ufc stream|wwe ppv|spfl feed|morocco stream|uruguay stream|"
    r"ontime sports stream|migu tv|rcti|winplus|temmis|nlse|cz sports|"
    r"kszwtv|fame.?mma|fast sports armenia|darts borad|baloncesto|"
    r"mundial|box hd|ppp hd|backup|baclup|sd stream)\b",
    re.I,
)
PEG_RE = re.compile(
    r"\b(public access|peg|community (media|tv)|government|city council)\b",
    re.I,
)

# ACCNX-style and unambiguous curated proxies (channelId -> tvg, tier, reason)
CURATED_ID: dict[str, tuple[str, str, str]] = {
    # ACCNX already in map; keep + name override
    "242": ("ACCNetwork.us", "HIGH", "accnx_proxy_sibling"),
    "1076": ("ACCNetwork.us", "HIGH", "accnx_proxy_sibling"),
    "664": ("ACCNetwork.us", "HIGH", "accnx_proxy_sibling"),
    # BTN+ / Big Ten Plus → Big Ten Network
    "207": ("BigTenNetwork.us", "MEDIUM", "btn_plus_proxy_like_accnx"),
    "214": ("BigTenNetwork.us", "MEDIUM", "big_ten_plus_proxy_like_accnx"),
    # SECN+ / SECNX → SEC Network
    "183": ("SECNetwork.us", "MEDIUM", "secn_plus_proxy_like_accnx"),
    "249": ("SECNetwork.us", "MEDIUM", "secn_plus_proxy_like_accnx"),
    "1166": ("SECNetwork.us", "MEDIUM", "secnx_proxy_like_accnx"),
    # Racer TV → Racer Network
    "646": ("RacerNetwork.us", "MEDIUM", "racer_tv_to_racer_network"),
    # beIN US (typos)
    "3038": ("beINSportsUSA.us", "HIGH", "bein_sport_usa_exact"),
    "3039": ("beINSportsUSA.us", "HIGH", "bein_spors_typo_usa"),
    # clear network ids
    "245": ("CaracolTV.co", "HIGH", "caracol_colombia"),
    "3014": ("ProSieben.de", "HIGH", "pro_sieben"),
    "6414": ("Sport1.de", "HIGH", "sport1_de"),
    "3099": ("DAZN.1.de", "MEDIUM", "dazn_de_generic_to_dazn1"),
    "4267": ("DAZN.1.de", "HIGH", "dazn1_de"),
    "3017": ("DAZN.1.de", "HIGH", "dazn1_de_alt"),
    "3018": ("DAZN.2.de", "HIGH", "dazn2_de_alt"),
    "3051": ("beIN.SPORTS.1.fr", "HIGH", "bein_sports_1_fr_bridge"),
    "3028": ("Deportes2porMovistarPlus.es", "HIGH", "m_plus_deportes_2"),
    "184": ("Deportes8porMovistarPlus.es", "HIGH", "movistar_deportes_8"),
    "3027": ("DeportesporMovistarPlusPlus.es", "MEDIUM", "movistar_plus_spain_generic"),
    "536": ("La2.es", "HIGH", "tve_la_2"),
    "533": ("La1.es", "HIGH", "tve_la_1"),
    "3093": ("Gol.es", "HIGH", "gol_spain"),
    "3055": ("RMCSport1.fr", "MEDIUM", "rmc_sport_france_generic"),
    "3023": ("CosmoteSport1.gr", "MEDIUM", "cosmote_sport_greece_generic"),
    "12": ("ERT2.gr", "HIGH", "ert_2_greece"),
    "169": ("SportKlub5.nl", "MEDIUM", "sportklub_5_hr_nl_proxy"),
    "967": ("SportKlub.nl", "MEDIUM", "sportklub_7_hr_nl_proxy_no_sk7"),
    "165": ("SportKlub.nl", "MEDIUM", "sportklub_8_hr_nl_proxy_no_sk8"),
    "258": ("SportKlub.nl", "MEDIUM", "sportklub_9_hr_nl_proxy_no_sk9"),
    "224": ("SportKlub.nl", "MEDIUM", "sportklub_10_hr_nl_proxy"),
    "199": ("SportKlub.nl", "MEDIUM", "sportklub_10_hr_nl_proxy"),
    "55": ("ZonaDAZN2.it", "HIGH", "dazn_zona_2"),
    "877": ("ZonaDAZN.it", "HIGH", "zona_dazn"),
    "262": ("RSILa2.ch", "HIGH", "rsi_la_2"),
    "3046": ("SkySportMotoGP.it", "HIGH", "sky_sport_motogp"),
    "1926": ("ESPN2LatinAmerica.us", "MEDIUM", "espn2_mexico_latam_proxy"),
    "3060": ("FightKlub.pl", "HIGH", "fightklub_poland"),
    "17": ("SportTV7.pt", "HIGH", "sport_tv7_portugal"),
    "2906": ("SportTV5.pt", "HIGH", "sport_tv5_portugal_typo"),
    "3021": ("SportTV1.pt", "HIGH", "sport_tv1_portugal"),
    "253": ("Antena1.ro", "HIGH", "antena_1_romania"),
    "168": ("MatchIgra.ru", "HIGH", "match_igra"),
    "213": ("MatchIgra.ru", "HIGH", "match_igra_cyrillic"),
    "27": ("SVT24.se", "HIGH", "svt_24"),
    "257": ("TV4.se", "HIGH", "tv4_sweden"),
    "3076": ("VSport1.se", "HIGH", "v_sport_1_se"),
    "3078": ("VSportPremium.se", "HIGH", "v_sport_premium"),
    "2": ("TabiiSpor6.tr", "HIGH", "tabii_spor_6"),
    "3088": ("NPO3.nl", "HIGH", "npo_3"),
    "5002": ("SkySport1.nz", "HIGH", "sky_sport_1_nz"),
    "741": ("TenSportsPakistan.pk", "HIGH", "ten_sports_pk"),
    "791": ("MGMPlus.us", "HIGH", "mgm_plus_epix"),
    "239": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "29": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "164": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "76": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "30": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "69": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "162": ("MagentaSport.de", "MEDIUM", "magenta_sport_overflow_proxy"),
    "3011": ("ORF1.at", "HIGH", "orf_eins"),
    "5016": ("NBCUniverso.us", "HIGH", "nbc_universo"),
    "10": ("AlIraqiaSport.iq", "HIGH", "al_iraqiya_sports"),
    "218": ("JOJSport.sk", "HIGH", "joj_sport_sk"),
    "1052": ("JOJSport.sk", "HIGH", "joj_sport_sk"),
    "24": ("JOJSport.sk", "HIGH", "joj_sport_slovakia"),
    "243": ("IctimaiTV.az", "HIGH", "ictimai_azerbaijan"),
    "244": ("Tipik.be", "HIGH", "tipik_belgium"),
    "3049": ("LaUne.be", "HIGH", "la_une_belgium"),
    "237": ("TVKlan.al", "HIGH", "tv_klan_albania"),
    "3062": ("SuperSport2.al", "HIGH", "supersport_2_albania"),
    "3063": ("Sport1.al", "MEDIUM", "sport_2_albania_ambiguous_skip_if_missing"),
    "202": ("TV2Direkte.no", "HIGH", "tv2_direkte_norway"),
    "182": ("TV2Sport2.no", "HIGH", "tv2_sport_2_norway"),
    "3067": ("VSport1.no", "MEDIUM", "v_sport_1_norway_if_exists"),
    "3066": ("M4Sport.hu", "HIGH", "m4_sports_hungary"),
    "3070": ("MTVUrheilu1.fi", "HIGH", "mtv_urheilu_1"),
    "254": ("RTS1.rs", "MEDIUM", "rts_1_serbia_if_exists"),
    "255": ("RTVSSport.sk", "MEDIUM", "rtvs_sport_slovakia_if_exists"),
    "7005": ("WinSports.co", "MEDIUM", "winsports2_to_winsports"),
    "7004": ("WinSports.co", "MEDIUM", "winsportsplus_to_winsports"),
    "459": ("ElevenSports5.pt", "MEDIUM", "eleven_sports_5_pt_if_exists"),
    "179": ("DAZN1.uk", "MEDIUM", "dazn_france_generic_uk_feed_proxy"),
    "5003": ("DAZNLaLiga.uk", "MEDIUM", "dazn_spain_generic_laliga_proxy"),
    "3047": ("ZonaDAZN.it", "MEDIUM", "dazn_italy_generic_zona"),
    "6034": ("DAZN1.uk", "MEDIUM", "dazn_belgium_uk_feed_proxy"),
}

# Name-key curated overrides (display / stripped names)
CURATED_NAME: dict[str, tuple[str, str, str]] = {
    "ACCNX": ("ACCNetwork.us", "HIGH", "accnx_name"),
    "ACCNX HD": ("ACCNetwork.us", "HIGH", "accnx_name"),
    "ACCNX USA": ("ACCNetwork.us", "HIGH", "accnx_name"),
    "BTN+": ("BigTenNetwork.us", "MEDIUM", "btn_plus_proxy"),
    "BTN+ HD": ("BigTenNetwork.us", "MEDIUM", "btn_plus_proxy"),
    "BTN+ USA": ("BigTenNetwork.us", "MEDIUM", "btn_plus_proxy"),
    "BIG TEN PLUS": ("BigTenNetwork.us", "MEDIUM", "big_ten_plus_proxy"),
    "SECN+": ("SECNetwork.us", "MEDIUM", "secn_plus_proxy"),
    "SECN+ HD": ("SECNetwork.us", "MEDIUM", "secn_plus_proxy"),
    "SECNX": ("SECNetwork.us", "MEDIUM", "secnx_proxy"),
    "SECNX HD": ("SECNetwork.us", "MEDIUM", "secnx_proxy"),
    "RACER TV": ("RacerNetwork.us", "MEDIUM", "racer_tv_proxy"),
    "RACER TV HD": ("RacerNetwork.us", "MEDIUM", "racer_tv_proxy"),
    "BEIN SPORT": ("beINSportsUSA.us", "HIGH", "bein_sport_usa"),
    "BEIN SPORS": ("beINSportsUSA.us", "HIGH", "bein_spors_typo"),
    "BEIN SPORTS USA": ("beINSportsUSA.us", "HIGH", "bein_sports_usa"),
    "MGM+ USA / EPIX": ("MGMPlus.us", "HIGH", "mgm_plus"),
    "MGM+": ("MGMPlus.us", "HIGH", "mgm_plus"),
    "PRO SIEBEN": ("ProSieben.de", "HIGH", "prosieben"),
    "CARACOL TV COLOMBIA": ("CaracolTV.co", "HIGH", "caracol"),
    "TVE LA 1 SPAIN": ("La1.es", "HIGH", "la1"),
    "TVE LA 2 SPAIN": ("La2.es", "HIGH", "la2"),
    "GOL SPAIN": ("Gol.es", "HIGH", "gol"),
    "SKY SPORTS 1": ("SkySport1.nz", "HIGH", "sky_sport_1_nz_context"),
    "TEN SPORTS": ("TenSportsPakistan.pk", "HIGH", "ten_sports_pk"),
    "NPO 3": ("NPO3.nl", "HIGH", "npo3"),
    "RSI LA 2": ("RSILa2.ch", "HIGH", "rsi_la2"),
    "FIGHTKLUB POLAND": ("FightKlub.pl", "HIGH", "fightklub"),
    "SPORT TV7 PORTUGAL": ("SportTV7.pt", "HIGH", "sporttv7"),
    "ANTENA 1 ROMANIA": ("Antena1.ro", "HIGH", "antena1"),
    "MATCH IGRA RUSSIA": ("MatchIgra.ru", "HIGH", "match_igra"),
    "МАТЧ! IGRA RUSSIA": ("MatchIgra.ru", "HIGH", "match_igra"),
    "SVT 24 SWEDEN": ("SVT24.se", "HIGH", "svt24"),
    "TV4 SWEDEN": ("TV4.se", "HIGH", "tv4"),
    "V SPORT 1": ("VSport1.se", "HIGH", "vsport1"),
    "V SPORT PREMIUM": ("VSportPremium.se", "HIGH", "vsport_premium"),
    "TABII SPOR 6 TURKEY": ("TabiiSpor6.tr", "HIGH", "tabii6"),
    "DAZN ZONA 2": ("ZonaDAZN2.it", "HIGH", "zona2"),
    "SKY SPORT MOTOGP": ("SkySportMotoGP.it", "HIGH", "motogp"),
    "ERT 2 GREECE": ("ERT2.gr", "HIGH", "ert2"),
    "ACCUWEATHER": ("AccuWeatherNOW.us", "HIGH", "accuweather"),
    "ESPN SEC NETWORK": ("SECNetwork.us", "HIGH", "espn_sec_network"),
    "ESPN NEWS": ("ESPNNews.us", "HIGH", "espn_news_if_exists"),
    "ME TV TOONS": ("MeTVToons.us", "MEDIUM", "metv_toons_if_exists"),
    "METV TOONS": ("MeTVToons.us", "MEDIUM", "metv_toons_if_exists"),
    "CA UNIVISION": ("UnivisionCanada.ca", "HIGH", "univision_ca"),
    "BEIN SPORTS SPANISH": ("beINSPORTSXTRAenEspanol.us", "MEDIUM", "bein_spanish_xtra"),
}

SUFFIX_BY_CC = {
    "US": (".us", "us2", ".us_"),
    "UK": (".uk",),
    "CA": (".ca", "ca2"),
    "DE": (".de",),
    "FR": (".fr",),
    "IT": (".it",),
    "ES": (".es",),
    "AU": (".au",),
    "NZ": (".nz",),
    "MX": (".mx",),
    "BR": (".br",),
    "CO": (".co",),
    "HR": (".hr",),
    "IL": (".il",),
    "GR": (".gr",),
    "TR": (".tr",),
    "PL": (".pl",),
    "NL": (".nl",),
    "BE": (".be",),
    "PT": (".pt",),
    "SE": (".se",),
    "NO": (".no",),
    "DK": (".dk",),
    "FI": (".fi",),
    "AT": (".at",),
    "CH": (".ch",),
    "IE": (".ie",),
    "AR": (".ar",),
    "CL": (".cl",),
    "RO": (".ro",),
    "RU": (".ru",),
    "AL": (".al",),
    "SK": (".sk",),
    "HU": (".hu",),
    "AZ": (".az",),
    "RS": (".rs",),
    "SI": (".si",),
    "EE": (".ee",),
    "PK": (".pk",),
    "IN": (".in",),
    "IQ": (".iq",),
    "MD": (".md",),
    "AU": (".au",),
    "AR": (".ar",),
    "UA": (".ua",),
}


@dataclass
class Fix:
    kind: str  # dl_id | name
    key: str
    name: str
    tvg_id: str
    tier: str
    reason: str
    provider: str


@dataclass
class Skip:
    name: str
    provider: str
    reason: str
    channel_id: str = ""


def kotlin_norm(name: str) -> str:
    s = (name or "").lower()
    s = SUPERSCRIPTS.sub(" ", s)
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def strip_display(name: str) -> str:
    s = SUPERSCRIPTS.sub("", name or "")
    s = COUNTRY_PREFIX.sub("", s)
    return s.strip()


def clean_for_match(name: str) -> str:
    s = strip_display(name)
    s = COUNTRY_WORDS.sub(" ", s)
    s = re.sub(r"\b(hd|fhd|4k|sd)\b", " ", s, flags=re.I)
    return re.sub(r"\s+", " ", s).strip()


NAME_COUNTRY_WORDS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\bslovenia\b", re.I), "SI"),
    (re.compile(r"\balbania\b", re.I), "AL"),
    (re.compile(r"\bmoldova\b", re.I), "MD"),
    (re.compile(r"\bnorway\b", re.I), "NO"),
    (re.compile(r"\baustr(alia|elia|lia|elia)\b", re.I), "AU"),
    (re.compile(r"\bargentina\b", re.I), "AR"),
    (re.compile(r"\bchile\b", re.I), "CL"),
    (re.compile(r"\bserbia\b", re.I), "RS"),
    (re.compile(r"\bestonia\b", re.I), "EE"),
    (re.compile(r"\bcroatia\b", re.I), "HR"),
    (re.compile(r"\bhungary\b|\bhungry\b", re.I), "HU"),
    (re.compile(r"\bfinland\b", re.I), "FI"),
    (re.compile(r"\bslovakia\b", re.I), "SK"),
    (re.compile(r"\bbelgium\b|\bbelgim\b", re.I), "BE"),
    (re.compile(r"\bportugal\b|\bpoutugal\b", re.I), "PT"),
    (re.compile(r"\bpoland\b", re.I), "PL"),
    (re.compile(r"\bromania\b", re.I), "RO"),
    (re.compile(r"\brussia\b", re.I), "RU"),
    (re.compile(r"\bturkey\b", re.I), "TR"),
    (re.compile(r"\bgreece\b", re.I), "GR"),
    (re.compile(r"\bsweden\b", re.I), "SE"),
    (re.compile(r"\bcolombia\b", re.I), "CO"),
    (re.compile(r"\bmexico\b", re.I), "MX"),
    (re.compile(r"\bspain\b", re.I), "ES"),
    (re.compile(r"\bfrance\b", re.I), "FR"),
    (re.compile(r"\bgermany\b", re.I), "DE"),
    (re.compile(r"\bitaly\b", re.I), "IT"),
    (re.compile(r"\bireland\b", re.I), "IE"),
    (re.compile(r"\bholland\b|\bnetherlands\b", re.I), "NL"),
    (re.compile(r"\bazerbaijan\b", re.I), "AZ"),
    (re.compile(r"\bukrain\w*\b", re.I), "UA"),
]


def country_hint(name: str) -> str | None:
    m = COUNTRY_PREFIX.match(name or "")
    if m:
        cc = m.group(1).upper()
        if cc != "INT":
            return cc
    for pat, cc in NAME_COUNTRY_WORDS:
        if pat.search(name or ""):
            return cc
    return None


def country_ok(tvg_id: str, hint: str | None, db_row: dict | None = None) -> bool:
    if not hint:
        return True
    low = tvg_id.lower()
    for suf in SUFFIX_BY_CC.get(hint, ()):
        if low.endswith(suf) or suf.rstrip("_") in low:
            return True
    if low.endswith("." + hint.lower()):
        return True
    if db_row and (db_row.get("country") or "").upper() == hint:
        return True
    return False


def load_playlist(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    out: list[dict] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXTINF:"):
            tvg_m = re.search(r'tvg-id="([^"]*)"', line)
            tvg = (tvg_m.group(1) if tvg_m else "").strip()
            name = line.split(",", 1)[-1].strip() if "," in line else "?"
            group_m = re.search(r'group-title="([^"]*)"', line)
            group = group_m.group(1) if group_m else ""
            url = ""
            j = i + 1
            while j < len(lines):
                u = lines[j].strip()
                if u and not u.startswith("#"):
                    url = u.split("|")[0]
                    break
                j += 1
            if not tvg:
                out.append({"name": name, "url": url, "group": group})
        i += 1
    return out


def classify(entry: dict) -> tuple[str, str | None]:
    url = entry["url"]
    m = re.search(r"tivimate-stream/(\d+)\.m3u8", url)
    if m:
        return "daddylive", m.group(1)
    if "/ntv-stream/" in url:
        return "ntv", None
    if "/dulo-stream/" in url:
        return "dulo", None
    if any(x in url for x in ("sofast", "ottera", "tubi", "pluto", "xumo", "klowdtv", "frequency.stream")):
        return "fast_cdn", None
    return "other", None


def load_db() -> tuple[dict[str, dict], dict[str, list[str]]]:
    db: dict[str, dict] = {}
    by_norm: dict[str, list[str]] = defaultdict(list)
    with CHANNELS_DB.open(encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            cid = (row.get("id") or "").strip()
            if not cid:
                continue
            db[cid] = row
            names = [row.get("name") or ""]
            names += [a.strip() for a in (row.get("alt_names") or "").split(";") if a.strip()]
            for nm in names:
                n = kotlin_norm(nm)
                if n and cid not in by_norm[n]:
                    by_norm[n].append(cid)
    return db, by_norm


def id_exists(tvg: str, db: dict[str, dict], prog: set[str], bridge: set[str]) -> bool:
    return tvg in db or tvg in prog or tvg in bridge


def pick_exact(
    name: str,
    by_norm: dict[str, list[str]],
    db: dict[str, dict],
    prog: set[str],
    bridge: set[str],
    hint: str | None,
) -> tuple[str, str, str] | None:
    variants = []
    for raw in (name, strip_display(name), clean_for_match(name)):
        n = kotlin_norm(raw)
        if n:
            variants.append(n)
        # collapse spaces in dazn1 style
        n2 = n.replace(" ", "") if n else ""
        if n2 and n2 != n:
            variants.append(n2)
    seen: set[str] = set()
    cands: list[str] = []
    for n in variants:
        for cid in by_norm.get(n, []):
            if cid not in seen:
                seen.add(cid)
                cands.append(cid)
    if not cands:
        return None
    filtered = [
        c for c in cands if country_ok(c, hint, db.get(c))
    ] if hint else list(cands)
    if not filtered:
        # Hard reject: never fall back to wrong-country exact norms
        return None
    with_prog = [c for c in filtered if c in prog or c in bridge]
    if len(filtered) == 1:
        return filtered[0], "HIGH", "exact_unique_norm"
    if len(with_prog) == 1:
        return with_prog[0], "HIGH", "exact_unique_with_prog"
    if hint and len(filtered) > 1:
        # prefer matching country code field
        cc = hint.lower()
        by_cc = [c for c in filtered if (db.get(c, {}).get("country") or "").lower() == cc]
        if len(by_cc) == 1:
            return by_cc[0], "HIGH", "exact_country_field"
        if len([c for c in by_cc if c in prog or c in bridge]) == 1:
            return [c for c in by_cc if c in prog or c in bridge][0], "HIGH", "exact_country_prog"
    return None


def fetch_playlist() -> Path:
    for url in (
        "http://127.0.0.1:13000/tivimate.m3u",
        "http://127.0.0.1:3000/tivimate.m3u",
        "http://127.0.0.1:3000/tivimate-playlist.m3u8",
    ):
        try:
            with urllib.request.urlopen(url, timeout=20) as resp:
                data = resp.read()
            path = Path("/tmp/empty-tvg-live-playlist.m3u")
            path.write_bytes(data)
            print(f"Fetched playlist from {url} ({len(data)} bytes)")
            return path
        except Exception as exc:
            print(f"skip {url}: {exc}")
    for p in (
        Path("/tmp/live-playlist.m3u"),
        Path("/tmp/stepdaddy-playlist.m3u8"),
        Path("/tmp/stepdaddy-playlist-audit.m3u8"),
    ):
        if p.is_file() and p.stat().st_size > 1000:
            print(f"Using fallback playlist {p}")
            return p
    raise SystemExit("No playlist available")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--playlist", type=Path, default=None)
    args = ap.parse_args()

    playlist = args.playlist or fetch_playlist()
    empty = load_playlist(playlist)
    total_empty = len(empty)
    print(f"Empty tvg-id channels: {total_empty}")

    db, by_norm = load_db()
    prog = {l.strip() for l in PROG_IDS.read_text().splitlines() if l.strip()} if PROG_IDS.is_file() else set()
    bridge_raw = json.loads(BRIDGE_PATH.read_text(encoding="utf-8")) if BRIDGE_PATH.is_file() else {}
    bridge = set((bridge_raw.get("bridge") or {}).keys())

    # Validate curated targets exist
    for cid, (tvg, tier, reason) in list(CURATED_ID.items()):
        if not id_exists(tvg, db, prog, bridge):
            # drop missing unless we know bridge/prog — already checked
            if reason.endswith("_if_exists") or "skip_if_missing" in reason:
                print(f"drop curated {cid}->{tvg} (missing)")
                del CURATED_ID[cid]
            else:
                print(f"WARN curated missing {cid}->{tvg}")

    # Fix Sport1.al / VSport1.no / RTS1.rs / RTVSSport / ElevenSports5.pt presence
    for drop_id, need in [
        ("3063", "Sport1.al"),
        ("3067", "VSport1.no"),
        ("254", "RTS1.rs"),
        ("255", "RTVSSport.sk"),
        ("459", "ElevenSports5.pt"),
    ]:
        if drop_id in CURATED_ID and not id_exists(CURATED_ID[drop_id][0], db, prog, bridge):
            print(f"drop curated {drop_id} missing target")
            del CURATED_ID[drop_id]

    # ESPNNews / MeTVToons
    for k, (tvg, tier, reason) in list(CURATED_NAME.items()):
        if reason.endswith("_if_exists") and not id_exists(tvg, db, prog, bridge):
            print(f"drop name curated {k}->{tvg}")
            del CURATED_NAME[k]
        # try alternate ids
    if "ESPN NEWS" in CURATED_NAME and not id_exists("ESPNNews.us", db, prog, bridge):
        for alt in ("ESPNews.us", "ESPN.News.us", "ESPNNews.us2"):
            if id_exists(alt, db, prog, bridge):
                CURATED_NAME["ESPN NEWS"] = (alt, "HIGH", "espn_news")
                break
        else:
            del CURATED_NAME["ESPN NEWS"]
    for k in ("ME TV TOONS", "METV TOONS"):
        if k in CURATED_NAME and not id_exists(CURATED_NAME[k][0], db, prog, bridge):
            for alt in ("MeTVToons.us", "METVToons.us", "MeTV.Toons.us"):
                if id_exists(alt, db, prog, bridge):
                    CURATED_NAME[k] = (alt, "HIGH", "metv_toons")
                    break
            else:
                del CURATED_NAME[k]

    mapping_asset = json.loads(MAPPING_PATH.read_text(encoding="utf-8"))
    mapping: dict[str, str] = dict(mapping_asset.get("mapping") or {})
    overrides: dict[str, str] = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))

    fixes: list[Fix] = []
    skips: list[Skip] = []
    seen_fix_keys: set[str] = set()

    def add_fix(fix: Fix) -> None:
        key = f"{fix.kind}:{fix.key}:{fix.tvg_id}"
        if key in seen_fix_keys:
            return
        if not id_exists(fix.tvg_id, db, prog, bridge) and fix.tier == "HIGH":
            # allow bridge-only HIGH already covered by id_exists
            if fix.tvg_id not in prog and fix.tvg_id not in bridge and fix.tvg_id not in db:
                print(f"reject missing target {fix}")
                return
        seen_fix_keys.add(key)
        fixes.append(fix)

    # Pass 1: DaddyLive curated + auto
    for entry in empty:
        provider, did = classify(entry)
        name = entry["name"]
        if provider != "daddylive" or not did:
            continue
        if ADULT_RE.search(name) or entry["group"] == "XXX Adult":
            skips.append(Skip(name, provider, "adult", did))
            continue
        if EVENT_RE.search(name) or EVENT_RE.search(strip_display(name)):
            skips.append(Skip(name, provider, "event_ppv_overflow_no_stable_epg", did))
            continue
        if PEG_RE.search(name):
            skips.append(Skip(name, provider, "peg_community", did))
            continue

        if did in CURATED_ID:
            tvg, tier, reason = CURATED_ID[did]
            add_fix(Fix("dl_id", did, name, tvg, tier, reason, provider))
            # also name override for display variants
            add_fix(
                Fix(
                    "name",
                    strip_display(name),
                    name,
                    tvg,
                    tier,
                    reason + "_name",
                    provider,
                )
            )
            continue

        # curated by stripped name
        stripped = strip_display(name)
        cleaned = clean_for_match(name)
        hit = None
        for key in (stripped, cleaned, stripped.upper(), kotlin_norm(stripped)):
            for ck, cv in CURATED_NAME.items():
                if kotlin_norm(ck) == kotlin_norm(key) or ck.upper() == stripped.upper():
                    hit = cv
                    break
            if hit:
                break
        if hit:
            tvg, tier, reason = hit
            add_fix(Fix("dl_id", did, name, tvg, tier, reason, provider))
            add_fix(Fix("name", stripped, name, tvg, tier, reason, provider))
            continue

        hint = country_hint(name)
        picked = pick_exact(name, by_norm, db, prog, bridge, hint)
        if picked:
            tvg, tier, reason = picked
            # reject single-token digit-only overlap style already avoided by exact norm
            add_fix(Fix("dl_id", did, name, tvg, tier, reason, provider))
            add_fix(Fix("name", stripped, name, tvg, tier, reason, provider))
            continue

        skips.append(Skip(name, provider, "no_high_medium_match", did))

    # Pass 2: supplements / FAST / NTV / dulo — name overrides only
    woftv_norms: set[str] = set()
    if WOFTV_US.is_file():
        try:
            programs = json.loads(WOFTV_US.read_text(encoding="utf-8")).get("programs") or []
            for p in programs:
                ch = p.get("channel") or ""
                title = (p.get("title") or "").strip()
                if ch and title and title.lower() != "program information currently unavailable":
                    woftv_norms.add(kotlin_norm(ch))
        except Exception as exc:
            print(f"WOFTV load failed: {exc}")

    for entry in empty:
        provider, did = classify(entry)
        if provider == "daddylive":
            continue
        name = entry["name"]
        if ADULT_RE.search(name) or entry["group"] == "XXX Adult":
            skips.append(Skip(name, provider, "adult"))
            continue
        if PEG_RE.search(name):
            skips.append(Skip(name, provider, "peg_community"))
            continue
        if EVENT_RE.search(name):
            skips.append(Skip(name, provider, "event_ppv_no_stable_epg"))
            continue

        stripped = strip_display(name)
        cleaned = clean_for_match(name)
        hint = country_hint(name) or ("US" if provider in ("ntv", "fast_cdn", "dulo") else None)

        # curated name
        hit = None
        for ck, cv in CURATED_NAME.items():
            if kotlin_norm(ck) == kotlin_norm(stripped) or kotlin_norm(ck) == kotlin_norm(cleaned):
                hit = cv
                break
        if hit:
            tvg, tier, reason = hit
            add_fix(Fix("name", stripped, name, tvg, tier, reason, provider))
            add_fix(Fix("name", name, name, tvg, tier, reason + "_full", provider))
            continue

        picked = pick_exact(name, by_norm, db, prog, bridge, hint)
        if picked:
            tvg, tier, reason = picked
            add_fix(Fix("name", stripped, name, tvg, tier, reason, provider))
            add_fix(Fix("name", name, name, tvg, tier, reason + "_full", provider))
            continue

        # MEDIUM: WOFTV name proven + unique channels_db token-strong match already failed;
        # if WOFTV has the name and AccuWeather-style US FAST id exists in overrides patterns — skip inventing hashes
        n = kotlin_norm(stripped)
        if n in woftv_norms:
            # only map if channels_db has a unique US/CA match after loosening country words
            loose = pick_exact(cleaned, by_norm, db, prog, bridge, "US")
            if loose:
                tvg, _, _ = loose
                add_fix(
                    Fix(
                        "name",
                        stripped,
                        name,
                        tvg,
                        "MEDIUM",
                        "woftv_name_plus_channels_db",
                        provider,
                    )
                )
                continue
            skips.append(Skip(name, provider, "woftv_name_but_no_stable_tvg_id"))
            continue

        skips.append(Skip(name, provider, "no_high_medium_match"))

    # Deduplicate skips by name+provider
    skip_keys = set()
    uniq_skips: list[Skip] = []
    for s in skips:
        k = (s.name, s.provider, s.reason)
        if k in skip_keys:
            continue
        skip_keys.add(k)
        uniq_skips.append(s)
    skips = uniq_skips

    high = [f for f in fixes if f.tier == "HIGH"]
    med = [f for f in fixes if f.tier == "MEDIUM"]
    print(f"Fixes HIGH={len(high)} MEDIUM={len(med)} unique keys={len(fixes)}")
    print(f"Skips={len(skips)}")

    if args.apply:
        id_fixes = {f.key: f for f in fixes if f.kind == "dl_id"}
        name_fixes = {f.key: f for f in fixes if f.kind == "name"}
        for key, fix in id_fixes.items():
            mapping[key] = fix.tvg_id
        for key, fix in name_fixes.items():
            overrides[key] = fix.tvg_id
            # also store kotlin-normalized display without country for mapper variants
            stripped = strip_display(key) if COUNTRY_PREFIX.match(key or "") else key
            if stripped and stripped != key:
                overrides.setdefault(stripped, fix.tvg_id)

        mapping_asset["mapping"] = dict(
            sorted(mapping.items(), key=lambda kv: int(kv[0]) if kv[0].isdigit() else kv[0])
        )
        mapping_asset["mapped_count"] = len(mapping_asset["mapping"])
        mapping_asset["exported_at"] = datetime.now(timezone.utc).isoformat()
        mapping_asset["source"] = mapping_asset.get("source") or "resolve-empty-tvg-ids"
        MAPPING_PATH.write_text(
            json.dumps(mapping_asset, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        OVERRIDES_PATH.write_text(
            json.dumps(
                dict(sorted(overrides.items(), key=lambda kv: kv[0].lower())),
                indent=2,
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"Wrote {MAPPING_PATH} ({mapping_asset['mapped_count']} ids)")
        print(f"Wrote {OVERRIDES_PATH} ({len(overrides)} overrides)")

    # Report
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    sample = sorted(fixes, key=lambda f: (f.tier, f.name))[:40]
    skip_reasons = Counter(s.reason for s in skips)
    by_provider_fix = Counter(f.provider for f in fixes)
    by_provider_skip = Counter(s.provider for s in skips)

    lines = [
        "# Empty tvg-id resolution",
        "",
        f"**Date:** {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}",
        f"**Playlist:** `{playlist}`",
        f"**Applied:** {bool(args.apply)}",
        "",
        "## Scorecard",
        "",
        f"- **Empty before:** {total_empty}",
        f"- **Mapped HIGH (fix rows):** {len(high)}",
        f"- **Mapped MEDIUM (fix rows):** {len(med)}",
        f"- **Unique dl_id fixes:** {len({f.key for f in fixes if f.kind == 'dl_id'})}",
        f"- **Unique name fixes:** {len({f.key for f in fixes if f.kind == 'name'})}",
        f"- **Skipped:** {len(skips)}",
        "",
        "### Fixes by provider",
        "",
    ]
    for k, v in by_provider_fix.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "### Skips by provider", ""]
    for k, v in by_provider_skip.most_common():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "### Skip reasons", ""]
    for k, v in skip_reasons.most_common():
        lines.append(f"- `{k}`: {v}")

    lines += [
        "",
        "## Sample mappings",
        "",
        "| Tier | Provider | Key | Name | tvg-id | Reason |",
        "|------|----------|-----|------|--------|--------|",
    ]
    for f in sample:
        lines.append(
            f"| {f.tier} | {f.provider} | `{f.kind}:{f.key}` | {f.name[:48]} | `{f.tvg_id}` | {f.reason} |"
        )

    lines += ["", "## Skipped (sample)", ""]
    for s in skips[:80]:
        cid = f" id={s.channel_id}" if s.channel_id else ""
        lines.append(f"- [{s.provider}] {s.name[:70]}{cid} — {s.reason}")
    if len(skips) > 80:
        lines.append(f"- … +{len(skips) - 80} more")

    lines += [
        "",
        "## Notes",
        "",
        "- ACCNX-style proxies (BTN+/SECN+/SECNX→network linear, Racer TV→Racer Network) are **MEDIUM** with documented caveats.",
        "- HR Sport Klub → NL SportKlub ids are **MEDIUM** brand proxies (no HR ids in channels_db).",
        "- Event/PPV/Court/Canal+ LIVE overflow and adult channels were **SKIP**ped (no invent).",
        "- Live gateway may still show empties until APK/assets reload (ACCNX already in assets before this run).",
        "",
    ]
    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    JSON_REPORT.write_text(
        json.dumps(
            {
                "total_empty": total_empty,
                "high": len(high),
                "medium": len(med),
                "skips": len(skips),
                "fixes": [asdict(f) for f in fixes],
                "skips_list": [asdict(s) for s in skips],
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {REPORT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Execute epg-residual-75-plan.md phases A–D (high-confidence only).

Inventories Movies / Entertainment / Local Channels residuals, finds grade A/B
candidates, proves against US2/LOCALS1/WOFTV caches, applies bridges + name
overrides + WOFTV aliases, and writes a results report.
"""

from __future__ import annotations

import csv
import gzip
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
BRIDGE_PATH = ASSETS / "epg_id_bridge.json"
OVERRIDES_PATH = ASSETS / "epg_name_overrides.json"
WOFTV_CONFIG = (
    ROOT
    / "app/src/main/kotlin/com/thothassistant/stepdaddy/gateway/epg/WhatsOnFreeTvEpgConfig.kt"
)
PLAYLIST = Path("/tmp/stepdaddy-playlist.m3u8")
PROG_IDS = Path("/tmp/epg-audit-prog-ids.txt")
US2_GZ = Path("/tmp/epg_ripper_US2_fresh.xml.gz")
LOCALS_GZ = Path("/tmp/epg_ripper_US_LOCALS1.xml.gz")
LOCALS_IDS = Path("/tmp/locals-ids.txt")
WOFTV_AUDIT = ROOT / "reports/woftv-match-audit.csv"
WOFTV_US = Path("/tmp/woftv-us.json")
OUT_DIR = ROOT / "reports/residuals"
RESULTS = ROOT / "reports/epg-residual-75-results.md"

GROUPS = ("Movies", "Entertainment", "Local Channels")
BASELINE = {
    "Movies": (127, 246),
    "Entertainment": (729, 2070),
    "Local Channels": (28, 175),
}

PEG_RE = re.compile(
    r"\b(public access|peg|community (media|tv|television)|"
    r"government|city council|education channel|school district|"
    r"university|campus tv|public access|access humboldt|"
    r"crea\s*tv|midpen|sf commons|bolton cvc|amp community|"
    r"hearing room|municipal|cable access)\b",
    re.I,
)
SUBCHANNEL_RE = re.compile(r"(\.2|\.3| too |diginet|dt2|dt3|-dt2|-dt3)", re.I)
CALL_FROM_ID = re.compile(
    r"^((?:[KWC][A-Z]{2,3})(?:TV|DT|LD|CD)?)(\d*)\.us",
    re.I,
)
HEX_RE = re.compile(r"^[0-9a-f]{24}$", re.I)
SUPERSCRIPTS = re.compile(r"[\u1d2c-\u1d61\u2070-\u209f\u02b0-\u02ff]+")


@dataclass
class Channel:
    tvg_id: str
    name: str
    group: str
    chno: str = ""


@dataclass
class Candidate:
    tvg_id: str
    name: str
    group: str
    feed_id: str
    grade: str
    phase: str
    method: str
    evidence: str = ""
    apply_as: str = "bridge"  # bridge | name_override | woftv_alias
    alias_key: str = ""
    alias_val: str = ""


def normalize_name(name: str) -> str:
    """Match EpgChannelMapper.normalizeName + strip country prefix / RAW marks."""
    s = (name or "").lower()
    s = SUPERSCRIPTS.sub(" ", s)
    s = re.sub(r"^(us|uk|ca|int|es|de|fr|it|au|nz|mx|br|cz):\s*", "", s)
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def strip_display(name: str) -> str:
    s = SUPERSCRIPTS.sub("", name or "")
    s = re.sub(r"^(US|UK|CA|INT|ES|DE|FR|IT|AU|NZ|MX|BR|CZ):\s*", "", s, flags=re.I)
    return s.strip()


def parse_playlist(path: Path) -> list[Channel]:
    out: list[Channel] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.startswith("#EXTINF"):
            continue
        g = re.search(r'group-title="([^"]*)"', line)
        if not g or g.group(1) not in GROUPS:
            continue
        tid = re.search(r'tvg-id="([^"]*)"', line)
        nm = re.search(r'tvg-name="([^"]*)"', line)
        ch = re.search(r'tvg-chno="([^"]*)"', line)
        name = nm.group(1) if nm else line.split(",", 1)[-1].strip()
        out.append(
            Channel(
                tvg_id=(tid.group(1) if tid else "").strip(),
                name=name,
                group=g.group(1),
                chno=(ch.group(1) if ch else ""),
            )
        )
    return out


def load_xml_channels(gz: Path) -> tuple[dict[str, str], set[str], dict[str, list[str]]]:
    """id->display, ids_with_programmes, norm->ids."""
    text = gzip.open(gz, "rt", encoding="utf-8", errors="replace").read()
    pairs = re.findall(
        r'<channel id="([^"]+)">[\s\S]*?<display-name[^>]*>([^<]*)</display-name>',
        text,
    )
    id_to_name = {cid: dname for cid, dname in pairs}
    with_prog = set(re.findall(r'<programme[^>]*channel="([^"]+)"', text))
    by_norm: dict[str, list[str]] = defaultdict(list)
    for cid, dname in pairs:
        by_norm[normalize_name(dname)].append(cid)
    return id_to_name, with_prog, by_norm


PLACEHOLDER_TITLE = "program information currently unavailable"


def load_woftv_keys(path: Path) -> dict[str, list[dict]]:
    """channel-key-norm → sample programme rows with real titles."""
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, list[dict]] = defaultdict(list)
    if isinstance(data, list):
        rows = data
    elif isinstance(data, dict):
        rows = data.get("programs") or data.get("channels") or data.get("epg") or []
    else:
        rows = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = row.get("channel") or row.get("name") or row.get("channelName") or ""
        if not name:
            continue
        title = (row.get("title") or "").strip()
        if title.lower() == PLACEHOLDER_TITLE:
            continue
        key = normalize_name(str(name))
        if len(out[key]) < 5:
            out[key].append(row)
    return dict(out)


def prefer_us2(ids: list[str], with_prog: set[str]) -> str | None:
    """Prefer HD East / bare HD with programmes."""
    scored: list[tuple[int, str]] = []
    for cid in ids:
        if cid not in with_prog:
            continue
        score = 0
        low = cid.lower()
        if ".hd." in low or low.endswith(".hd.us2"):
            score += 3
        if "pacific" in low or "west" in low:
            score -= 2
        if "east" in low:
            score += 1
        if low.endswith(".us2"):
            score += 1
        scored.append((score, cid))
    if not scored:
        return None
    scored.sort(key=lambda x: (-x[0], x[1]))
    return scored[0][1]


def extract_call(tvg_id: str, name: str) -> str | None:
    tid = (tvg_id or "").strip()
    m = CALL_FROM_ID.match(tid.replace("@", "."))
    if m:
        base = m.group(1).upper()
        # normalize KRGVTV → KRGV, WATCDT → WATC
        base = re.sub(r"(TV|DT|LD|CD)$", "", base)
        return base
    m2 = re.search(r"\b([KWC][A-Z]{2,3})(?:-?[A-Z]{0,2})?\b", name.upper())
    if m2:
        return m2.group(1)
    return None


def is_peg(name: str, tvg_id: str) -> bool:
    blob = f"{name} {tvg_id}"
    if PEG_RE.search(blob):
        return True
    low = blob.lower()
    markers = (
        "public access",
        "community media",
        "community tv",
        "government",
        "education",
        "municipal",
        "city of ",
        "county ",
        " school",
        "university",
        "campus",
        "peg ",
        "leasing",
        "bulletin board",
        "meeting",
        "hearing",
        "access ",
    )
    return any(m in low for m in markers)


def bucket_of(c: Channel) -> str:
    if not c.tvg_id:
        return "empty_tvg_id"
    if HEX_RE.match(c.tvg_id) or c.tvg_id.isdigit():
        return "fast_hash"
    if is_peg(c.name, c.tvg_id):
        return "peg"
    if c.group == "Local Channels":
        return "local_other"
    if re.match(r"^US[A-Z0-9]{6,}$", c.tvg_id) or "Latin" in c.name:
        return "ambiguous"
    return "ambiguous"


def resolve_covered(
    c: Channel,
    prog: set[str],
    bridge: dict[str, list[str]],
    overrides_by_norm: dict[str, str],
    feed_prog: set[str],
    woftv_keys: set[str],
    woftv_aliases: dict[str, str],
    *,
    count_woftv: bool = True,
) -> bool:
    tid = c.tvg_id
    if tid and tid in prog:
        return True
    if tid and tid in feed_prog:
        return True
    if tid:
        base = tid.split("@")[0]
        if base in feed_prog or base in prog:
            return True
        for fid in bridge.get(tid, []):
            if fid in prog or fid in feed_prog:
                return True
            fbase = fid.split("@")[0]
            if fbase in prog or fbase in feed_prog:
                return True
    norm = normalize_name(c.name)
    ov = overrides_by_norm.get(norm)
    if ov and (ov in prog or ov in feed_prog):
        return True
    if not count_woftv:
        return False
    alias = woftv_aliases.get(norm, norm)
    if alias in woftv_keys:
        return True
    return False


def now_playing_us2(feed_id: str, us2_text_cache: dict[str, list[tuple[str, str, str]]]) -> str:
    rows = us2_text_cache.get(feed_id) or []
    if not rows:
        return "(no programme rows cached)"
    # pick first as sample
    title, start, stop = rows[0]
    return f"{title} @ {start}→{stop}"


def index_us2_now(gz: Path, limit_per: int = 3) -> dict[str, list[tuple[str, str, str]]]:
    text = gzip.open(gz, "rt", encoding="utf-8", errors="replace").read()
    out: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for m in re.finditer(
        r'<programme start="([^"]+)" stop="([^"]+)" channel="([^"]+)">\s*'
        r"<title[^>]*>([^<]*)</title>",
        text,
    ):
        start, stop, cid, title = m.group(1), m.group(2), m.group(3), m.group(4)
        if len(out[cid]) < limit_per:
            out[cid].append((title, start, stop))
    return out


def patch_woftv_aliases(new_aliases: dict[str, str]) -> int:
    if not new_aliases or not WOFTV_CONFIG.is_file():
        return 0
    text = WOFTV_CONFIG.read_text(encoding="utf-8")
    # Find NAME_ALIASES map body
    m = re.search(
        r"(val NAME_ALIASES: Map<String, String> = mapOf\()([\s\S]*?)(\n    \))",
        text,
    )
    if not m:
        print("WARN: could not locate NAME_ALIASES", file=sys.stderr)
        return 0
    body = m.group(2)
    existing = set(re.findall(r'"([^"]+)"\s+to\s+"', body))
    added = 0
    lines = []
    for k, v in sorted(new_aliases.items()):
        if k in existing or k == v:
            continue
        lines.append(f'        "{k}" to "{v}",')
        added += 1
    if not added:
        return 0
    # Insert before closing
    insert = "\n".join(lines)
    new_body = body.rstrip() + "\n" + insert + "\n"
    WOFTV_CONFIG.write_text(
        text[: m.start(2)] + new_body + text[m.start(3) :],
        encoding="utf-8",
    )
    return added


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    channels = parse_playlist(PLAYLIST)
    prog = set(PROG_IDS.read_text().splitlines()) if PROG_IDS.is_file() else set()
    bridge_doc = json.loads(BRIDGE_PATH.read_text(encoding="utf-8"))
    bridge: dict[str, list[str]] = dict(bridge_doc.get("bridge") or {})
    overrides: dict[str, str] = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
    overrides_by_norm = {normalize_name(k): v for k, v in overrides.items()}

    us2_names, us2_prog, us2_by_norm = load_xml_channels(US2_GZ)
    locals_names, locals_prog, locals_by_norm = load_xml_channels(LOCALS_GZ)
    locals_id_set = set()
    if LOCALS_IDS.is_file():
        locals_id_set = {ln.strip() for ln in LOCALS_IDS.read_text().splitlines() if ln.strip()}
    else:
        locals_id_set = set(locals_names)

    # Existing WOFTV aliases from config
    woftv_aliases: dict[str, str] = {}
    if WOFTV_CONFIG.is_file():
        for k, v in re.findall(
            r'"([^"]+)"\s+to\s+"([^"]+)"',
            WOFTV_CONFIG.read_text(encoding="utf-8"),
        ):
            woftv_aliases[k] = v

    woftv_catalog = load_woftv_keys(WOFTV_US)
    woftv_keys = set(woftv_catalog)
    print(f"WOFTV catalog keys with real titles: {len(woftv_keys)}", file=sys.stderr)

    feed_prog = us2_prog | locals_prog
    us2_now = index_us2_now(US2_GZ)

    # BEFORE scorecard (using current assets)
    before = Counter()
    before_feed = Counter()
    before_total = Counter()
    peg_counts = Counter()
    for c in channels:
        before_total[c.group] += 1
        if is_peg(c.name, c.tvg_id):
            peg_counts[c.group] += 1
        if resolve_covered(
            c, prog, bridge, overrides_by_norm, feed_prog, woftv_keys, woftv_aliases
        ):
            before[c.group] += 1
        if resolve_covered(
            c,
            prog,
            bridge,
            overrides_by_norm,
            feed_prog,
            woftv_keys,
            woftv_aliases,
            count_woftv=False,
        ):
            before_feed[c.group] += 1

    # Research pool: missing feed/bridge coverage (still pursue US2 even if WOFTV hits)
    residuals = [
        c
        for c in channels
        if not resolve_covered(
            c,
            prog,
            bridge,
            overrides_by_norm,
            feed_prog,
            woftv_keys,
            woftv_aliases,
            count_woftv=False,
        )
    ]
    residuals_woftv_gap = [
        c
        for c in channels
        if not resolve_covered(
            c, prog, bridge, overrides_by_norm, feed_prog, woftv_keys, woftv_aliases
        )
    ]

    # Export residual inventory (WOFTV-gap view for reporting)
    inv_path = OUT_DIR / "residual-inventory.csv"
    with inv_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(
            f,
            fieldnames=["group", "tvg_id", "name", "bucket", "norm", "peg"],
        )
        w.writeheader()
        for c in residuals_woftv_gap:
            w.writerow(
                {
                    "group": c.group,
                    "tvg_id": c.tvg_id,
                    "name": c.name,
                    "bucket": bucket_of(c),
                    "norm": normalize_name(c.name),
                    "peg": is_peg(c.name, c.tvg_id),
                }
            )

    candidates: list[Candidate] = []
    rejects: list[dict] = []

    # --- Phase A: empty + exact US2 name ---
    for c in residuals:
        if c.group == "Local Channels":
            continue
        norm = normalize_name(c.name)
        hits = us2_by_norm.get(norm) or []
        # light variants: drop "movies" suffix noise already handled
        if not hits:
            # try without "plus" expansion side effects by using strip_display only
            alt = normalize_name(strip_display(c.name).replace("!", ""))
            hits = us2_by_norm.get(alt) or []
        feed = prefer_us2(hits, us2_prog)
        if not feed:
            continue
        # Short/ambiguous norms need playlist-id agreement
        if len(norm) < 4 and normalize_name(us2_names.get(feed, "")) != norm:
            rejects.append({"name": c.name, "reason": "short_ambiguous_norm", "feed": feed})
            continue
        if len(norm) <= 5 and len(hits) > 2:
            # require playlist id stem overlap
            stem = (c.tvg_id or "").split("@")[0].lower().replace(".", "")
            if stem and stem not in feed.lower().replace(".", ""):
                rejects.append({"name": c.name, "reason": "ambiguous_multi_us2", "feed": feed})
                continue
        # Hard rejects
        if "movies and more" in norm or "movies more" in norm:
            if "and more" not in normalize_name(us2_names.get(feed, "")):
                rejects.append(
                    {
                        "name": c.name,
                        "reason": "false friend Movies & More",
                        "feed": feed,
                    }
                )
                continue
        if norm == "moviesphere" and "gold" in feed.lower():
            rejects.append({"name": c.name, "reason": "MovieSphere≠Gold", "feed": feed})
            continue
        proof = now_playing_us2(feed, us2_now)
        grade = "A" if c.tvg_id and (
            c.tvg_id.split("@")[0].lower() in feed.lower()
            or normalize_name(us2_names.get(feed, "")) == norm
        ) else "B"
        apply_as = "name_override" if not c.tvg_id else "bridge"
        candidates.append(
            Candidate(
                tvg_id=c.tvg_id or f"__empty__:{norm}",
                name=c.name,
                group=c.group,
                feed_id=feed,
                grade=grade,
                phase="A",
                method="exact_us2_display_name",
                evidence=proof,
                apply_as=apply_as,
            )
        )

    # --- Phase B: locals OTA call-sign ---
    for c in residuals:
        if c.group != "Local Channels":
            continue
        if is_peg(c.name, c.tvg_id):
            continue
        if SUBCHANNEL_RE.search(c.name) or SUBCHANNEL_RE.search(c.tvg_id):
            rejects.append({"name": c.name, "reason": "subchannel/diginet", "feed": ""})
            continue
        call = extract_call(c.tvg_id, c.name)
        if not call:
            continue
        # candidates: CALL-DT.us_locals1, CALL-LD, CALL-CD
        options = [
            f"{call}-DT.us_locals1",
            f"{call}-LD.us_locals1",
            f"{call}-CD.us_locals1",
            f"{call}.us_locals1",
        ]
        feed = None
        for opt in options:
            if opt in locals_id_set and (opt in locals_prog or opt in locals_names):
                if opt in locals_prog or opt in locals_id_set:
                    feed = opt
                    if opt in locals_prog:
                        break
        if not feed:
            # try by display-name call match
            for cid, dname in locals_names.items():
                if dname.upper().startswith(call) and cid in locals_prog:
                    if "-DT" in cid or cid.endswith(f"{call}.us_locals1"):
                        feed = cid
                        break
        if not feed or feed not in locals_prog:
            continue
        # FNX / Too 57.2 style already rejected via SUBCHANNEL
        candidates.append(
            Candidate(
                tvg_id=c.tvg_id,
                name=c.name,
                group=c.group,
                feed_id=feed,
                grade="A",
                phase="B",
                method="callsign_locals1",
                evidence=f"LOCALS1 {feed} display={locals_names.get(feed,'')}",
                apply_as="bridge",
            )
        )

    # --- Phase C: WOFTV ≥ 0.90 + catalog key (exact; name match for empty ids) ---
    woftv_rows = []
    if WOFTV_AUDIT.is_file():
        woftv_rows = list(csv.DictReader(WOFTV_AUDIT.open(encoding="utf-8")))
    residual_by_id = {(c.tvg_id, c.group): c for c in residuals_woftv_gap if c.tvg_id}
    residual_by_norm: dict[tuple[str, str], Channel] = {}
    for c in residuals_woftv_gap:
        residual_by_norm[(normalize_name(c.name), c.group)] = c

    for row in woftv_rows:
        g = row.get("group_title") or ""
        if g not in ("Movies", "Entertainment"):
            continue
        score = float(row.get("best_woftv_score") or 0)
        if score < 0.90:
            continue
        tid = (row.get("current_tvg_id") or "").strip()
        name = row.get("display_name") or ""
        c = residual_by_id.get((tid, g)) if tid else None
        if c is None:
            c = residual_by_norm.get((normalize_name(name), g))
        if not c:
            continue
        key = (row.get("woftv_key") or "").strip()
        if not key:
            continue
        key_norm = normalize_name(key)
        if len(key_norm) < 5 or key_norm not in woftv_keys:
            continue
        if key_norm in {"movies", "comedy", "crime", "news", "drama"}:
            rejects.append({"name": name, "reason": "generic FAST title", "feed": key_norm})
            continue
        display_norm = normalize_name(c.name)
        if display_norm == key_norm or display_norm in woftv_keys:
            continue
        if woftv_aliases.get(display_norm) == key_norm:
            continue
        grade = "A" if score >= 0.95 else "B"
        platforms = row.get("woftv_platform") or ""
        sample = row.get("woftv_sample_title") or ""
        candidates.append(
            Candidate(
                tvg_id=c.tvg_id or f"__empty__:{display_norm}",
                name=c.name,
                group=c.group,
                feed_id=key_norm,
                grade=grade,
                phase="C",
                method=f"woftv_score_{score:.2f}",
                evidence=f"platforms={platforms}; sample={sample}",
                apply_as="woftv_alias",
                alias_key=display_norm,
                alias_val=key_norm,
            )
        )

    # --- Phase D: hard residuals — only exact US2 left that phase A missed due to covered filter ---
    # Gracenote-like with unique US2 name already handled in A.
    # Document permanent uncovered later.

    # Deduplicate candidates preferring higher grade / earlier phase
    grade_rank = {"A": 0, "B": 1, "C": 2}
    best: dict[str, Candidate] = {}
    for cand in candidates:
        if cand.grade not in ("A", "B"):
            continue
        key = cand.tvg_id if not cand.tvg_id.startswith("__empty__") else f"name:{normalize_name(cand.name)}"
        prev = best.get(key)
        if prev is None or grade_rank[cand.grade] < grade_rank[prev.grade]:
            best[key] = cand
    ship = list(best.values())

    # Apply
    bridges_added = 0
    overrides_added = 0
    aliases_to_add: dict[str, str] = {}
    applied_rows: list[dict] = []

    for cand in ship:
        if cand.apply_as == "bridge" and cand.tvg_id and not cand.tvg_id.startswith("__empty__"):
            existing = bridge.get(cand.tvg_id) or []
            if cand.feed_id not in existing:
                bridge[cand.tvg_id] = [cand.feed_id] + [x for x in existing if x != cand.feed_id]
                bridges_added += 1
                applied_rows.append({**asdict(cand), "action": "bridge"})
        elif cand.apply_as == "name_override":
            # Use cleaned display name as override key (mapper strips quality)
            key_name = strip_display(cand.name)
            # Also common variants without HD
            keys = {key_name, re.sub(r"\s+(HD|SD|FHD)$", "", key_name, flags=re.I).strip()}
            for kn in keys:
                if not kn:
                    continue
                if overrides.get(kn) == cand.feed_id:
                    continue
                overrides[kn] = cand.feed_id
                overrides_by_norm[normalize_name(kn)] = cand.feed_id
                overrides_added += 1
            applied_rows.append({**asdict(cand), "action": "name_override"})
        elif cand.apply_as == "woftv_alias":
            if cand.alias_key and cand.alias_val and cand.alias_key != cand.alias_val:
                aliases_to_add[cand.alias_key] = cand.alias_val
                woftv_aliases[cand.alias_key] = cand.alias_val
                applied_rows.append({**asdict(cand), "action": "woftv_alias"})

    aliases_added = patch_woftv_aliases(aliases_to_add)

    # Write bridge + overrides
    bridge_doc["bridge"] = dict(sorted(bridge.items(), key=lambda kv: kv[0].lower()))
    bridge_doc["bridge_count"] = len(bridge_doc["bridge"])
    bridge_doc["generated_at"] = datetime.now(timezone.utc).isoformat()
    BRIDGE_PATH.write_text(json.dumps(bridge_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    OVERRIDES_PATH.write_text(
        json.dumps(dict(sorted(overrides.items(), key=lambda kv: kv[0].lower())), indent=2, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )

    # AFTER scorecard
    after = Counter()
    after_feed = Counter()
    for c in channels:
        if resolve_covered(
            c, prog, bridge, overrides_by_norm, feed_prog, woftv_keys, woftv_aliases
        ):
            after[c.group] += 1
        if resolve_covered(
            c,
            prog,
            bridge,
            overrides_by_norm,
            feed_prog,
            woftv_keys,
            woftv_aliases,
            count_woftv=False,
        ):
            after_feed[c.group] += 1

    # Eligible denom (exclude PEG)
    eligible_total = Counter()
    eligible_cov = Counter()
    for c in channels:
        if is_peg(c.name, c.tvg_id):
            continue
        eligible_total[c.group] += 1
        if resolve_covered(
            c, prog, bridge, overrides_by_norm, feed_prog, woftv_keys, woftv_aliases
        ):
            eligible_cov[c.group] += 1

    # Permanent uncovered tags
    permanent = []
    for c in channels:
        if resolve_covered(
            c, prog, bridge, overrides_by_norm, feed_prog, woftv_keys, woftv_aliases
        ):
            continue
        reasons = []
        if is_peg(c.name, c.tvg_id):
            reasons.append("PEG")
        if not c.tvg_id:
            reasons.append("empty_tvg_id")
        if HEX_RE.match(c.tvg_id or "") or (c.tvg_id or "").isdigit():
            reasons.append("FAST_no_dual_source")
        if "latin" in c.name.lower() or "mexico" in c.name.lower():
            reasons.append("regional_latin")
        if not reasons:
            reasons.append("no_high_conf_source")
        permanent.append({"group": c.group, "tvg_id": c.tvg_id, "name": c.name, "reasons": "|".join(reasons)})

    with (OUT_DIR / "permanent-uncovered.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["group", "tvg_id", "name", "reasons"])
        w.writeheader()
        w.writerows(permanent)

    with (OUT_DIR / "applied-candidates.csv").open("w", encoding="utf-8", newline="") as f:
        if applied_rows:
            w = csv.DictWriter(f, fieldnames=sorted(applied_rows[0].keys()))
            w.writeheader()
            w.writerows(applied_rows)

    def pct(n: int, d: int) -> str:
        return f"{(100.0 * n / d):.1f}%" if d else "n/a"

    lines = []
    lines.append("# EPG residual → 75% results")
    lines.append("")
    lines.append(f"**Date:** {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}")
    lines.append("**Policy:** P0 PEG excluded from eligible denominator; placeholders not counted.")
    lines.append("**Gates:** Grade A/B only (exact US2 name, call-sign LOCALS1, WOFTV ≥0.90 + catalog key).")
    lines.append("")
    lines.append("## Coverage scorecard")
    lines.append("")
    lines.append("| Category | Plan baseline | After feed-only | After +WOFTV | Eligible (+WOFTV, no PEG) | Raw 75% need |")
    lines.append("|----------|---------------|-----------------|--------------|---------------------------|--------------|")
    for g in GROUPS:
        b_n, b_d = BASELINE[g]
        tot = before_total[g]
        el_d = eligible_total[g]
        plan_d = {"Movies": 246, "Entertainment": 2070, "Local Channels": 175}[g]
        need = int(round(0.75 * plan_d))
        lines.append(
            f"| {g} | {b_n}/{b_d} ({pct(b_n,b_d)}) | "
            f"{after_feed[g]}/{tot} ({pct(after_feed[g],tot)}) | "
            f"{after[g]}/{tot} ({pct(after[g],tot)}) | "
            f"{eligible_cov[g]}/{el_d} ({pct(eligible_cov[g],el_d)}) | "
            f"{need} |"
        )
    lines.append("")
    lines.append("### Before this apply (same scorecard)")
    lines.append("")
    lines.append("| Category | Feed-only before | +WOFTV before |")
    lines.append("|----------|------------------|---------------|")
    for g in GROUPS:
        tot = before_total[g]
        lines.append(
            f"| {g} | {before_feed[g]}/{tot} ({pct(before_feed[g],tot)}) | "
            f"{before[g]}/{tot} ({pct(before[g],tot)}) |"
        )
    lines.append("")
    lines.append("## Applied counts")
    lines.append("")
    lines.append(f"- **Bridges added/updated:** {bridges_added}")
    lines.append(f"- **Name overrides added:** {overrides_added}")
    lines.append(f"- **WOFTV NAME_ALIASES added:** {aliases_added}")
    lines.append(f"- **Shipped candidates (A/B):** {len(ship)}")
    lines.append(f"- **Rejects logged:** {len(rejects)}")
    lines.append(f"- **PEG in playlist (excluded from eligible):** {dict(peg_counts)}")
    lines.append("")
    lines.append("## Phase breakdown (shipped)")
    lines.append("")
    phase_c = Counter(c.phase for c in ship)
    for ph in ("A", "B", "C", "D"):
        lines.append(f"- Phase {ph}: {phase_c.get(ph, 0)}")
    lines.append("")
    lines.append("## Top remaining blockers")
    lines.append("")
    reason_c = Counter()
    for row in permanent:
        for r in row["reasons"].split("|"):
            reason_c[f"{row['group']}:{r}"] += 1
    for key, n in reason_c.most_common(20):
        lines.append(f"- `{key}`: {n}")
    lines.append("")
    lines.append("## Artifacts")
    lines.append("")
    lines.append(f"- Residual inventory: `{inv_path.relative_to(ROOT)}`")
    lines.append(f"- Applied candidates: `reports/residuals/applied-candidates.csv`")
    lines.append(f"- Permanent uncovered: `reports/residuals/permanent-uncovered.csv`")
    lines.append(f"- Bridge asset: `app/src/main/assets/epg_id_bridge.json` ({bridge_doc['bridge_count']} keys)")
    lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append(
        "- Host scorecard treats WOFTV catalog name hits as covered (device `mergeGaps` path)."
    )
    lines.append(
        "- Raw 75% for Entertainment/Locals remains a stretch; eligible % is the honest goal metric."
    )
    lines.append(
        "- No low-confidence fuzzy network matches were applied."
    )

    RESULTS.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Update plan status header
    plan = ROOT / "reports/epg-residual-75-plan.md"
    if plan.is_file():
        ptxt = plan.read_text(encoding="utf-8")
        ptxt = ptxt.replace(
            "**Status:** PLAN ONLY — no bridges in this document.",
            f"**Status:** EXECUTED — see `reports/epg-residual-75-results.md` ({datetime.now(timezone.utc).date()}).",
        )
        plan.write_text(ptxt, encoding="utf-8")

    print(RESULTS.read_text(encoding="utf-8"))
    print(
        f"\nSUMMARY bridges={bridges_added} overrides={overrides_added} "
        f"aliases={aliases_added} ship={len(ship)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

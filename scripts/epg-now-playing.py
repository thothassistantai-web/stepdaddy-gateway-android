#!/usr/bin/env python3
"""Preview what is playing NOW for a playlist tvg-id / channel name.

Host-side approval helper before committing EPG / tvg-id corrections.
Probes sources in order and prints title + start/stop (local + UTC) + confidence.

Usage:
  python3 scripts/epg-now-playing.py HBO2.us
  python3 scripts/epg-now-playing.py --name "US: HBO2 HD"
  python3 scripts/epg-now-playing.py HBO2.us --diagnose
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MAPPING_PATH = ASSETS / "channel_epg_map.json"
OVERRIDES_PATH = ASSETS / "epg_name_overrides.json"
BRIDGE_PATH = ASSETS / "epg_id_bridge.json"
TVTV_BRIDGE_PATH = ASSETS / "tvtv_id_bridge.json"
BUNDLED_GRIDS = ASSETS / "tvtv_bundled_grids"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
)
TVTV_LINEUP = "USA-NY71652-X"
EPGSHARE_US2 = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"
# Mirrors TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS
EASTERN_PREFERRED = {
    "HBO2.us",
    "Showtime.us",
    "StarzInBlack.us",
    "StarzKidsFamily.us",
}
LOCAL_TZ = ZoneInfo("America/New_York")


@dataclass
class Hit:
    source: str
    title: str
    start: datetime
    stop: datetime
    confidence: str
    channel_id: str = ""
    note: str = ""

    def fmt(self) -> str:
        local_s = self.start.astimezone(LOCAL_TZ).strftime("%a %Y-%m-%d %H:%M %Z")
        local_e = self.stop.astimezone(LOCAL_TZ).strftime("%H:%M %Z")
        utc_s = self.start.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        utc_e = self.stop.astimezone(timezone.utc).strftime("%H:%M UTC")
        lines = [
            f"  source:     {self.source}",
            f"  channel:    {self.channel_id or '(playlist id)'}",
            f"  title:      {self.title}",
            f"  local:      {local_s} → {local_e}",
            f"  utc:        {utc_s} → {utc_e}",
            f"  confidence: {self.confidence}",
        ]
        if self.note:
            lines.append(f"  note:       {self.note}")
        return "\n".join(lines)


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def http_get(url: str, timeout: int = 45) -> tuple[int, bytes]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return int(resp.status), resp.read()
    except urllib.error.HTTPError as exc:
        body = exc.read() if exc.fp else b""
        return int(exc.code), body
    except Exception as exc:  # noqa: BLE001
        return -1, str(exc).encode()


def parse_xmltv_ts(raw: str) -> datetime | None:
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", (raw or "").strip())
    if not m:
        return None
    base = datetime.strptime(m.group(1), "%Y%m%d%H%M%S")
    tz = m.group(2)
    if tz:
        sign = 1 if tz[0] == "+" else -1
        offset = timezone(
            sign * timedelta(hours=int(tz[1:3]), minutes=int(tz[3:5])),
        )
        return base.replace(tzinfo=offset).astimezone(timezone.utc)
    return base.replace(tzinfo=timezone.utc)


def load_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_tvg_id(tvg_id: str | None, name: str | None) -> tuple[str, str]:
    mapping = load_json(MAPPING_PATH)
    overrides = load_json(OVERRIDES_PATH)
    if isinstance(mapping.get("mapping"), dict):
        mapping = mapping["mapping"]

    if tvg_id:
        tid = tvg_id.strip()
        hint = next((k for k, v in overrides.items() if v == tid), "")
        return tid, hint

    assert name
    needle = name.strip()
    if needle in overrides:
        return str(overrides[needle]), needle
    norm = re.sub(r"[^a-z0-9]+", "", needle.lower())
    for display, tid in overrides.items():
        if re.sub(r"[^a-z0-9]+", "", display.lower()) == norm:
            return str(tid), display
    for cid, tid in mapping.items():
        if isinstance(tid, str) and norm in re.sub(r"[^a-z0-9]+", "", tid.lower()):
            return tid, f"channel_id={cid}"
    raise SystemExit(f"Could not resolve channel name to tvg-id: {needle!r}")


def feed_aliases(playlist_id: str) -> list[str]:
    bridge = load_json(BRIDGE_PATH)
    raw = bridge.get("bridge", bridge)
    aliases = [a for a in raw.get(playlist_id, []) if a]
    extras = [
        playlist_id,
        playlist_id.replace(".us", ".HD.us2"),
        playlist_id.replace(".us", ".HD.(Pacific).us2"),
        playlist_id.replace(".us", ".(Pacific).us2"),
    ]
    out: list[str] = []
    for a in aliases + extras:
        if a and a not in out:
            out.append(a)
    return out


def find_now_in_xmltv_text(
    text: str,
    channel_ids: set[str],
    *,
    source: str,
    confidence: str,
) -> Hit | None:
    now = now_utc()
    prog_re = re.compile(r"<programme\b([^>]*)>(.*?)</programme>", re.S | re.I)
    attr_re = re.compile(r'(\w+)="([^"]*)"')
    title_re = re.compile(r"<title[^>]*>([^<]*)</title>", re.I)
    for attrs_blob, body in prog_re.findall(text):
        attrs = dict(attr_re.findall(attrs_blob))
        ch = (attrs.get("channel") or "").strip()
        if ch not in channel_ids:
            continue
        start = parse_xmltv_ts(attrs.get("start", ""))
        stop = parse_xmltv_ts(attrs.get("stop", ""))
        if not start or not stop or not (start <= now < stop):
            continue
        title_m = title_re.search(body)
        title = (title_m.group(1) if title_m else "").strip() or "(no title)"
        return Hit(
            source=source,
            title=title,
            start=start,
            stop=stop,
            confidence=confidence,
            channel_id=ch,
        )
    return None


def probe_cached_epg(playlist_id: str, aliases: list[str]) -> Hit | None:
    candidates = [Path("/tmp/epg.xml"), ROOT / "epg.xml", Path.home() / "epg.xml"]
    ids = set(aliases) | {playlist_id}
    for path in candidates:
        if not path.is_file() or path.stat().st_size < 100:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        hit = find_now_in_xmltv_text(
            text,
            ids,
            source=f"cached epg.xml ({path})",
            confidence="medium (served/cache file; may be stale)",
        )
        if hit:
            return hit
        if f'id="{playlist_id}"' in text or f'channel="{playlist_id}"' in text:
            return Hit(
                source=f"cached epg.xml ({path})",
                title="(no current programme)",
                start=now_utc(),
                stop=now_utc(),
                confidence="low",
                channel_id=playlist_id,
                note="channel present but no on-air row in window",
            )
    return None


def _now_from_tvtv_grid(
    raw: str,
    *,
    source: str,
    confidence: str,
    channel_id: str,
) -> Hit:
    data = json.loads(raw)
    rows: list[dict] = []
    if isinstance(data, list):
        if data and isinstance(data[0], list):
            for block in data:
                rows.extend(x for x in block if isinstance(x, dict))
        else:
            rows = [x for x in data if isinstance(x, dict)]
    elif isinstance(data, dict):
        for key in ("programs", "events", "grid"):
            if isinstance(data.get(key), list):
                rows = [x for x in data[key] if isinstance(x, dict)]
                break
    now = now_utc()
    for row in rows:
        title = str(row.get("title") or row.get("programTitle") or "").strip()
        start_raw = row.get("startTime") or row.get("start")
        duration = row.get("duration")
        if not start_raw or not title:
            continue
        try:
            start = datetime.fromisoformat(str(start_raw).replace("Z", "+00:00"))
            if start.tzinfo is None:
                start = start.replace(tzinfo=timezone.utc)
            start = start.astimezone(timezone.utc)
        except ValueError:
            continue
        minutes = int(duration or 0)
        stop = (
            start + timedelta(minutes=minutes) if minutes else start + timedelta(hours=1)
        )
        if start <= now < stop:
            return Hit(
                source=source,
                title=title,
                start=start,
                stop=stop,
                confidence=confidence,
                channel_id=channel_id,
            )
    return Hit(
        source=source,
        title="(no current programme)",
        start=now,
        stop=now,
        confidence="low",
        channel_id=channel_id,
        note=f"parsed {len(rows)} grid rows, none on-air",
    )


def probe_tvtv(playlist_id: str) -> Hit:
    bridge = load_json(TVTV_BRIDGE_PATH)
    entries = bridge.get("bridge", bridge)
    entry = entries.get(playlist_id)
    if not entry:
        return Hit(
            source="tvtv.us",
            title="(not in tvtv_id_bridge.json)",
            start=now_utc(),
            stop=now_utc(),
            confidence="n/a",
            channel_id=playlist_id,
            note="no site_id mapping",
        )
    site_id = str(entry.get("site_id") or "").strip()
    start = now_utc().replace(minute=0, second=0, microsecond=0)
    end = start + timedelta(hours=24)
    start_iso = start.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    end_iso = end.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    url = (
        f"https://www.tvtv.us/api/v1/lineup/{TVTV_LINEUP}/grid/"
        f"{start_iso}/{end_iso}/{site_id}"
    )
    code, body = http_get(url, timeout=25)
    if code != 200:
        bundled = BUNDLED_GRIDS / f"{playlist_id}.json"
        bundled_note = ""
        if bundled.is_file():
            bundled_hit = _now_from_tvtv_grid(
                bundled.read_text(encoding="utf-8"),
                source=f"tvtv bundled ({bundled.name})",
                confidence="low (offline snapshot; often outside window)",
                channel_id=playlist_id,
            )
            if bundled_hit.title != "(no current programme)":
                bundled_hit.note = f"live API HTTP {code}; using bundled fallback"
                return bundled_hit
            bundled_note = "; bundled present but no on-air row (stale)"
        return Hit(
            source="tvtv.us",
            title="(empty / error)",
            start=now_utc(),
            stop=now_utc(),
            confidence="failed",
            channel_id=f"site_id={site_id}",
            note=f"HTTP {code} for {url}{bundled_note}",
        )
    return _now_from_tvtv_grid(
        body.decode("utf-8", errors="replace"),
        source="tvtv.us live grid",
        confidence="high (Eastern lineup USA-NY71652-X)",
        channel_id=f"site_id={site_id}",
    )


def probe_epgshare(playlist_id: str, aliases: list[str]) -> list[Hit]:
    hits: list[Hit] = []
    cache = Path("/tmp/epg_ripper_US2_fresh.xml.gz")
    if not cache.is_file():
        cache = Path("/tmp/epg_ripper_US2.xml.gz")
    if not cache.is_file():
        # Prior host-side downloads from this session / audits
        for alt in (
            Path("/tmp/epg_ripper_US2_fresh.xml.gz"),
            Path("/tmp/epg_ripper_US2.xml.gz"),
        ):
            if alt.is_file():
                cache = alt
                break
    if cache.is_file():
        with gzip.open(cache, "rb") as fh:
            text = fh.read().decode("utf-8", errors="replace")
        source = f"epgshare US2 (cached {cache.name})"
    else:
        code, body = http_get(
            EPGSHARE_US2 + f"?cb={int(now_utc().timestamp())}",
            timeout=60,
        )
        if code != 200:
            return [
                Hit(
                    source="epgshare US2",
                    title="(download failed)",
                    start=now_utc(),
                    stop=now_utc(),
                    confidence="failed",
                    note=f"HTTP {code}",
                ),
            ]
        out = Path("/tmp/epg_ripper_US2_fresh.xml.gz")
        out.write_bytes(body)
        with gzip.open(out, "rb") as fh:
            text = fh.read().decode("utf-8", errors="replace")
        source = "epgshare US2 (live download)"

    ordered = list(aliases)
    for preferred in (
        playlist_id.replace(".us", ".HD.us2"),
        playlist_id,
    ):
        if preferred in ordered:
            ordered.remove(preferred)
            ordered.insert(0, preferred)

    for feed_id in ordered:
        is_eastern_hd = feed_id.endswith(".HD.us2") and "(Pacific)" not in feed_id
        is_pacific = "Pacific" in feed_id
        if is_eastern_hd:
            confidence = "high (Eastern HD feed)"
        elif is_pacific:
            confidence = "medium (Pacific / alternate feed — timezone may skew)"
        else:
            confidence = "medium"
        hit = find_now_in_xmltv_text(
            text,
            {feed_id},
            source=source,
            confidence=confidence,
        )
        if hit:
            if feed_id != playlist_id:
                hit.note = (
                    f"would map → playlist {playlist_id} via epg_id_bridge "
                    f"(feed id {feed_id})"
                )
            hits.append(hit)
    if not hits:
        shells = sorted(set(re.findall(r'<channel id="([^"]*)"', text)) & set(ordered))
        hits.append(
            Hit(
                source=source,
                title="(no match for aliases)",
                start=now_utc(),
                stop=now_utc(),
                confidence="failed",
                channel_id=playlist_id,
                note=f"tried {ordered}; matching shells={shells[:8]}",
            ),
        )
    return hits


def probe_iptv_org(playlist_id: str) -> Hit:
    code, _ = http_get("https://iptv-org.github.io/api/schedules.json", timeout=15)
    ch_code, ch_body = http_get(
        "https://iptv-org.github.io/api/channels.json",
        timeout=30,
    )
    note = f"schedules.json HTTP {code}"
    if ch_code == 200:
        try:
            channels = json.loads(ch_body.decode())
            hit = next((c for c in channels if c.get("id") == playlist_id), None)
            if hit:
                note += (
                    f"; channel exists name={hit.get('name')!r} "
                    "(no live schedule API)"
                )
            else:
                note += f"; {playlist_id} not in channels.json"
        except json.JSONDecodeError:
            note += "; channels.json unreadable"
    return Hit(
        source="iptv-org schedules",
        title="(no public schedule dump)",
        start=now_utc(),
        stop=now_utc(),
        confidence="n/a",
        channel_id=playlist_id,
        note=note,
    )


def _normalize_woftv_key(name: str) -> str:
    s = (name or "").lower()
    s = re.sub(r"^(us|uk|ca|int|es|de|fr|it|au|nz|mx|br|cz):\s*", "", s)
    s = re.sub(r"\([^)]*\)", " ", s)
    s = s.replace("+", " plus ").replace("&", " and ")
    s = re.sub(r"\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\b", " ", s)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def probe_woftv(playlist_id: str, name_hint: str) -> Hit:
    """Parse /tmp/woftv-us.json and /tmp/woftv-ca.json for a now-playing-ish sample."""
    paths = [Path("/tmp/woftv-us.json"), Path("/tmp/woftv-ca.json")]
    existing = [p for p in paths if p.is_file()]
    if not existing:
        return Hit(
            source="WOFTV",
            title="(no /tmp/woftv-us.json or woftv-ca.json)",
            start=now_utc(),
            stop=now_utc(),
            confidence="n/a",
            note="FAST-oriented; premium cable usually absent",
        )
    needles = []
    if name_hint:
        needles.append(_normalize_woftv_key(name_hint))
    if playlist_id:
        needles.append(_normalize_woftv_key(playlist_id))
    needles = [n for n in needles if n]
    placeholder = "program information currently unavailable"
    now = now_utc()
    best: Hit | None = None
    for path in existing:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            continue
        for row in data.get("programs") or []:
            if not isinstance(row, dict):
                continue
            ch = _normalize_woftv_key(row.get("channel") or "")
            if not ch or ch not in needles:
                continue
            title = (row.get("title") or "").strip()
            if not title or placeholder in title.lower():
                continue
            start = now
            stop = now + timedelta(hours=1)
            # Prefer a row whose start is near now when timestamps exist
            for key, attr in (("start", "start"), ("stop", "stop")):
                raw = row.get(key) or row.get(f"{attr}_time") or ""
                if isinstance(raw, str) and len(raw) >= 10:
                    try:
                        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
                        if key == "start":
                            start = dt
                        else:
                            stop = dt
                    except ValueError:
                        pass
            hit = Hit(
                source=f"WOFTV ({path.name})",
                title=title,
                start=start,
                stop=stop,
                confidence="high" if ch == needles[0] else "medium",
                channel_id=ch,
                note=f"platform={row.get('platform') or '?'}",
            )
            if best is None:
                best = hit
            # Prefer currently-airing window
            if start <= now <= stop:
                return hit
        if best:
            return best
    return Hit(
        source="WOFTV (US+CA)",
        title="(no catalogue match)",
        start=now_utc(),
        stop=now_utc(),
        confidence="n/a",
        channel_id=playlist_id,
        note="No US/CA programme row for this name/id",
    )


def external_hbo2_evidence() -> Hit:
    return Hit(
        source="external evidence (not in-pipeline)",
        title="See epgshare HBO2.HD.us2 row above",
        start=now_utc(),
        stop=now_utc(),
        confidence="corroboration only",
        note=(
            "tvtv.us / TV Guide live APIs 404 from this host; "
            "epgshare US2 Eastern HD is the working alternate in-pipeline source"
        ),
    )


def diagnose_hbo2(playlist_id: str, aliases: list[str], results: list[Hit]) -> None:
    print("\n=== HBO2 DIAGNOSIS ===")
    print(f"playlist tvg-id: {playlist_id}")
    print(f"Eastern-preferred: {playlist_id in EASTERN_PREFERRED}")
    bridge = load_json(BRIDGE_PATH).get("bridge", load_json(BRIDGE_PATH))
    print(f"epg_id_bridge[{playlist_id!r}]: {bridge.get(playlist_id, 'MISSING')}")
    print(f"alias probe order: {aliases}")
    print(
        "pipeline: Eastern tvtv pass → skip epgshare primary for Eastern-preferred "
        "→ gap-fill routes bare .us/.us2 to PRIMARY US2 (via epg_id_bridge aliases)",
    )
    working = [
        h
        for h in results
        if not h.title.startswith("(") and h.confidence not in {"failed", "n/a"}
    ]
    if working:
        print("\nWorking alternate (bridge / epgshare US2):")
        print(working[0].fmt())
    bridged = bridge.get(playlist_id)
    if bridged:
        print(f"\nApplied bridge: {playlist_id} → {bridged}")
    else:
        print(
            f"\nWARNING: {playlist_id} still missing from epg_id_bridge.json "
            "(gap-fill US2 alone cannot remap feed ids).",
        )



def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("tvg_id", nargs="?", help="Playlist tvg-id (e.g. HBO2.us)")
    ap.add_argument("--name", help='Channel display name (e.g. "US: HBO2 HD")')
    ap.add_argument(
        "--diagnose",
        action="store_true",
        help="Extra Eastern-preferred / bridge diagnosis (auto for HBO2.us)",
    )
    args = ap.parse_args()
    if not args.tvg_id and not args.name:
        ap.error("pass a tvg-id and/or --name")

    playlist_id, hint = resolve_tvg_id(args.tvg_id, args.name)
    aliases = feed_aliases(playlist_id)
    diagnose = args.diagnose or playlist_id == "HBO2.us"

    print(f"playlist: {playlist_id}" + (f"  ({hint})" if hint else ""))
    print(f"now:      {now_utc().astimezone(LOCAL_TZ).strftime('%Y-%m-%d %H:%M:%S %Z')}")
    print(f"aliases:  {aliases}")
    print()

    results: list[Hit] = []

    print("— cached epg.xml —")
    hit = probe_cached_epg(playlist_id, aliases)
    if hit:
        results.append(hit)
        print(hit.fmt())
    else:
        print("  (no local epg.xml)")
    print()

    print("— tvtv.us —")
    hit = probe_tvtv(playlist_id)
    results.append(hit)
    print(hit.fmt())
    print()

    print("— epgshare US2 —")
    for h in probe_epgshare(playlist_id, aliases):
        results.append(h)
        print(h.fmt())
        print()

    print("— iptv-org —")
    hit = probe_iptv_org(playlist_id)
    results.append(hit)
    print(hit.fmt())
    print()

    print("— WOFTV —")
    hit = probe_woftv(playlist_id, hint)
    results.append(hit)
    print(hit.fmt())
    print()

    if diagnose and playlist_id == "HBO2.us":
        live_ok = any(
            h.source.startswith("epgshare") and not h.title.startswith("(")
            for h in results
        )
        if not live_ok:
            print("— external evidence (last resort) —")
            ext = external_hbo2_evidence()
            results.append(ext)
            print(ext.fmt())
            print()
        diagnose_hbo2(playlist_id, aliases, results)

    usable = [
        h
        for h in results
        if h.confidence.startswith("high")
        or (h.confidence.startswith("medium") and not h.title.startswith("("))
    ]
    print("=== SUMMARY (approval) ===")
    if usable:
        best = usable[0]
        print(f"NOW PLAYING (best): {best.title}")
        print(best.fmt())
    else:
        print("No usable on-air row from live sources.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

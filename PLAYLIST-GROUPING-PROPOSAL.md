# Playlist Grouping (Implemented)

TiviMate playlist `group-title` organization by country and category. **Implemented** in `GroupTitleResolver.kt`, `ChannelMetaStore.kt`, and `PlaylistBuilder.kt`.

**Source research:** subagent transcript `2e10fc83-a73e-449f-93fe-35ab5f16ac65`.

---

## Approved Rules

### `group-title` format

**`{FLAG_EMOJI} | {COUNTRY_CODE} | {CATEGORY}`**

Example: `🇺🇸 | US | Sports`

| Part | Rule |
|------|------|
| **Separator** | Space-pipe-space: ` \| ` |
| **Flag** | Country flag emoji from tags (or inferred from channel name) |
| **Country** | 2-letter code from flag (`🇺🇸` → `US`). `🌐` / `🌍` → `INT` (International) |
| **Category** | Title Case English label |
| **International** | `🌐 | INT | {CATEGORY}` |
| **Adult** | `Adult` only — no country, no `INT \|` prefix |
| **Unknown country** | `🌐 | INT | {CATEGORY}` |

### Category priority

1. **Adult** — `🔞`, `#NSFW`, `#adult`, or channel name `18+…`
2. **Sports** — any sport tag (`#sports`, `#football`, `#college`, …) **overrides** `#premium`
3. **Premium** — `#premium` when no sport tags
4. Category rollup table (Entertainment, Movies, News, …)
5. **General** — no tags

### Display titles (TiviMate)

**Keep** `[Category]` suffix on channel names (e.g. `ACC Network USA [Sports]`). Do **not** drop the suffix.

### Sort order (TiviMate sidebar)

1. Country A→Z (by country code)
2. Category priority: **Sports** first, then Entertainment → Movies → News → … → **Premium** → **General** last
3. **Adult** group last

---

## Data sources

| Source | Role |
|--------|------|
| `app/src/main/assets/meta.json` | Tags keyed by channel name (~830 entries), bundled from Linux app |
| Upstream `/api/channels` | Channel `id` + `name` |
| `ChannelMetaStore` | Merges meta tags at channel load (matches Linux `_channel_from_row`) |
| Name parser | Fallback when no flag in tags (`USA`, `France`, `Poland`, `DE`, …) |

### Tag schema

```json
"ACC Network USA": {
  "tags": ["🇺🇸", "#sports", "#college"]
}
```

| Tag type | Examples | Role |
|----------|----------|------|
| Flag emoji | `🇺🇸`, `🇬🇧`, `🇨🇦` | Country marker |
| Globe | `🌐`, `🌍` | International |
| Hashtags | `#sports`, `#movies`, `#premium` | Category |
| Adult | `🔞`, `#NSFW`, `#adult` | `Adult` group |

---

## Verified examples

| Channel | Tags | `group-title` | Display title |
|---------|------|---------------|-----------------|
| ACC Network USA | 🇺🇸 #sports #college | `🇺🇸 \| US \| Sports` | `ACC Network USA [Sports]` |
| beIN Sports MAX 4 France | 🇫🇷 #sports #premium | `🇫🇷 \| FR \| Sports` | `beIN Sports MAX 4 France [Sports]` |
| Arena Sport 1 Premium | 🌐 #sports #premium | `🌐 \| INT \| Sports` | `Arena Sport 1 Premium [Sports]` |
| 18+ bucket | 🔞 #NSFW #adult | `Adult` | `18+ … [Adult]` |

---

## Sample M3U entries

```m3u
#EXTM3U url-tvg="http://127.0.0.1:8787/epg.xml" x-tvg-url="http://127.0.0.1:8787/epg.xml"
#EXTINF:-1 tvg-id="..." tvg-name="TSN 1 CA" tvg-logo="..." group-title="🇨🇦 | CA | Sports" tvg-chno="111",TSN 1 CA [Sports]
http://127.0.0.1:8787/tivimate-stream/111.m3u8|User-Agent=...|Referer=...|Origin=...
#EXTINF:-1 tvg-id="..." tvg-name="ACC Network USA" tvg-logo="..." group-title="🇺🇸 | US | Sports" tvg-chno="...",ACC Network USA [Sports]
#EXTINF:-1 tvg-id="..." tvg-name="beIN Sports MAX 4 France" tvg-logo="..." group-title="🇫🇷 | FR | Sports" tvg-chno="494",beIN Sports MAX 4 France [Sports]
#EXTINF:-1 tvg-logo="..." group-title="🌐 | INT | General" tvg-chno="155",3 Schweiz
#EXTINF:-1 tvg-logo="..." group-title="Adult" tvg-chno="...",18+ Channel Name [Adult]
```

---

## Category mapping

| Raw `#tag(s)` | Display category |
|---------------|------------------|
| `#sports`, `#football`, `#cricket`, `#tennis`, `#motorsport`, `#f1`, `#college`, `#golf`, `#basketball`, `#hockey`, `#rugby`, `#boxing`, `#mma`, `#baseball` | **Sports** |
| `#live` | **Sports** if any sport tag; else **Entertainment** |
| `#entertainment`, `#general`, `#variety`, `#drama`, `#comedy`, … | **Entertainment** |
| `#movies`, `#action`, `#thriller`, `#horror`, … | **Movies** |
| `#news`, `#local`, `#international`, `#politics`, `#public` | **News** |
| `#documentary`, `#crime` | **Documentary** |
| `#culture`, `#arts` | **Culture** |
| `#music` | **Music** |
| `#kids` | **Kids** |
| `#regional` | **Regional** |
| `#premium` | **Premium** (unless sport tags present) |
| `#hd` | *(ignored)* |
| `#NSFW`, `#adult`, `🔞` | **Adult** |
| *(no tags)* | **General** |

---

## Implementation files

| File | Purpose |
|------|---------|
| `ChannelMetaStore.kt` | Load `meta.json`, tag lookup by channel name |
| `GroupTitleResolver.kt` | Flag→ISO map, category rollup, name fallback, sort keys |
| `PlaylistBuilder.kt` | `group-title`, `[Category]` display suffix, sorted output |
| `DaddyLiveClient.kt` | Enrich channels with meta tags at load time |

---

## Changes from original proposal

| Topic | Proposal | **Approved** |
|-------|----------|--------------|
| Format | `US \| Sports` | `🇺🇸 \| US \| Sports` |
| Category priority | Premium > Sports | **Sports > Premium** |
| Adult | `INT \| Adult` | **`Adult` only** |
| Display title | Drop `[Category]` suffix | **Keep suffix** |

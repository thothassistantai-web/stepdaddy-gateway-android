# Playlist Grouping (Implemented)

TiviMate playlist `group-title` organization by **flat genre groups** (provider-style filter tabs). **Implemented** in `GroupTitleResolver.kt`, `ChannelTitleNormalizer.kt`, `ChannelMetaStore.kt`, and `PlaylistBuilder.kt`.

**Source research:** `provider-channel-sort-research.md`, `epg-sort-style-samples.md`.

---

## Approved Rules

### `group-title` format

**Flat category label only** — no country prefix in the group name.

Examples: `Sports`, `News`, `Local Channels`, `En Español`

| Group | Rules |
|-------|-------|
| **Local Channels** | `#local` / `#regional` affiliates — **excluding** news (`#news` → News) |
| **Entertainment** | General entertainment; mature cartoons (`#animation` + `#adult`) |
| **Movies** | **Only** `#premium` + movie-tier tags (`#movies`, `#action`, …) |
| **Music** | `#music` |
| **Kids** | `#kids` / `#cartoons` — excludes mature cartoons |
| **Sports** | All sport tags; **overrides** `#premium` |
| **News** | `#news` and local news affiliates |
| **Documentary** | `#documentary`, `#crime`, `#culture`, `#arts`, `#history` |
| **International** | Fallback for `🌐` / unknown country with no genre tags |
| **En Español** | `#Spanish` / `#spanish` / `#latino` |
| **XXX Adult** | `18+…`, `🔞`, `#NSFW`, `#adult` (not mature cartoons) |

### Display title format (TiviMate)

**`{Normalized Name} {FLAG} {CODE}`** at end of channel name.

Example: `ACC Network USA 🇺🇸 US`

| Part | Rule |
|------|------|
| **Normalize** | Strip `[Category]` suffix; fix known spelling; trim redundant trailing country before re-append |
| **Suffix** | Flag emoji + 2-letter ISO code (`🇺🇸 US`, `🇨🇦 CA`, `🇬🇧 UK`) |
| **CA** | Sorted into **same genre groups as US** (Sports, News, …) with `🇨🇦 CA` suffix |
| **Non-US non-CA** | Same genre groups with country flag at end |
| **XXX Adult** | No country suffix |

### Category priority

1. **XXX Adult**
2. **En Español** (`#Spanish` / `#spanish`)
3. **Sports** (overrides `#premium`)
4. **News** (overrides `#local`)
5. **Local Channels** (`#local`, `#regional`)
6. **Movies** (`#premium` + movie-tier tags only)
7. **Kids** / **Music** / **Documentary**
8. Tag rollup → **Entertainment**
9. **International** (INT country, no tags)

### Sort order (TiviMate sidebar)

Set TiviMate → Playlists → Manage Groups → **Groups sorting: By order in playlist**.

Playlist emits channels in this group order (then `tvg-chno` within each group):

1. Entertainment
2. Movies
3. Local Channels
4. News
5. Sports
6. Kids
7. Documentary
8. Music
9. 📡 | Extra | 24/7
10. International
11. En Español
12. 🎟️ Special Events (DaddyLive schedule + TheTvApp; sorted by sport/league)
13. XXX Adult

Legacy labels `Locals` → Local Channels slot; `Premium` → Movies slot; `🎬 | Adult Swim | Marathon` → Entertainment; `🏈 | Sports | TheTvApp` → Special Events.

### Adult Swim 24/7 marathons

Published under **Entertainment** (not Extra 24/7). Xtream-style display title:

`US: 24/7 : Adultswim {CHANNEL NAME} ᴿᴬᵂ`

### Special Events (DaddyLive + TheTvApp)

Live event supplements (`dlhd-guide:*`, `dlhd-event:*`, `sport:*` ids) use group **🎟️ Special Events** at the bottom of the sidebar (above XXX Adult).

- **Guide rows** (`dlhd-guide:*`) — one per event category (PPV, Tennis, …) with full schedule EPG from DaddyLive `tv.json` / `tv2.json`
- **Stream rows** — DaddyLive numeric ids use `/tivimate-stream/{id}`; tv2 path ids use `/dlhd-event-stream/{token}`; TheTvApp uses `sport:*` embed URLs

Display title: `US: {LEAGUE} {EVENT NAME} ᴸᴵⱽᴱ` (guides: `US: {CATEGORY} SCHEDULE ᴸᴵⱽᴱ`)

Within each group: US → CA → other countries A→Z, then channel name.

---

## Data sources

| Source | Role |
|--------|------|
| `app/src/main/assets/meta.json` | Tags keyed by channel name (~830 entries) |
| Upstream `/api/channels` | Channel `id` + `name` |
| `ChannelMetaStore` | Merges meta tags at channel load |
| Name parser | Fallback when no flag in tags (`USA`, `France`, `CA`, …) |

### Tag schema

```json
"ACC Network USA": {
  "tags": ["🇺🇸", "#sports", "#college"]
}
```

---

## Verified examples

| Channel | Tags | `group-title` | Display title |
|---------|------|---------------|---------------|
| ACC Network USA | 🇺🇸 #sports #college | `Sports` | `ACC Network USA 🇺🇸 US` |
| beIN Sports MAX 4 France | 🇫🇷 #sports #premium | `Sports` | `beIN Sports MAX 4 France 🇫🇷 FR` |
| Sportsnet 360 | 🇨🇦 #sports | `Sports` | `Sportsnet 360 🇨🇦 CA` |
| ABC NY USA | 🇺🇸 #local #news | `News` | `ABC NY USA 🇺🇸 US` |
| HBO Poland | 🇵🇱 #movies #premium | `Movies` | `HBO Poland 🇵🇱 PL` |
| Telemundo | 🇺🇸 #Spanish #news | `En Español` | `Telemundo 🇺🇸 US` |
| Adult Swim | 🇺🇸 #animation #adult | `Entertainment` | `Adult Swim 🇺🇸 US` |
| 18+ bucket | 🔞 #NSFW #adult | `XXX Adult` | `18+ Channel` |

---

## Sample M3U entries

```m3u
#EXTM3U url-tvg="http://127.0.0.1:8787/epg.xml" x-tvg-url="http://127.0.0.1:8787/epg.xml"
#EXTINF:-1 tvg-id="..." tvg-name="Sportsnet 360" tvg-logo="..." group-title="Sports" tvg-chno="111",Sportsnet 360 🇨🇦 CA
#EXTINF:-1 tvg-id="..." tvg-name="ACC Network USA" tvg-logo="..." group-title="Sports" tvg-chno="...",ACC Network USA 🇺🇸 US
#EXTINF:-1 tvg-id="..." tvg-name="beIN Sports MAX 4 France" tvg-logo="..." group-title="Sports" tvg-chno="494",beIN Sports MAX 4 France 🇫🇷 FR
#EXTINF:-1 tvg-logo="..." group-title="International" tvg-chno="155",3 Schweiz 🌐 INT
#EXTINF:-1 tvg-logo="..." group-title="XXX Adult" tvg-chno="...",18+ Channel Name
```

---

## Implementation files

| File | Purpose |
|------|---------|
| `GroupTitleResolver.kt` | Flat genre groups, category priority, country resolution, sort keys |
| `ChannelTitleNormalizer.kt` | Display title normalization + flag/country suffix |
| `ChannelMetaStore.kt` | Load `meta.json`, tag lookup by channel name |
| `PlaylistBuilder.kt` | `group-title`, normalized display titles, sorted output |
| `DaddyLiveClient.kt` | Enrich channels with meta tags at load time |

---

## Changes from country×category scheme

| Topic | Previous | **Approved** |
|-------|----------|--------------|
| `group-title` | `🇺🇸 \| US \| Sports` | **`Sports`** (flat genre) |
| Country | In group name | **End of display title** (`🇺🇸 US`) |
| CA | `🇨🇦 \| CA \| Sports` | **`Sports`** + `🇨🇦 CA` on title |
| Premium | Separate Premium group | **Movies** only when `#premium` + movie tier |
| Local | Mixed into News | **`Local Channels`** group (news → News) |
| Spanish | Entertainment | **`En Español`** group |
| Adult | `Adult` | **`XXX Adult`** |
| Display `[Category]` | Kept | **Stripped**; country suffix instead |

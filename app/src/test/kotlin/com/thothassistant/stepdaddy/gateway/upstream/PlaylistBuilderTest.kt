package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.epg.PlaylistEpgHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBuilderTest {
    private fun ch(
        id: String,
        name: String,
        tags: List<String> = emptyList(),
        tvgId: String? = null,
    ) = Channel(id = id, name = name, tags = tags, tvgId = tvgId)

    @Test
    fun `tivimate playlist emits groups in sidebar order then ascending tvg-chno`() {
        val channels = listOf(
            ch("espn", "ESPN USA", listOf("🇺🇸", "#sports")),
            ch("cbs", "CBS USA", listOf("🇺🇸", "#local")),
            ch("cnn", "CNN USA", listOf("🇺🇸", "#news")),
            ch("hbo", "HBO USA", listOf("🇺🇸", "#movies", "#premium")),
            ch("fx", "FX USA", listOf("🇺🇸", "#entertainment")),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
        )

        val rows = Regex("""group-title="([^"]+)".*?tvg-chno="(\d+)"""")
            .findAll(playlist)
            .map { GroupTitleResolver.groupSortOrder(it.groupValues[1]) to it.groupValues[2].toInt() }
            .toList()

        assertTrue(rows.isNotEmpty())
        assertTrue(rows.zipWithNext().all { (left, right) ->
            left.first < right.first || (left.first == right.first && left.second <= right.second)
        })
        assertEquals(0, rows.first().first)
    }

    @Test
    fun `iptv supplement continues channel numbers within category`() {
        val channels = listOf(
            ch("mtv", "MTV USA", listOf("🇺🇸", "#music")),
        )
        val supplements = listOf(
            SupplementChannel(
                id = "iptv:abc123",
                name = "Vevo Pop (720p)",
                groupTitle = GroupTitleResolver.MUSIC,
                streamUrl = "https://example.com/vevo.m3u8",
                tags = listOf("🇺🇸", "#music"),
                providerTag = "Pluto",
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.LEGACY,
        )

        assertTrue(playlist.contains("group-title=\"Music\""))
        assertTrue(playlist.contains("Vevo Pop (720p) 🇺🇸 US Pluto"))
        val chnos = Regex("""tvg-chno="(\d+)"""")
            .findAll(playlist)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals(listOf(210, 211), chnos)
    }

    @Test
    fun `iptv supplement stored as movies but resolved entertainment uses bulk band`() {
        val supplements = listOf(
            SupplementChannel(
                id = "iptv:movies1",
                name = "24 Hour Free Movies (720p)",
                groupTitle = GroupTitleResolver.MOVIES,
                streamUrl = "https://example.com/movies.m3u8",
                tags = listOf("🇺🇸", "#movies", "#entertainment"),
                providerTag = "Distro",
            ),
        )

        val numbers = ChannelNumberResolver.assignSupplements(
            channels = emptyList(),
            supplements = supplements,
            groupFor = ChannelNumberResolver::supplementGroup,
        )

        assertTrue(numbers.getValue("iptv:movies1") >= 1600)
    }

    @Test
    fun `sidecar supplement uses stored group title for numbering`() {
        val supplements = listOf(
            SupplementChannel(
                id = "sup:abc123",
                name = "FS1 (MOJ)",
                groupTitle = GroupTitleResolver.SPORTS,
                streamUrl = "http://fl1.moveonjoy.com/FS1/index.m3u8",
            ),
        )

        val numbers = ChannelNumberResolver.assignSupplements(
            channels = emptyList(),
            supplements = supplements,
            groupFor = ChannelNumberResolver::supplementGroup,
        )

        assertTrue(numbers.getValue("sup:abc123") in 500..1599)
    }

    @Test
    fun `supplements number US before UK within same category`() {
        val supplements = listOf(
            SupplementChannel(
                id = "iptv:uk1",
                name = "BBC Radio 1 UK",
                groupTitle = GroupTitleResolver.MUSIC,
                streamUrl = "https://example.com/uk.m3u8",
                tags = listOf("#music"),
                providerTag = "BBC",
            ),
            SupplementChannel(
                id = "iptv:us1",
                name = "Vevo Pop USA",
                groupTitle = GroupTitleResolver.MUSIC,
                streamUrl = "https://example.com/us.m3u8",
                tags = listOf("#music"),
                providerTag = "Pluto",
            ),
        )

        val numbers = ChannelNumberResolver.assignSupplements(
            channels = emptyList(),
            supplements = supplements,
            groupFor = ChannelNumberResolver::supplementGroup,
        )

        assertTrue(numbers.getValue("iptv:us1") < numbers.getValue("iptv:uk1"))
    }

    @Test
    fun `xtream category style formats cable adult swim and special events`() {
        val channels = listOf(
            ch("fox", "Fox News Channel", listOf("🇺🇸", "#news"), tvgId = "FoxNews.us"),
        )
        val supplements = listOf(
            SupplementChannel(
                id = "adultswim:rick-and-morty",
                name = "Rick and Morty",
                groupTitle = GroupTitleResolver.ENTERTAINMENT,
                streamUrl = "https://example.com/as.m3u8",
                tags = listOf("🇺🇸", "#entertainment", "#animation"),
                providerTag = "Adult Swim",
                tvgId = "AdultSwimRickandMorty.us",
            ),
            SupplementChannel(
                id = "sport:abc123",
                name = "Lakers vs Celtics",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "https://example.com/nba.m3u8",
                providerTag = "NBA",
                eventSourceUrl = "https://thetvapp.link/nba/lakers-celtics/123",
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        )

        assertTrue(playlist.contains("group-title=\"News\""))
        assertTrue(playlist.contains("US: FOX NEWS CHANNEL HD"))
        assertTrue(playlist.contains("tvg-name=\"US: FOX NEWS CHANNEL HD\""))
        assertTrue(playlist.contains("group-title=\"Entertainment\""))
        assertTrue(playlist.contains("US: 24/7 : Adultswim RICK AND MORTY ᴿᴬᵂ"))
        assertTrue(playlist.contains("group-title=\"🎟️ Special Events\""))
        assertTrue(playlist.contains("US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ"))
    }

    @Test
    fun `special events sort by league before channel number`() {
        val supplements = listOf(
            SupplementChannel(
                id = "sport:nfl",
                name = "Chiefs vs Bills",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "https://example.com/nfl.m3u8",
                providerTag = "NFL",
            ),
            SupplementChannel(
                id = "sport:nba",
                name = "Lakers vs Celtics",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "https://example.com/nba.m3u8",
                providerTag = "NBA",
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        )

        val nflIndex = playlist.indexOf("US: NFL CHIEFS VS BILLS")
        val nbaIndex = playlist.indexOf("US: NBA LAKERS VS CELTICS")
        assertTrue(nflIndex >= 0 && nbaIndex >= 0)
        assertTrue(nflIndex < nbaIndex)
    }

    @Test
    fun `special events guide precedes streams in same category`() {
        val supplements = listOf(
            SupplementChannel(
                id = "dlhd-event:swim1",
                name = "Final Heat",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "SWIMMING",
                eventSourceUrl = "Swimming|Sunday|14:00|Swimming : Final Heat",
                dlhdEventStreamKey = "tv|201",
            ),
            SupplementChannel(
                id = "dlhd-guide:swimming",
                name = "🏊 Swimming Schedule",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "SWIMMING",
                tags = listOf("#events", "#guide"),
            ),
            SupplementChannel(
                id = "dlhd-event:golf1",
                name = "Round 1",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "GOLF",
                eventSourceUrl = "Golf|Sunday|15:00|Golf : Round 1",
                dlhdEventStreamKey = "tv|202",
            ),
            SupplementChannel(
                id = "dlhd-guide:golf",
                name = "⛳ Golf Schedule",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "GOLF",
                tags = listOf("#events", "#guide"),
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        )

        val golfGuide = playlist.indexOf("GOLF SCHEDULE")
        val golfEvent = playlist.indexOf("ROUND 1")
        val swimGuide = playlist.indexOf("SWIMMING SCHEDULE")
        val swimEvent = playlist.indexOf("FINAL HEAT")
        assertTrue(playlist.contains("dlhd-event-guide/golf.mp4|"))
        assertTrue(playlist.contains("dlhd-event-guide/swimming.mp4|"))
        assertFalse(playlist.contains("dlhd-event-guide/golf.m3u8"))
        assertTrue(golfGuide >= 0 && golfEvent > golfGuide)
        assertTrue(swimGuide >= 0 && swimEvent > swimGuide)
        assertTrue(golfGuide < swimGuide)
    }

    @Test
    fun `external epg urls are embedded comma separated in playlist header`() {
        val urls = listOf(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
        )
        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = listOf(ch("fox", "Fox News", listOf("🇺🇸", "#news"), tvgId = "FoxNews.us")),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            epgUrl = PlaylistEpgHeader.joinUrls(urls),
        )
        assertTrue(playlist.contains("url-tvg=\"${urls[0]},${urls[1]}\""))
    }

    @Test
    fun `gateway epg default uses loopback endpoint in header when url provided`() {
        val playlist = PlaylistBuilder.minimalPlaylist(
            "http://127.0.0.1:3000",
            "http://127.0.0.1:3000/epg.xml",
        )
        assertTrue(playlist.contains("url-tvg=\"http://127.0.0.1:3000/epg.xml\""))
    }

    @Test
    fun `empty epg url omits tvg attributes`() {
        val playlist = PlaylistBuilder.minimalPlaylist("http://127.0.0.1:3000", null)
        assertEquals("#EXTM3U\n", playlist)
    }

    @Test
    fun `ntv supplement uses gateway stream route with referer`() {
        val supplements = listOf(
            SupplementChannel(
                id = "ntv:abc123",
                name = "ESPN",
                groupTitle = NtvCxCdnLiveConfig.GROUP_TITLE,
                streamUrl = "",
                providerTag = "CDN",
                referer = NtvCxCdnLiveConfig.REFERER,
                origin = NtvCxCdnLiveConfig.ORIGIN,
                ntvCdnLiveKey = "cdnlive|ESPN|us",
            ),
        )
        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = supplements,
        )
        assertTrue(playlist.contains("http://127.0.0.1:3000/ntv-stream/abc123.m3u8"))
        assertTrue(playlist.contains("Referer=${NtvCxCdnLiveConfig.REFERER}"))
        assertTrue(playlist.contains("INT: ESPN ᴿᴬᵂ"))
    }
}

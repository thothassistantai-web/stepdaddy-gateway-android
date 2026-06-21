package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
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
    fun `tivimate playlist emits channels in ascending tvg-chno order`() {
        val channels = listOf(
            ch("espn", "ESPN USA", listOf("🇺🇸", "#sports")),
            ch("cbs", "CBS USA", listOf("🇺🇸", "#local")),
            ch("cnn", "CNN USA", listOf("🇺🇸", "#news")),
            ch("nbc", "NBC USA", listOf("🇺🇸", "#local")),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
        )

        val chnos = Regex("""tvg-chno="(\d+)"""")
            .findAll(playlist)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertTrue(chnos.containsAll(listOf(100)))
        assertTrue(chnos.filter { it in 1..499 }.size >= 2)
        assertTrue(chnos.any { it == 70 || it in 500..1599 })
        assertTrue(chnos.zipWithNext().all { (left, right) -> left <= right })
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
        assertTrue(playlist.contains("ESPN CDN"))
    }
}

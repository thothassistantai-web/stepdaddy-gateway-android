package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementDedupTest {
    private fun ch(id: String, name: String, tvgId: String? = null) =
        Channel(id = id, name = name, tvgId = tvgId)

    private fun mapTestChannel(entry: M3uParser.Entry, defaultGroup: String) =
        SupplementChannel(
            id = "test:${entry.name}",
            name = entry.name.trim(),
            tvgId = entry.tvgId,
            groupTitle = defaultGroup.ifEmpty { SupplementConfig.GROUP_PREFIX },
            streamUrl = entry.streamUrl,
        )

    @Test
    fun `filter drops exact normalized name matches`() {
        val daddy = listOf(ch("1", "ESPN USA", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN USA", streamUrl = "https://example.com/espn.m3u8"),
            M3uParser.Entry(name = "DAZN USA", streamUrl = "https://example.com/dazn.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
            mapChannel = ::mapTestChannel,
        )
        assertEquals(1, out.channels.size)
        assertEquals("DAZN USA", out.channels.first().name)
    }

    @Test
    fun `CONSOLIDATE_FALLBACKS attaches daddy overlap instead of publishing row`() {
        val daddy = listOf(ch("70", "ESPN USA", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN USA", streamUrl = "https://example.com/espn-fast.m3u8"),
            M3uParser.Entry(name = "DAZN USA", streamUrl = "https://example.com/dazn.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.CONSOLIDATE_FALLBACKS,
            mapChannel = ::mapTestChannel,
        )
        assertEquals(1, out.channels.size)
        assertEquals("DAZN USA", out.channels.first().name)
        assertEquals(1, out.daddyFallbacks["70"]?.size)
        assertEquals("https://example.com/espn-fast.m3u8", out.daddyFallbacks["70"]?.first()?.streamUrl)
    }

    @Test
    fun `CONSOLIDATE_FALLBACKS attaches internal iptv duplicate to primary row`() {
        val entries = listOf(
            M3uParser.Entry(name = "Pluto TV", tvgId = "Pluto.us", streamUrl = "https://a.example/a.m3u8"),
            M3uParser.Entry(name = "Pluto TV Mirror", tvgId = "Pluto.us", streamUrl = "https://b.example/b.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries = entries,
            daddyChannels = emptyList(),
            importMode = SupplementImportMode.CONSOLIDATE_FALLBACKS,
            mapChannel = ::mapTestChannel,
        )
        assertEquals(1, out.channels.size)
        assertEquals(1, out.channels.first().fallbackMirrors.size)
        assertEquals("https://b.example/b.m3u8", out.channels.first().fallbackMirrors.first().streamUrl)
    }

    @Test
    fun `filter drops exact tvg-id matches`() {
        val daddy = listOf(ch("1", "ESPN", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN Network", tvgId = "ESPN.us", streamUrl = "https://example.com/espn.m3u8"),
            M3uParser.Entry(name = "New Channel", tvgId = "NEW.us", streamUrl = "https://example.com/new.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
            mapChannel = ::mapTestChannel,
        )
        assertEquals(1, out.channels.size)
        assertEquals("NEW.us", out.channels.first().tvgId)
    }

    @Test
    fun `filter drops iptv-org tvg-id base match with at suffix`() {
        val daddy = listOf(ch("1", "BBC One", "BBCOne.uk"))
        val entries = listOf(
            M3uParser.Entry(
                name = "BBC One HD",
                tvgId = "BBCOne.uk@HD",
                streamUrl = "https://example.com/bbc.m3u8",
            ),
            M3uParser.Entry(
                name = "Sky News",
                tvgId = "SkyNews.uk@SD",
                streamUrl = "https://example.com/sky.m3u8",
            ),
        )
        val out = SupplementDedup.filterNewChannels(
            entries = entries,
            daddyChannels = daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
        ) { entry, _ ->
            SupplementChannel(
                id = "iptv:test",
                name = entry.name,
                tvgId = entry.tvgId,
                groupTitle = "test",
                streamUrl = entry.streamUrl,
            )
        }
        assertEquals(1, out.channels.size)
        assertEquals("Sky News", out.channels.first().name)
    }

    @Test
    fun `FULL_CATALOG keeps daddy name matches`() {
        val daddy = listOf(ch("1", "ESPN USA", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN USA", streamUrl = "https://example.com/espn.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.FULL_CATALOG,
            mapChannel = ::mapTestChannel,
        )
        assertEquals(1, out.channels.size)
    }

    @Test
    fun `m3u parser reads extinf attributes`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ABC.us" tvg-logo="http://logo" group-title="US",ABC East
            http://stream.example/live.m3u8
        """.trimIndent()
        val entries = M3uParser.parse(m3u)
        assertEquals(1, entries.size)
        assertEquals("ABC East", entries[0].name)
        assertEquals("ABC.us", entries[0].tvgId)
        assertEquals("http://stream.example/live.m3u8", entries[0].streamUrl)
    }
}

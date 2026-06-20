package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementDedupTest {
    private fun ch(id: String, name: String, tvgId: String? = null) =
        Channel(id = id, name = name, tvgId = tvgId)

    @Test
    fun `filter drops exact normalized name matches`() {
        val daddy = listOf(ch("1", "ESPN USA", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN USA", streamUrl = "http://fl25.moveonjoy.com/ESPN/index.m3u8"),
            M3uParser.Entry(name = "DAZN USA", streamUrl = "http://fl25.moveonjoy.com/DAZN/index.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
        )
        assertEquals(1, out.size)
        assertEquals("DAZN USA", out.first().name)
        assertTrue(out.first().id.startsWith("sup:"))
    }

    @Test
    fun `filter drops exact tvg-id matches`() {
        val daddy = listOf(ch("1", "ESPN", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN Network", tvgId = "ESPN.us", streamUrl = "http://fl25.moveonjoy.com/ESPN/index.m3u8"),
            M3uParser.Entry(name = "New Channel", tvgId = "NEW.us", streamUrl = "http://fl25.moveonjoy.com/NEW/index.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
        )
        assertEquals(1, out.size)
        assertEquals("NEW.us", out.first().tvgId)
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
            applySidecarProviderFilter = false,
        ) { entry, _ ->
            com.thothassistant.stepdaddy.gateway.model.SupplementChannel(
                id = "iptv:test",
                name = entry.name,
                tvgId = entry.tvgId,
                groupTitle = "test",
                streamUrl = entry.streamUrl,
            )
        }
        assertEquals(1, out.size)
        assertEquals("Sky News", out.first().name)
    }

    @Test
    fun `filter drops thetvapp proxy before dedup`() {
        val daddy = listOf(ch("1", "ESPN USA"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN Extra", streamUrl = "http://x/channel?url=https%3A%2F%2Fthetvapp.link%2Ftv%2Fespn-live-stream%2F"),
            M3uParser.Entry(name = "MOJ CNN", streamUrl = "http://fl25.moveonjoy.com/CNN/index.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
        )
        assertEquals(1, out.size)
        assertEquals("MOJ CNN", out.first().name)
        assertEquals(SupplementConfig.MOVEONJOY_REFERER, out.first().referer)
    }

    @Test
    fun `FULL_CATALOG keeps daddy name matches`() {
        val daddy = listOf(ch("1", "ESPN USA", "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(name = "ESPN USA", streamUrl = "http://fl25.moveonjoy.com/ESPN/index.m3u8"),
        )
        val out = SupplementDedup.filterNewChannels(
            entries,
            daddy,
            importMode = SupplementImportMode.FULL_CATALOG,
        )
        assertEquals(1, out.size)
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

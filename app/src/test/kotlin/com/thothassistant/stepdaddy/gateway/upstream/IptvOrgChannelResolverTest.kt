package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvOrgChannelResolverTest {
    private val catalog = IptvOrgChannelLookup { tvgId ->
        when (tvgId?.substringBefore('@')?.lowercase()) {
            "vevopop.us" -> IptvOrgChannelCatalog.Row(
                categories = listOf("music"),
                country = "US",
                isNsfw = false,
            )
            "48hours.us" -> IptvOrgChannelCatalog.Row(
                categories = listOf("series"),
                country = "US",
                isNsfw = false,
            )
            else -> null
        }
    }

    private val resolver = IptvOrgChannelResolver(catalog)

    @Test
    fun `vevo pop resolves to Music with US flag`() {
        val entry = M3uParser.Entry(
            name = "Vevo Pop (720p)",
            tvgId = "VevoPop.us@SD",
            streamUrl = "https://example.com/a.m3u8",
        )
        val resolution = resolver.resolve(entry, "us_pluto.m3u")
        assertEquals(GroupTitleResolver.MUSIC, resolution.groupTitle)
        assertEquals("US", resolution.countryCode)
    }

    @Test
    fun `name heuristic maps news without tvg-id`() {
        val entry = M3uParser.Entry(
            name = "City News Live",
            streamUrl = "https://example.com/b.m3u8",
        )
        val resolution = resolver.resolve(entry, "us_firetv.m3u")
        assertEquals(GroupTitleResolver.NEWS, resolution.groupTitle)
    }

    @Test
    fun `us local playlist adds local category`() {
        val entry = M3uParser.Entry(
            name = "WABC-DT1",
            streamUrl = "https://example.com/c.m3u8",
        )
        val resolution = resolver.resolve(entry, "us_local.m3u")
        assertEquals(GroupTitleResolver.LOCAL_CHANNELS, resolution.groupTitle)
    }
}

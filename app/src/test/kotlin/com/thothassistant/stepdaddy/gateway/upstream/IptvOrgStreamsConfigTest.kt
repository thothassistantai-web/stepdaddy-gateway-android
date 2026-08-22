package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOrgStreamsConfigTest {
    @Test
    fun `playlist range is uk through us_xumo`() {
        val files = IptvOrgStreamsConfig.PLAYLIST_FILES
        assertEquals("uk.m3u", files.first())
        assertEquals("us_xumo.m3u", files.last())
        assertEquals(39, files.size)
    }

    @Test
    fun `raw url points at github master`() {
        val url = IptvOrgStreamsConfig.rawUrl("uk_pluto.m3u")
        assertTrue(url.contains("raw.githubusercontent.com/iptv-org/iptv/master/streams/uk_pluto.m3u"))
    }

    @Test
    fun `group title uses playlist slug`() {
        assertEquals("🌐 | iptv-org | Uk Pluto", IptvOrgStreamsConfig.groupTitleFor("uk_pluto.m3u"))
        assertEquals("🌐 | iptv-org | Us Xumo", IptvOrgStreamsConfig.groupTitleFor("us_xumo.m3u"))
    }

    @Test
    fun `candidate urls prefer jsdelivr before github raw`() {
        val urls = IptvOrgStreamsConfig.candidateUrls("uk_bbc.m3u")
        assertTrue(urls.first().startsWith("https://cdn.jsdelivr.net/"))
        assertTrue(urls.any { it.contains("gcore.jsdelivr.net") })
        assertEquals(
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk_bbc.m3u",
            urls.last(),
        )
    }

    @Test
    fun `FETCH_BUDGET_MS leaves headroom before supplement sync ceiling`() {
        assertTrue(IptvOrgStreamsConfig.FETCH_BUDGET_MS > 0L)
        assertTrue(IptvOrgStreamsConfig.FETCH_BUDGET_MS <= 360_000L)
    }
}

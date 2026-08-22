package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDaddyFallbacksBuilderTest {
    @Test
    fun `FULL_CATALOG published ntv row attaches smart daddy fallback`() {
        val daddy = listOf(
            Channel(id = "70", name = "ESPN USA", tags = listOf("#sports", "#us")),
        )
        val supplements = listOf(
            SupplementChannel(
                id = "ntv:abc",
                name = "ESPN",
                groupTitle = "ntv",
                streamUrl = "",
                tags = listOf("#us"),
                regionCode = "US",
                ntvCdnLiveKey = "cdnlive|ESPN|us|",
                providerTag = "CDN",
            ),
            SupplementChannel(
                id = "ntv:local",
                name = "Obscure Local 99",
                groupTitle = "ntv",
                streamUrl = "",
                ntvCdnLiveKey = "cdnlive|local|us|",
            ),
        )
        val fallbacks = SmartDaddyFallbacksBuilder.fromSupplements(daddy, supplements)
        assertEquals(1, fallbacks.size)
        assertEquals(1, fallbacks["70"]?.size)
        assertTrue(fallbacks["70"]?.first()?.ntvCdnLiveKey?.startsWith("cdnlive|") == true)
    }

    @Test
    fun `special events and vod are skipped`() {
        val daddy = listOf(Channel(id = "1", name = "ESPN USA"))
        val supplements = listOf(
            SupplementChannel(
                id = "dlhd-guide:espn",
                name = "ESPN USA",
                groupTitle = "events",
                streamUrl = "https://example.com/a.m3u8",
            ),
            SupplementChannel(
                id = "tmdb:1",
                name = "ESPN USA",
                groupTitle = "movies",
                streamUrl = "https://example.com/b.m3u8",
            ),
        )
        assertTrue(SmartDaddyFallbacksBuilder.fromSupplements(daddy, supplements).isEmpty())
    }
}

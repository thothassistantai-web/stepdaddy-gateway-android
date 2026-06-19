package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNumberResolverCountryTest {
    @Test
    fun `music supplements fill US before UK`() {
        val supplements = listOf(
            SupplementChannel(
                id = "iptv:uk1",
                name = "BBC Radio 1 UK",
                groupTitle = GroupTitleResolver.MUSIC,
                streamUrl = "https://example.com/uk.m3u8",
                tags = listOf("#music"),
            ),
            SupplementChannel(
                id = "iptv:us1",
                name = "Vevo Pop USA",
                groupTitle = GroupTitleResolver.MUSIC,
                streamUrl = "https://example.com/us.m3u8",
                tags = listOf("#music"),
            ),
        )

        val numbers = ChannelNumberResolver.assignSupplements(
            channels = emptyList(),
            supplements = supplements,
            groupFor = ChannelNumberResolver::supplementGroup,
        )

        assertEquals(mapOf("iptv:us1" to 210, "iptv:uk1" to 211), numbers)
    }

    @Test
    fun `resolver reads USA and UK suffixes`() {
        assertEquals(
            "US",
            GroupTitleResolver.resolve("Vevo Pop USA", listOf("#music")).countryCode,
        )
        assertEquals(
            "UK",
            GroupTitleResolver.resolve("BBC Radio 1 UK", listOf("#music")).countryCode,
        )
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCountrySortTest {
    @Test
    fun `priority order is US CA UK other English then rest`() {
        val ordered = listOf("FR", "UK", "AU", "US", "CA", "IE", "DE", "NZ")
            .sortedBy { ChannelCountrySort.prioritySortKey(it) }
        assertTrue(ordered.indexOf("US") < ordered.indexOf("CA"))
        assertTrue(ordered.indexOf("CA") < ordered.indexOf("UK"))
        assertTrue(ordered.indexOf("UK") < ordered.indexOf("AU"))
        assertTrue(ordered.indexOf("AU") < ordered.indexOf("IE"))
        assertTrue(ordered.indexOf("IE") < ordered.indexOf("DE"))
        assertTrue(ordered.indexOf("DE") < ordered.indexOf("FR"))
    }

    @Test
    fun `GB normalizes to UK tier`() {
        assertTrue(
            ChannelCountrySort.prioritySortKey("GB") ==
                ChannelCountrySort.prioritySortKey("UK"),
        )
    }
}

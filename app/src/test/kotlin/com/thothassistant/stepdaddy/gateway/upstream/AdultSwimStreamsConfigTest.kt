package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultSwimStreamsConfigTest {
    @Test
    fun `masterPlaylistUrl uses turner CDN pattern`() {
        val url = AdultSwimStreamsConfig.masterPlaylistUrl("rick-and-morty")
        assertTrue(url.contains("adultswim-vodlive.cdn.turner.com/live/rick-and-morty/stream_de.m3u8"))
        assertTrue(url.contains("playername="))
    }

    @Test
    fun `catalog has unique slugs`() {
        val slugs = AdultSwimStreamsConfig.CATALOG.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }
}

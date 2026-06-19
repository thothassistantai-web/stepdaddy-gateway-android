package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementProviderFilterTest {
    private fun entry(url: String, name: String = "Test") =
        M3uParser.Entry(name = name, streamUrl = url)

    @Test
    fun `allows direct moveonjoy m3u8`() {
        val url = "http://fl25.moveonjoy.com/CNN/index.m3u8"
        assertEquals(SupplementProviderFilter.Provider.MOVEONJOY, SupplementProviderFilter.classify(entry(url)))
        assertTrue(SupplementProviderFilter.isAllowed(entry(url)))
    }

    @Test
    fun `blocks thetvapp channel proxy`() {
        val url = "http://192.168.1.1:4124/channel?url=https%3A%2F%2Fthetvapp.link%2Ftv%2Fespn-live-stream%2F"
        assertEquals(SupplementProviderFilter.Provider.THETVAPP, SupplementProviderFilter.classify(entry(url)))
        assertFalse(SupplementProviderFilter.isAllowed(entry(url)))
    }

    @Test
    fun `blocks tvpass token proxy`() {
        val url = "http://127.0.0.1:4124/channel?url=https%3A%2F%2Ftvpass.org%2Fchannel%2Fespn%2F"
        assertEquals(SupplementProviderFilter.Provider.TVPASS, SupplementProviderFilter.classify(entry(url)))
    }

    @Test
    fun `filter counts blocked providers`() {
        val entries = listOf(
            entry("http://fl1.moveonjoy.com/ABC/index.m3u8", "ABC"),
            entry("http://x/channel?url=https%3A%2F%2Fthetvapp.link%2Ftv%2Fcnn-live-stream%2F", "CNN"),
            entry("http://x/channel?url=https%3A%2F%2Ftvpass.org%2Fchannel%2Ffox%2F", "Fox"),
        )
        val result = SupplementProviderFilter.filter(entries)
        assertEquals(1, result.allowed.size)
        assertEquals(1, result.blockedTheTvApp)
        assertEquals(1, result.blockedTvPass)
    }
}

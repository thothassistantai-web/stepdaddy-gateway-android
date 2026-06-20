package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtvCxCdnLiveSourceTest {
    private val catalog = listOf(
        NtvCxCdnLiveResolver.CatalogChannel("ESPN", "us", null),
        NtvCxCdnLiveResolver.CatalogChannel("AzamSports 1", "ae", null),
    )

    private val daddy = listOf(
        Channel(id = "70", name = "ESPN USA", tags = listOf("#sports")),
    )

    @Test
    fun `ALL mode keeps rows that match main list names`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            mergeMode = NtvCxMergeMode.ALL,
        )
        assertEquals(2, channels.size)
        assertTrue(channels.any { it.name == "ESPN" && it.providerTag == "CDN" })
    }

    @Test
    fun `SUPPLEMENT_ONLY mode skips normalized main-list names`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            mergeMode = NtvCxMergeMode.SUPPLEMENT_ONLY,
        )
        assertEquals(1, channels.size)
        assertEquals("AzamSports 1", channels.single().name)
    }

    @Test
    fun `buildChannels assigns stable ntv ids`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = emptyList(),
            mergeMode = NtvCxMergeMode.ALL,
        )
        assertTrue(channels.all { it.id.startsWith("ntv:") })
        assertTrue(channels.all { !it.ntvCdnLiveKey.isNullOrBlank() })
    }
}

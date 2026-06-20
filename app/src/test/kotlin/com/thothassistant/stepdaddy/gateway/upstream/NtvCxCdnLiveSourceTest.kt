package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtvCxCdnLiveSourceTest {
    private val catalog = listOf(
        NtvCxCdnLiveResolver.CatalogChannel("cdnlive", "ESPN", "us", null),
        NtvCxCdnLiveResolver.CatalogChannel(
            "hesgoales",
            "AzamSports 1",
            "",
            null,
            "https://hesgoaler.com/stream.php?ch=AzamSports1",
        ),
    )

    private val daddy = listOf(
        Channel(id = "70", name = "ESPN USA", tags = listOf("#sports")),
    )

    @Test
    fun `ALL mode keeps rows that match main list names`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            mergeMode = SupplementImportMode.FULL_CATALOG,
        )
        assertEquals(2, channels.size)
        assertTrue(channels.any { it.name == "ESPN" && it.providerTag == "CDN" })
        assertTrue(channels.any { it.name == "AzamSports 1" && it.providerTag == "Falcon" })
    }

    @Test
    fun `SUPPLEMENT_ONLY mode skips normalized main-list names`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            mergeMode = SupplementImportMode.SKIP_DUPLICATES,
        )
        assertEquals(1, channels.size)
        assertEquals("AzamSports 1", channels.single().name)
    }

    @Test
    fun `buildChannels assigns stable ntv ids`() {
        val channels = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = emptyList(),
            mergeMode = SupplementImportMode.FULL_CATALOG,
        )
        assertTrue(channels.all { it.id.startsWith("ntv:") })
        assertTrue(channels.all { !it.ntvCdnLiveKey.isNullOrBlank() })
    }
}

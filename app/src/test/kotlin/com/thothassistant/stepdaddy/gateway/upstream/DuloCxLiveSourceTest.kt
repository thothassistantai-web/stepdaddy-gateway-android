package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuloCxLiveSourceTest {
    private val catalog = listOf(
        DuloCxLiveResolver.CatalogChannel(
            id = "espn-id",
            name = "ESPN HD | USA",
            category = "sports",
            logoUrl = "https://cdn.example/espn.png",
            epgSourceUrl = null,
            supporterOnly = false,
            playable = true,
            sortOrder = 0,
        ),
        DuloCxLiveResolver.CatalogChannel(
            id = "local-id",
            name = "Obscure Local 99",
            category = "entertainment",
            logoUrl = null,
            epgSourceUrl = null,
            supporterOnly = false,
            playable = true,
            sortOrder = 1,
        ),
        DuloCxLiveResolver.CatalogChannel(
            id = "vip-id",
            name = "VIP Only",
            category = "sports",
            logoUrl = null,
            epgSourceUrl = null,
            supporterOnly = true,
            playable = true,
            sortOrder = 0,
        ),
    )

    private val daddy = listOf(
        Channel(id = "70", name = "ESPN USA", tags = listOf("#sports")),
    )

    @Test
    fun `FULL_CATALOG keeps non-supporter playable rows and attaches smart fallbacks`() {
        val result = DuloCxLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            importMode = SupplementImportMode.FULL_CATALOG,
        )
        assertEquals(2, result.channels.size)
        assertTrue(result.channels.all { it.id.startsWith("dulo:") })
        assertTrue(result.channels.any { it.duloChannelId == "espn-id" })
        assertTrue(result.channels.none { it.duloChannelId == "vip-id" })
        assertEquals(1, result.daddyFallbacks["70"]?.size)
        assertEquals("espn-id", result.daddyFallbacks["70"]?.first()?.duloChannelId)
    }

    @Test
    fun `SKIP_DUPLICATES drops daddy name overlaps but attaches smart fallbacks`() {
        val result = DuloCxLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            importMode = SupplementImportMode.SKIP_DUPLICATES,
        )
        assertEquals(1, result.channels.size)
        assertEquals("local-id", result.channels.single().duloChannelId)
        assertEquals(1, result.daddyFallbacks["70"]?.size)
    }

    @Test
    fun `CONSOLIDATE_FALLBACKS attaches dulo id to daddy row`() {
        val result = DuloCxLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddy,
            importMode = SupplementImportMode.CONSOLIDATE_FALLBACKS,
        )
        assertEquals(1, result.channels.size)
        assertEquals(1, result.daddyFallbacks["70"]?.size)
        assertEquals("espn-id", result.daddyFallbacks["70"]?.first()?.duloChannelId)
    }

    @Test
    fun `cleanDisplayName strips region and HD suffix`() {
        assertEquals("ESPN", DuloCxLiveSource.cleanDisplayName("ESPN HD | USA"))
        assertEquals("CNN", DuloCxLiveSource.cleanDisplayName("CNN HD | USA"))
    }

    @Test
    fun `parseCatalogJson reads public API shape`() {
        val resolver = DuloCxLiveResolver(DuloCxLiveResolver.defaultClient())
        val json = """
            {"channels":[
              {"id":"a","name":"CNN HD | USA","category":"news","logo_url":"https://x/y.png",
               "epg_source_url":null,"supporter_only":false,"sort_order":0,"playable":true}
            ]}
        """.trimIndent()
        val rows = resolver.parseCatalogJson(json)
        assertEquals(1, rows.size)
        assertEquals("a", rows.single().id)
        assertEquals("news", rows.single().category)
    }
}

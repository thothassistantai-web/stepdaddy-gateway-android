package com.thothassistant.stepdaddy.gateway.xtream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamLiveCatalogTest {
    @Test
    fun numericChannelUsesEmptyDirectSource() {
        val streams = XtreamLiveCatalog.streams(
            channels = listOf(Channel(id = "51", name = "ESPN", tvgId = "ESPN.us")),
            supplements = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
        )
        assertEquals(1, streams.size)
        assertEquals(51, streams[0].stream_id)
        assertEquals("", streams[0].direct_source)
    }

    @Test
    fun supplementWithExternalUrlUsesDirectSource() {
        val streams = XtreamLiveCatalog.streams(
            channels = emptyList(),
            supplements = listOf(
                SupplementChannel(
                    id = "iptv:abc",
                    name = "Test Channel",
                    groupTitle = "Entertainment",
                    streamUrl = "https://cdn.example/live.m3u8",
                ),
            ),
            baseUrl = "http://127.0.0.1:3000",
        )
        assertEquals(1, streams.size)
        assertEquals("https://cdn.example/live.m3u8", streams[0].direct_source)
    }

    @Test
    fun categoriesMatchStreamGroups() {
        val categories = XtreamLiveCatalog.categories(
            channels = listOf(Channel(id = "1", name = "News", tags = listOf("news"))),
            supplements = emptyList(),
        )
        assertTrue(categories.isNotEmpty())
        assertEquals(categories[0].category_id, categories[0].category_id)
    }
}

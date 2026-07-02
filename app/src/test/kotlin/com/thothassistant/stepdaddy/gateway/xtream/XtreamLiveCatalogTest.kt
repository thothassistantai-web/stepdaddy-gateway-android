package com.thothassistant.stepdaddy.gateway.xtream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun iptvSupplementCategoryMatchesBetweenCategoriesAndStreams() {
        val supplements = listOf(
            SupplementChannel(
                id = "iptv:abc",
                name = "BBC News",
                groupTitle = "Movies",
                tags = listOf("news"),
                streamUrl = "https://cdn.example/live.m3u8",
            ),
        )
        val categories = XtreamLiveCatalog.categories(channels = emptyList(), supplements = supplements)
        val streams = XtreamLiveCatalog.streams(
            channels = emptyList(),
            supplements = supplements,
            baseUrl = "http://127.0.0.1:3000",
        )
        assertEquals(1, streams.size)
        assertTrue(categories.any { it.category_id == streams[0].category_id })
        val groupName = categories.first { it.category_id == streams[0].category_id }.category_name
        assertTrue(groupName.isNotBlank())
        assertNotEquals("Movies", groupName)
    }
}

package com.nova.stepdaddylivehd.gateway.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListProviderTest {
    @Test
    fun parseM3u_extractsIdsNumbersAndHeadersLine() {
        val body = """
            #EXTM3U
            #EXTINF:-1 tvg-chno="51" group-title="USA" tvg-id="51",51 ABC
            http://127.0.0.1:3000/tivimate-stream/51.m3u8|User-Agent=test|Referer=https://daddylive.org/|Origin=https://daddylive.org
            #EXTINF:-1 tvg-chno="857" group-title="Italy",857 Italy
            http://127.0.0.1:3000/tivimate-stream/857.m3u8|User-Agent=test
        """.trimIndent()
        val channels = ChannelListProvider.parseM3u(body)
        assertEquals(2, channels.size)
        assertEquals("51", channels[0].id)
        assertEquals(51, channels[0].number)
        assertEquals("51 ABC", channels[0].name)
        assertEquals("USA", channels[0].groupTitle)
        assertEquals("857", channels[1].id)
        assertTrue(channels[1].name.contains("Italy"))
    }
}

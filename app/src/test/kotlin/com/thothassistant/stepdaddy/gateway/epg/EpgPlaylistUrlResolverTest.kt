package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgPlaylistUrlResolverTest {
    @Test
    fun `header omits tvg attributes when url list empty`() {
        assertEquals("#EXTM3U stepdaddy-rev=\"${PlaylistEpgHeader.PLAYLIST_REV}\"\n", PlaylistEpgHeader.line(emptyList()))
        assertEquals("#EXTM3U stepdaddy-rev=\"${PlaylistEpgHeader.PLAYLIST_REV}\"\n", PlaylistEpgHeader.line(null))
        assertEquals("#EXTM3U stepdaddy-rev=\"${PlaylistEpgHeader.PLAYLIST_REV}\"\n", PlaylistEpgHeader.line(""))
    }

    @Test
    fun `header joins multiple urls comma separated for tivimate`() {
        val urls = listOf(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
        )
        val line = PlaylistEpgHeader.line(urls)
        assertTrue(line.startsWith("#EXTM3U url-tvg=\""))
        assertTrue(line.contains("US2.xml.gz,https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz\""))
        assertTrue(line.contains("x-tvg-url=\""))
        assertTrue(line.contains("stepdaddy-rev=\"${PlaylistEpgHeader.PLAYLIST_REV}\""))
    }

    @Test
    fun `parse accepts comma newline and semicolon separators`() {
        val raw = """
            https://a.example/epg.xml.gz
            https://b.example/epg.xml.gz,https://c.example/epg.xml.gz;https://d.example/epg.xml.gz
        """.trimIndent()
        assertEquals(
            listOf(
                "https://a.example/epg.xml.gz",
                "https://b.example/epg.xml.gz",
                "https://c.example/epg.xml.gz",
                "https://d.example/epg.xml.gz",
            ),
            EpgConfig.parseExternalEpgUrls(raw),
        )
    }

    @Test
    fun `default external urls match primary epgshare feeds`() {
        assertEquals(EpgConfig.PRIMARY_FEED_URLS, EpgConfig.DEFAULT_EXTERNAL_EPG_URLS)
    }
}

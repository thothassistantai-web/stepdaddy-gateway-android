package com.nova.stepdaddylivehd.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ResportzHtmlParserTest {
    private val baseUrl = "https://resportz.cfd/live/stream-51.php"
    private val sampleM3u8 = "https://example.cdn/premium51/index.m3u8?token=abc"
    private val sampleB64 = Base64.getEncoder().encodeToString(sampleM3u8.toByteArray())

    @Test
    fun extractIframe_thatframeAbsoluteUrl() {
        val html = """<iframe id="thatframe" src="https://donis.example/resportz3.php?id=51" width="100%"></iframe>"""
        val matches = ResportzHtmlParser.extractIframeCandidates(html, baseUrl)
        assertEquals(1, matches.size)
        assertEquals("thatframe_id_src", matches[0].pattern)
        assertTrue(matches[0].value.contains("donis.example"))
    }

    @Test
    fun extractIframe_relativeResportzQuery() {
        val html = """<iframe src="/?a=2012" width="100%" id="thatframe"></iframe>"""
        val matches = ResportzHtmlParser.extractIframeCandidates(html, baseUrl)
        assertEquals(1, matches.size)
        assertEquals("src_thatframe_id", matches[0].pattern)
        assertEquals("https://resportz.cfd/?a=2012", matches[0].value)
    }

    @Test
    fun extractIframe_skipsVuenStub() {
        val html = """<iframe src="https://vuen.link/ch?id=10" id="thatframe"></iframe>"""
        assertTrue(ResportzHtmlParser.extractIframeCandidates(html, baseUrl).isEmpty())
        assertEquals("https://vuen.link/ch?id=10", ResportzHtmlParser.firstRawIframeSrc(html, baseUrl))
        assertTrue(ResportzHtmlParser.isEmbedStub("https://vuen.link/ch?id=10"))
    }

    @Test
    fun extractM3u8_sourceWindowAtobSingle() {
        val html = """<script>source: window.atob('$sampleB64')</script>"""
        val match = ResportzHtmlParser.extractM3u8Url(html)
        assertNotNull(match)
        assertEquals("source_window_atob_single", match?.pattern)
        assertEquals(sampleM3u8, match?.value)
    }

    @Test
    fun extractM3u8_atobDoubleQuotes() {
        val html = """var u = atob("$sampleB64");"""
        val match = ResportzHtmlParser.extractM3u8Url(html)
        assertNotNull(match)
        assertEquals("atob_double", match?.pattern)
        assertEquals(sampleM3u8, match?.value)
    }

    @Test
    fun extractM3u8_directUrl() {
        val html = """player.setup({ file: "$sampleM3u8" });"""
        val match = ResportzHtmlParser.extractM3u8Url(html)
        assertNotNull(match)
        assertEquals("direct_m3u8_url", match?.pattern)
        assertEquals(sampleM3u8, match?.value)
    }

    @Test
    fun extractIframe_dlhdPkWatchPage() {
        val watchBase = "https://dlhd.pk/watch/stream-51.php"
        val html = """<iframe src="https://donis.jimpenopisonline.online/premiumtv/daddy3.php?id=51" width="100%"></iframe>"""
        val matches = ResportzHtmlParser.extractIframeCandidates(html, watchBase)
        assertEquals(1, matches.size)
        assertTrue(matches[0].value.contains("donis.jimpenopisonline.online"))
    }

    @Test
    fun extractM3u8_missingReturnsNull() {
        assertNull(ResportzHtmlParser.extractM3u8Url("<html><body>agenda</body></html>"))
    }
}

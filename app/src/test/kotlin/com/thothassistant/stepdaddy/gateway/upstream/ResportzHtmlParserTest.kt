package com.thothassistant.stepdaddy.gateway.upstream

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

    @Test
    fun extractM3u8_econfigPreferred() {
        val blob =
            "WkVoWEtiRmxYTVdaa1dFcHpXREkxZG1ORVNuZEphbTlwWVVoU01HTklUVFpNZVRscVdrYz1aWGxYS2VtUk" +
                "lTbXhaVnpGbVpGaEtjMGxxYjJsaFNGSXdZMGhOTmt4NU9XcGFSelIxV2xnPU5IVlhhV0dob1lsaENjMXB" +
                "UT1hOaFdGcHNUSHBWZUV3eU1YWmliVGgxWWxST01VOURTams9YUdoWGlXRUp6V2xNNWMyRllXbXhNZWxW" +
                "NFRESnNkVnBIVmpSTWJUQjZaRlJuYVV4RFNubz0="
        val html = """<script>window._econfig="$blob";</script>"""
        val match = ResportzHtmlParser.extractM3u8Url(html)
        assertNotNull(match)
        assertEquals("econfig_stream_url", match?.pattern)
        assertEquals("https://cdn.example/live/51/mono.m3u8", match?.value)
    }

    @Test
    fun extractPlayerHubUrls_fromDaddyUrlsAndNontongo() {
        val html =
            """
            <div data-tv-daddy-urls="[&quot;https://www.nontongo.win/livetv/view/51&quot;]"></div>
            <iframe src="https://player.example/embed/51"></iframe>
            """.trimIndent()
        val hubs = ResportzHtmlParser.extractPlayerHubUrls(html, "51", "https://daddylive.li/live/stream=51")
        assertTrue(hubs.any { it.contains("nontongo.win") })
        assertTrue(hubs.any { it.contains("player.example") })
    }

    @Test
    fun prioritizeHubUrls_assetrageBeforeNontongoAndGeneric() {
        val ordered =
            ResportzHtmlParser.prioritizeHubUrls(
                listOf(
                    "https://www.nontongo.win/livetv/view/51",
                    "https://generic.example/player/51",
                    "https://assetrage.net/e/abc123",
                    "https://dlive.sx/embed/51",
                ),
            )
        assertEquals("https://assetrage.net/e/abc123", ordered[0])
        assertEquals("https://dlive.sx/embed/51", ordered[1])
        assertEquals("https://generic.example/player/51", ordered[2])
        assertEquals("https://www.nontongo.win/livetv/view/51", ordered[3])
    }

    @Test
    fun extractPlayerHubUrls_dliveAssetrageBeforeNontongo() {
        val html =
            """
            <div data-tv-daddy-urls="[&quot;https://www.nontongo.win/livetv/view/51&quot;]"></div>
            <iframe src="https://assetrage.net/e/xyz"></iframe>
            <iframe src="https://other.example/hub/51"></iframe>
            """.trimIndent()
        val hubs = ResportzHtmlParser.extractPlayerHubUrls(html, "51", "https://dlive.sx/live/51")
        assertEquals("https://assetrage.net/e/xyz", hubs.first())
        assertTrue(hubs.last().contains("nontongo"))
        assertTrue(ResportzHtmlParser.hubPriorityRank(hubs.first()) < ResportzHtmlParser.hubPriorityRank(hubs.last()))
    }

    @Test
    fun extractIframeCandidates_assetragePreferredOverGeneric() {
        val html =
            """
            <iframe src="https://generic.example/a"></iframe>
            <iframe src="https://assetrage.net/e/1"></iframe>
            """.trimIndent()
        val matches = ResportzHtmlParser.extractIframeCandidates(html, "https://dlive.sx/")
        assertEquals("https://assetrage.net/e/1", matches.first().value)
    }

    @Test
    fun extractIframeCandidates_skipsJsTemplateConcat() {
        val html =
            """
            <iframe src="'+domain+'/player/embed.php?id='+channelId+'"></iframe>
            <iframe src="https://assetrage.net/e/ok"></iframe>
            """.trimIndent()
        val matches = ResportzHtmlParser.extractIframeCandidates(html, "https://daddylive.li/live/stream=51")
        assertEquals(1, matches.size)
        assertEquals("https://assetrage.net/e/ok", matches[0].value)
    }
}

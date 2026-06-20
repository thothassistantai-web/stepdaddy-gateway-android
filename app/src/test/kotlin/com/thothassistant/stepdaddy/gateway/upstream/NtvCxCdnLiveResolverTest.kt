package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtvCxCdnLiveResolverTest {
    @Test
    fun `cdnLiveSlug strips unsafe characters and lowercases`() {
        assertEquals("espn", NtvCxCdnLiveResolver.channelSlug("ESPN"))
        assertEquals("hbo", NtvCxCdnLiveResolver.channelSlug("HBO"))
        assertEquals("arena-sport-1", NtvCxCdnLiveResolver.channelSlug("Arena Sport 1"))
    }

    @Test
    fun `watchPageUrl matches ntv cx lowercase slugs`() {
        assertEquals(
            "https://www.ntv.cx/channel-cdnlive/hbo?code=us",
            NtvCxCdnLiveResolver.watchPageUrl("HBO", "us"),
        )
    }

    @Test
    fun `parseCatalogJson keeps cdnlive and hesgoales rows`() {
        val json = """
            {
              "success": true,
              "channels": [
                {"server":"cdnlive","channel_name":"ESPN","channel_code":"us","channel_image":"/logo.png"},
                {"server":"hesgoales","channel_name":"beIN SPORTS USA","channel_url":"https://hesgoaler.com/stream.php?ch=beINSPORTSUS"},
                {"server":"dlhd","channel_name":"ABC USA","channel_id":"51"}
              ]
            }
        """.trimIndent()
        val rows = NtvCxCdnLiveResolver.parseCatalogJson(json)
        assertEquals(2, rows.size)
        assertEquals("cdnlive", rows[0].server)
        assertEquals("ESPN", rows[0].name)
        assertEquals("us", rows[0].regionCode)
        assertTrue(rows[0].logo!!.endsWith("/logo.png"))
        assertEquals("hesgoales", rows[1].server)
        assertEquals("beIN SPORTS USA", rows[1].name)
        assertEquals("https://hesgoaler.com/stream.php?ch=beINSPORTSUS", rows[1].streamPageUrl)
    }

    @Test
    fun `ntvKey encodes cdnlive and hesgoales rows`() {
        assertEquals(
            "cdnlive|HBO|us",
            NtvCxCdnLiveResolver.ntvKey("cdnlive", "HBO", "us"),
        )
        assertEquals(
            "hesgoales|beIN SPORTS USA|https://hesgoaler.com/stream.php?ch=beINSPORTSUS",
            NtvCxCdnLiveResolver.ntvKey(
                "hesgoales",
                "beIN SPORTS USA",
                "",
                "https://hesgoaler.com/stream.php?ch=beINSPORTSUS",
            ),
        )
    }

    @Test
    fun `parseNtvKey round trips keys`() {
        val cdn = NtvCxCdnLiveResolver.parseNtvKey("cdnlive|ESPN|us")
        assertNotNull(cdn)
        assertEquals("cdnlive", cdn!!.server)
        assertEquals("ESPN", cdn.name)
        assertEquals("us", cdn.extra)

        val hes = NtvCxCdnLiveResolver.parseNtvKey(
            "hesgoales|beIN SPORTS USA|https://hesgoaler.com/stream.php?ch=beINSPORTSUS",
        )
        assertNotNull(hes)
        assertEquals("hesgoales", hes!!.server)
        assertTrue(hes.extra.contains("beINSPORTSUS"))
        assertNull(NtvCxCdnLiveResolver.parseNtvKey("hesgoales|beIN SPORTS USA|"))
    }

    @Test
    fun `parsePlayerM3u8 assembles signed manifest url`() {
        val html = """
            <script>
            var bgecaZcv='aHR0cHM';
            var YKdhLeJz=':';
            var EJKRtDWt='Ly8';
            var UsaBGrcS='Y2RubGl2ZXR2';
            var divnERzk='LnR2';
            var UTusFiov='L3NlY3VyZS9hcGkvdjEv';
            var IIXakYIu='NmEyODhkMjY4MWQ4MTkyYmI3NmNhYjQw';
            var xXwOoagU='L3BsYXlsaXN0';
            var DPJvaoLl='Lm0zdTh=';
            var fhwRJKjX='P3Rva2VuPXNpZ25lZC10b2tlbg==';
            </script>
        """.trimIndent()
        val url = NtvCxCdnLiveResolver.parsePlayerM3u8(html)
        assertNotNull(url)
        assertEquals(
            "https://cdnlivetv.tv/secure/api/v1/6a288d2681d8192bb76cab40/playlist.m3u8?token=signed-token",
            url,
        )
    }

    @Test
    fun `extractHesgoalesSrc and channel id parse player html`() {
        val html = """
            const settings = {
                api: window.location.pathname + window.location.search,
                ch: "beINSPORTSUS",
                src: "https://lovely.lovetier.bz/beINSPORTSUS/index.m3u8",
                currentToken: ""
            };
        """.trimIndent()
        assertEquals(
            "https://lovely.lovetier.bz/beINSPORTSUS/index.m3u8",
            NtvCxCdnLiveResolver.extractHesgoalesSrc(html),
        )
        assertEquals(
            "beINSPORTSUS",
            NtvCxCdnLiveResolver.extractHesgoalesChannelId(
                html,
                "https://hesgoaler.com/stream.php?ch=beINSPORTSUS",
            ),
        )
    }

    @Test
    fun `refreshHesgoalesToken parses token json`() {
        val token = NtvCxCdnLiveResolver.refreshHesgoalesToken(
            "https://hesgoaler.com/stream.php?ch=beINSPORTSUS",
            "beINSPORTSUS",
        ) { _, _ ->
            """{"success":true,"token":"abc123"}"""
        }
        assertEquals("abc123", token)
    }

    @Test
    fun `extractEmbedToken finds token in watch html`() {
        val html = """<option value="/embed?t=abc123~">Stream 1</option>"""
        assertEquals("abc123~", NtvCxCdnLiveResolver.extractEmbedToken(html))
    }
}

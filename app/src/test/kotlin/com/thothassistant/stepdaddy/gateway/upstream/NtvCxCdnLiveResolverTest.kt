package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtvCxCdnLiveResolverTest {
    @Test
    fun `cdnLiveSlug strips unsafe characters`() {
        assertEquals("ESPN", NtvCxCdnLiveResolver.cdnLiveSlug("ESPN"))
        assertEquals("Arena-Sport-1", NtvCxCdnLiveResolver.cdnLiveSlug("Arena Sport 1"))
    }

    @Test
    fun `parseCatalogJson keeps cdnlive rows only`() {
        val json = """
            {
              "success": true,
              "channels": [
                {"server":"cdnlive","channel_name":"ESPN","channel_code":"us","channel_image":"/logo.png"},
                {"server":"dlhd","channel_name":"ABC USA","channel_id":"51"}
              ]
            }
        """.trimIndent()
        val rows = NtvCxCdnLiveResolver.parseCatalogJson(json)
        assertEquals(1, rows.size)
        assertEquals("ESPN", rows[0].name)
        assertEquals("us", rows[0].regionCode)
        assertTrue(rows[0].logo!!.endsWith("/logo.png"))
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
    fun `extractEmbedToken finds token in watch html`() {
        val html = """<option value="/embed?t=abc123~">Stream 1</option>"""
        assertEquals("abc123~", NtvCxCdnLiveResolver.extractEmbedToken(html))
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EconfigDecoderTest {
    // Synthetic blob from encode/decode roundtrip (stream_url + stream_url_nop2p).
    private val sampleBlob =
        "WkVoWEtiRmxYTVdaa1dFcHpXREkxZG1ORVNuZEphbTlwWVVoU01HTklUVFpNZVRscVdrYz1aWGxYS2VtUk" +
            "lTbXhaVnpGbVpGaEtjMGxxYjJsaFNGSXdZMGhOTmt4NU9XcGFSelIxV2xnPU5IVlhhV0dob1lsaENjMXB" +
            "UT1hOaFdGcHNUSHBWZUV3eU1YWmliVGgxWWxST01VOURTams9YUdoWGlXRUp6V2xNNWMyRllXbXhNZWxW" +
            "NFRESnNkVnBIVmpSTWJUQjZaRlJuYVV4RFNubz0="

    @Test
    fun decodeBlob_extractsStreamUrls() {
        val json = EconfigDecoder.decodeBlob(sampleBlob)
        assertNotNull(json)
        assertTrue(json!!.contains("cdn.example/live/51/mono.m3u8"))
        assertTrue(json.contains("cdn.example/live/51/index.m3u8"))
    }

    @Test
    fun extractStreamUrl_prefersNop2p() {
        val html = """<script>window._econfig='$sampleBlob';</script>"""
        assertEquals(
            "https://cdn.example/live/51/mono.m3u8",
            EconfigDecoder.extractStreamUrl(html),
        )
    }

    @Test
    fun extractStreamUrl_missingReturnsNull() {
        assertNull(EconfigDecoder.extractStreamUrl("<html></html>"))
    }

    @Test
    fun normalizeStreamUrl_unescapesJsonSlashes() {
        assertEquals(
            "https://cdn.example/hls/live.m3u8?s=1",
            EconfigDecoder.normalizeStreamUrl("""https:\/\/cdn.example\/hls\/live.m3u8?s=1"""),
        )
    }
}

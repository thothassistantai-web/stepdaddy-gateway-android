package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsManifestProbeTest {
    @Test
    fun `detects master playlist`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()
        assertTrue(HlsManifestProbe.isMasterPlaylist(master))
    }

    @Test
    fun `resolves first media url relative to manifest`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg001.ts
        """.trimIndent()
        val url = HlsManifestProbe.firstMediaUrl(media, "https://cdn.example/live/chunk.m3u8")
        assertEquals("https://cdn.example/live/seg001.ts", url)
    }

    @Test
    fun `prefers segment after extinf`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg001.ts
            #EXTINF:6.0,
            seg002.ts
        """.trimIndent()
        val url = HlsManifestProbe.firstSegmentUrl(media, "https://cdn.example/live/index.m3u8")
        assertEquals("https://cdn.example/live/seg001.ts", url)
    }

    @Test
    fun `master playlist is not media-only`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
        """.trimIndent()
        assertFalse(HlsManifestProbe.firstSegmentUrl(master, "https://cdn.example/master.m3u8") != null)
        assertEquals(
            "https://cdn.example/low/index.m3u8",
            HlsManifestProbe.firstMediaUrl(master, "https://cdn.example/master.m3u8"),
        )
    }
}

package com.thothassistant.stepdaddy.gateway.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementFallbackStreamRoutesTest {
    @Test
    fun isBareHttpUrl_detectsUrlBodiesThatBreakExoPlayer() {
        assertTrue(
            SupplementFallbackStreamRoutes.isBareHttpUrl(
                "https://cdnlivetv.tv/live/foo/index.m3u8",
            ),
        )
        assertTrue(
            SupplementFallbackStreamRoutes.isBareHttpUrl(
                "  http://example.com/stream.m3u8  ",
            ),
        )
    }

    @Test
    fun isBareHttpUrl_allowsRealHlsBodies() {
        assertFalse(
            SupplementFallbackStreamRoutes.isBareHttpUrl(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://cdn.example/a.m3u8\n",
            ),
        )
        assertFalse(SupplementFallbackStreamRoutes.isBareHttpUrl(""))
        assertFalse(SupplementFallbackStreamRoutes.isBareHttpUrl("<html>404</html>"))
    }
}

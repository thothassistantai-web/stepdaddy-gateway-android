package com.thothassistant.stepdaddy.gateway.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsErrorManifestParserTest {
    @Test
    fun extractMessage_parsesStepDaddyComment() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:3
            # StepDaddy: upstream timeout — retry shortly
            #EXTINF:1.0,unavailable
            unavailable.ts
        """.trimIndent()
        assertEquals("upstream timeout — retry shortly", HlsErrorManifestParser.extractMessage(body))
        assertTrue(HlsErrorManifestParser.isErrorManifest(body))
    }

    @Test
    fun extractMessage_returnsNullForValidManifest() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            http://127.0.0.1:3000/content/abc
        """.trimIndent()
        assertFalse(HlsErrorManifestParser.isErrorManifest(body))
    }
}

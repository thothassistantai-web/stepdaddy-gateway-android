package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsErrorManifestTest {
    @Test
    fun build_emitsMasterOnlyWithoutFakeSegments() {
        val manifest = HlsErrorManifest.build("upstream timeout — retry shortly")
        assertTrue(manifest.startsWith("#EXTM3U"))
        assertTrue(manifest.contains("# StepDaddy: upstream timeout"))
        assertFalse(manifest.contains("#EXTINF:"))
        assertFalse(manifest.contains("unavailable.ts"))
    }
}

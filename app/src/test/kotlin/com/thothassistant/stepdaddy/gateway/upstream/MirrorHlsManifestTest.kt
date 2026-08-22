package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorHlsManifestTest {
    @Test
    fun build_emitsVariantPerMirror() {
        val manifest = MirrorHlsManifest.build(
            baseUrl = "http://127.0.0.1:3000",
            eventToken = "abc123",
            mirrorCount = 3,
            labels = listOf("Germany", "Paraguay", "Backup"),
        )
        assertTrue(manifest.startsWith("#EXTM3U"))
        assertEquals(3, manifest.lines().count { it.contains("/dlhd-event-mirror/abc123/") })
        assertTrue(manifest.contains("NAME=\"Germany\""))
    }

    @Test
    fun build_singleMirrorStillValid() {
        val manifest = MirrorHlsManifest.build(
            baseUrl = "http://127.0.0.1:3000",
            eventToken = "solo",
            mirrorCount = 1,
        )
        assertTrue(manifest.contains("/dlhd-event-mirror/solo/0.m3u8"))
    }

    @Test
    fun build_doesNotEmitIndependentSegments() {
        val manifest = MirrorHlsManifest.build(
            baseUrl = "http://127.0.0.1:3000",
            eventToken = "test",
            mirrorCount = 2,
        )
        assertTrue(manifest.contains("#EXTM3U"))
        assertTrue(manifest.contains("#EXT-X-STREAM-INF"))
        assertTrue(!manifest.contains("INDEPENDENT-SEGMENTS"))
    }
}

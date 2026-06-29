package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorLatencyTrackerTest {
    private val tracker = MirrorLatencyTracker(alpha = 0.5)

    @Test
    fun `orderedMirrorUrls puts activeBaseUrl first`() {
        val ordered = MirrorLatencyTracker.orderedMirrorUrls(
            activeBaseUrl = "https://daddylive.eu",
            dlhdBaseUrl = "https://daddylive.li",
            configuredMirrors = listOf("https://daddylive.org"),
            mirrorLatencyMs = { null },
        )
        assertEquals("https://daddylive.eu", ordered.first())
        assertEquals(3, ordered.size)
    }

    @Test
    fun `orderedMirrorUrls sorts remaining mirrors by latency EMA`() {
        val ordered = MirrorLatencyTracker.orderedMirrorUrls(
            activeBaseUrl = "https://daddylive.li",
            dlhdBaseUrl = "https://daddylive.li",
            configuredMirrors = listOf("https://daddylive.org", "https://daddylive.eu"),
            mirrorLatencyMs = { url ->
                when (url) {
                    "https://daddylive.org" -> 900.0
                    "https://daddylive.eu" -> 200.0
                    else -> null
                }
            },
        )
        assertEquals("https://daddylive.li", ordered[0])
        assertEquals("https://daddylive.eu", ordered[1])
        assertEquals("https://daddylive.org", ordered[2])
    }

    @Test
    fun `orderedMirrorUrls skips excluded mirrors`() {
        val ordered = MirrorLatencyTracker.orderedMirrorUrls(
            activeBaseUrl = "https://daddylive.org",
            dlhdBaseUrl = "https://daddylive.li",
            configuredMirrors = listOf("https://daddylive.org"),
            mirrorLatencyMs = { null },
            isExcluded = { it == "https://daddylive.org" },
        )
        assertEquals(listOf("https://daddylive.li"), ordered)
    }

    @Test
    fun `mirror EMA prefers faster samples on subsequent ordering`() {
        tracker.recordMirrorSuccess("https://daddylive.org", 2_000L)
        tracker.recordMirrorSuccess("https://daddylive.eu", 400L)
        tracker.recordMirrorSuccess("https://daddylive.org", 500L)

        val orgLatency = tracker.mirrorLatencyMs("https://daddylive.org")!!
        val euLatency = tracker.mirrorLatencyMs("https://daddylive.eu")!!
        assertTrue(orgLatency > euLatency)

        val ordered = MirrorLatencyTracker.orderedMirrorUrls(
            activeBaseUrl = "https://daddylive.li",
            dlhdBaseUrl = "https://daddylive.li",
            configuredMirrors = listOf("https://daddylive.org", "https://daddylive.eu"),
            mirrorLatencyMs = tracker::mirrorLatencyMs,
        )
        assertEquals("https://daddylive.eu", ordered[1])
        assertEquals("https://daddylive.org", ordered[2])
    }

    @Test
    fun `orderedDlhdPaths sorts by path latency`() {
        tracker.recordDlhdPathSuccess("watch", 300L)
        tracker.recordDlhdPathSuccess("cast", 1_200L)
        val paths = listOf("cast", "watch", "plus")
        val ordered = tracker.orderedDlhdPaths(paths)
        assertEquals("watch", ordered.first())
        assertEquals("plus", ordered.last())
    }
}

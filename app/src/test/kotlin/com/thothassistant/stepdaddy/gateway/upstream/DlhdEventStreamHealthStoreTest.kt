package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlhdEventStreamHealthStoreTest {
    private val store = DlhdEventStreamHealthStore()

    @Test
    fun `unknown until probed`() {
        assertEquals(DlhdEventStreamHealth.Status.UNKNOWN, store.status("abc"))
        assertFalse(store.isHealthy("abc"))
    }

    @Test
    fun `records healthy and unhealthy tokens`() {
        store.record("ok", DlhdEventStreamHealth.ProbeResult.healthy())
        store.record("bad", DlhdEventStreamHealth.ProbeResult.unhealthy("segment_unreachable"))
        assertTrue(store.isHealthy("ok"))
        assertEquals(DlhdEventStreamHealth.Status.UNHEALTHY, store.status("bad"))
        assertEquals("segment_unreachable", store.entry("bad")?.lastError)
    }

    @Test
    fun `summary counts active unknown tokens`() {
        store.record("a", DlhdEventStreamHealth.ProbeResult.healthy())
        store.record("b", DlhdEventStreamHealth.ProbeResult.unhealthy("fail"))
        val summary = store.summary(activeStreams = 3)
        assertEquals(3, summary.activeStreams)
        assertEquals(2, summary.probed)
        assertEquals(1, summary.healthy)
        assertEquals(1, summary.unhealthy)
        assertEquals(1, summary.unknown)
    }

    @Test
    fun `records ended status`() {
        store.record("done", DlhdEventStreamHealth.ProbeResult.ended())
        assertEquals(DlhdEventStreamHealth.Status.ENDED, store.status("done"))
        assertFalse(store.isHealthy("done"))
    }

    @Test
    fun `summary counts ended tokens`() {
        store.record("live", DlhdEventStreamHealth.ProbeResult.healthy())
        store.record("done", DlhdEventStreamHealth.ProbeResult.ended())
        val summary = store.summary(activeStreams = 2)
        assertEquals(1, summary.healthy)
        assertEquals(1, summary.ended)
    }

    @Test
    fun `prune drops stale tokens`() {
        store.record("keep", DlhdEventStreamHealth.ProbeResult.healthy())
        store.record("drop", DlhdEventStreamHealth.ProbeResult.unhealthy("gone"))
        store.pruneTokens(setOf("keep"))
        assertTrue(store.isHealthy("keep"))
        assertEquals(DlhdEventStreamHealth.Status.UNKNOWN, store.status("drop"))
    }
}

class DlhdEventStreamProberTest {
    @Test
    fun `tv probe delegates to injected resolver`() = runBlocking {
        val prober = DlhdEventStreamProber()
        val channel = SupplementChannel(
            id = "dlhd-event:tv1",
            name = "Event",
            groupTitle = "Special",
            streamUrl = "",
            dlhdEventStreamKey = "tv|726",
        )
        val ok = prober.probe(channel) { channelId ->
            assertEquals("726", channelId)
            true
        }
        assertEquals(DlhdEventStreamHealth.Status.HEALTHY, ok.status)

        val bad = prober.probe(channel) { false }
        assertEquals(DlhdEventStreamHealth.Status.UNHEALTHY, bad.status)
    }

    @Test
    fun `missing key is unhealthy`() = runBlocking {
        val prober = DlhdEventStreamProber()
        val channel = SupplementChannel(
            id = "dlhd-event:x",
            name = "Event",
            groupTitle = "Special",
            streamUrl = "",
        )
        val result = prober.probe(channel) { true }
        assertEquals(DlhdEventStreamHealth.Status.UNHEALTHY, result.status)
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlhdEventMirrorProbeStoreTest {
    @Test
    fun record_and_lookup_mirror_health() {
        val store = DlhdEventMirrorProbeStore()
        store.record("event-a", "tv|1", healthy = true)
        store.record("event-a", "TV|2", healthy = false, error = "failed")

        assertTrue(store.isHealthy("event-a", "tv|1")!!)
        assertFalse(store.isHealthy("event-a", "tv|2")!!)
        assertNull(store.isHealthy("event-a", "tv|3"))
    }

    @Test
    fun prune_drops_stale_events() {
        val store = DlhdEventMirrorProbeStore()
        store.record("keep", "tv|1", healthy = true)
        store.record("drop", "tv|9", healthy = true)

        store.pruneEvents(setOf("keep"))

        assertTrue(store.isHealthy("keep", "tv|1")!!)
        assertNull(store.isHealthy("drop", "tv|9"))
    }
}

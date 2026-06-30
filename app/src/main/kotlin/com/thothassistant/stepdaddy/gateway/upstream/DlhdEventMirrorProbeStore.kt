package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.ConcurrentHashMap

/** Per-mirror playability cache for consolidated dlhd-event rows (`eventKey|streamKey`). */
class DlhdEventMirrorProbeStore {
    data class Entry(
        val healthy: Boolean,
        val lastProbeMs: Long = System.currentTimeMillis(),
        val error: String? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var revision = 0L

    fun revision(): Long = revision

    fun record(eventKey: String, streamKey: String, healthy: Boolean, error: String? = null) {
        val key = compositeKey(eventKey, streamKey)
        if (key.isEmpty()) return
        val previous = entries[key]?.healthy
        entries[key] = Entry(healthy = healthy, error = error)
        if (previous != healthy) {
            revision++
        }
    }

    fun isHealthy(eventKey: String, streamKey: String): Boolean? =
        entries[compositeKey(eventKey, streamKey)]?.healthy

    fun entry(eventKey: String, streamKey: String): Entry? =
        entries[compositeKey(eventKey, streamKey)]

    fun snapshot(): Map<String, Entry> = entries.toMap()

    fun pruneEvents(activeEventKeys: Collection<String>) {
        val active = activeEventKeys.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val removed = entries.keys.removeIf { key ->
            val eventKey = key.substringBefore('|')
            eventKey !in active
        }
        if (removed) {
            revision++
        }
    }

    private fun compositeKey(eventKey: String, streamKey: String): String {
        val event = eventKey.trim()
        val stream = streamKey.trim().lowercase()
        if (event.isEmpty() || stream.isEmpty()) return ""
        return "$event|$stream"
    }
}

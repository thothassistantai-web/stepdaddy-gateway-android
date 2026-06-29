package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import com.thothassistant.stepdaddy.gateway.model.EventMetadata
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class EventMetadataCache(
    val entries: Map<String, EventMetadata> = emptyMap(),
    val syncedAtMs: Long = 0L,
)

/** Disk-backed index of scraped Special Events metadata keyed by supplement channel id. */
class EventMetadataStore(context: Context) {
    private val file = File(File(context.filesDir, "supplement"), "event_metadata.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun readAll(): Map<String, EventMetadata> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<EventMetadataCache>(file.readText()).entries
        }.getOrDefault(emptyMap())
    }

    fun writeAll(entries: Map<String, EventMetadata>) {
        file.parentFile?.mkdirs()
        val payload = EventMetadataCache(entries = entries, syncedAtMs = System.currentTimeMillis())
        file.writeText(json.encodeToString(payload))
    }

    fun retainOnly(channelIds: Set<String>) {
        val current = readAll()
        if (current.isEmpty()) return
        val next = current.filterKeys { it in channelIds }
        if (next.size == current.size) return
        if (next.isEmpty()) {
            clear()
        } else {
            writeAll(next)
        }
    }

    fun clear() {
        file.delete()
    }

    fun lastSyncedAtMs(): Long =
        runCatching {
            json.decodeFromString<EventMetadataCache>(file.readText()).syncedAtMs
        }.getOrDefault(0L)
}

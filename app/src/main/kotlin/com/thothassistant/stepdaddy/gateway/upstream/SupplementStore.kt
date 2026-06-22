package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SupplementCache(
    val channels: List<SupplementChannel> = emptyList(),
    val syncedAtMs: Long = 0L,
)

@Serializable
private data class GuideScheduleCache(
    val guides: Map<String, List<SpecialEventsMerger.GuideEventRow>> = emptyMap(),
    val syncedAtMs: Long = 0L,
)

class SupplementStore(context: Context) {
    private val dir = File(context.filesDir, "supplement").also { it.mkdirs() }
    private val channelsFile = File(dir, "channels.json")
    val epgGzipFile = File(dir, "epg.xml.gz")
    val epgPlainFile = File(dir, "epg.xml")
    val sportsEpgFile = File(dir, "sports_epg.xml")
    private val guideSchedulesFile = File(dir, "guide_schedules.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun epgFile(): File? {
        if (epgGzipFile.exists() && epgGzipFile.length() > 0L) return epgGzipFile
        if (epgPlainFile.exists() && epgPlainFile.length() > 0L) return epgPlainFile
        return null
    }

    fun readChannels(): List<SupplementChannel> {
        if (!channelsFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<SupplementCache>(channelsFile.readText()).channels
        }.getOrDefault(emptyList())
    }

    fun writeChannels(channels: List<SupplementChannel>) {
        val payload = SupplementCache(channels = channels, syncedAtMs = System.currentTimeMillis())
        channelsFile.writeText(json.encodeToString(payload))
    }

    fun isStale(): Boolean {
        if (!channelsFile.exists()) return true
        val syncedAt = runCatching {
            json.decodeFromString<SupplementCache>(channelsFile.readText()).syncedAtMs
        }.getOrDefault(0L)
        return System.currentTimeMillis() - syncedAt > SupplementConfig.SYNC_INTERVAL_MS
    }

    fun lastSyncedAtMs(): Long =
        runCatching {
            json.decodeFromString<SupplementCache>(channelsFile.readText()).syncedAtMs
        }.getOrDefault(0L)

    fun readGuideSchedules(): Map<String, List<SpecialEventsMerger.GuideEventRow>> {
        if (!guideSchedulesFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<GuideScheduleCache>(guideSchedulesFile.readText()).guides
        }.getOrDefault(emptyMap())
    }

    fun writeGuideSchedules(guides: Map<String, List<SpecialEventsMerger.GuideEventRow>>) {
        val payload = GuideScheduleCache(guides = guides, syncedAtMs = System.currentTimeMillis())
        guideSchedulesFile.writeText(json.encodeToString(payload))
    }

    fun clearGuideSchedules() {
        guideSchedulesFile.delete()
    }
}

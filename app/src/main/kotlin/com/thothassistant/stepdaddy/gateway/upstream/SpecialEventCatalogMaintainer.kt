package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.time.Instant

/** Prunes expired Special Events rows between upstream syncs. */
object SpecialEventCatalogMaintainer {
    data class PruneResult(
        val channels: List<SupplementChannel>,
        val guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        val removedStreams: Int,
        val removedGuides: Int,
        val removedScheduleRows: Int,
    ) {
        val changed: Boolean
            get() = removedStreams > 0 || removedGuides > 0 || removedScheduleRows > 0
    }

    fun isSpecialEventChannel(id: String): Boolean =
        id.startsWith("sport:") ||
            id.startsWith("dlhd-guide:") ||
            id.startsWith("dlhd-event:")

    fun prune(
        channels: List<SupplementChannel>,
        guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long = System.currentTimeMillis(),
    ): PruneResult {
        val now = Instant.ofEpochMilli(nowMs)
        var removedScheduleRows = 0
        val prunedGuides = guideSchedules.mapValues { (_, rows) ->
            val active = rows.filter { row ->
                SpecialEventLifecycle.isActive(row.startMs, row.stopMs, nowMs)
            }
            removedScheduleRows += rows.size - active.size
            active
        }.filterValues { it.isNotEmpty() }

        val activeStreams = channels.filter { channel ->
            when {
                channel.id.startsWith("dlhd-event:") -> isDlhdEventActive(channel, now)
                channel.id.startsWith("sport:") -> true
                else -> false
            }
        }
        val slugsWithStreams = activeStreams.mapNotNull { categorySlug(it) }.toSet()

        val keptGuides = channels.filter { channel ->
            if (!channel.id.startsWith("dlhd-guide:")) return@filter false
            val guideId = channel.id
            val slug = guideId.removePrefix("dlhd-guide:")
            prunedGuides[guideId].orEmpty().isNotEmpty() || slug in slugsWithStreams
        }

        val removedStreams = channels.count { channel ->
            channel.id.startsWith("dlhd-event:") && channel !in activeStreams
        }
        val removedGuides = channels.count { channel ->
            channel.id.startsWith("dlhd-guide:") && channel !in keptGuides
        }

        val nonSpecial = channels.filter { !isSpecialEventChannel(it.id) }
        val keptIds = (keptGuides + activeStreams).map { it.id }.toSet()
        val nextChannels = buildList {
            channels.forEach { channel ->
                when {
                    !isSpecialEventChannel(channel.id) -> add(channel)
                    channel.id in keptIds -> add(channel)
                }
            }
        }

        return PruneResult(
            channels = nextChannels,
            guideSchedules = prunedGuides,
            removedStreams = removedStreams,
            removedGuides = removedGuides,
            removedScheduleRows = removedScheduleRows,
        )
    }

    fun isDlhdEventActive(channel: SupplementChannel, now: Instant = Instant.now()): Boolean {
        if (!channel.id.startsWith("dlhd-event:")) return true
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl) ?: return true
        val (start, stop) = DlhdScheduleTime.parseWindow(meta.dateKey, meta.timeLabel)
        return SpecialEventLifecycle.isActive(start, stop, now)
    }

    fun categorySlug(channel: SupplementChannel): String? {
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl) ?: return null
        if (meta.category.isBlank()) return null
        return SpecialEventsMerger.slugify(meta.category)
    }
}

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

    data class VerifyResult(
        val needsRefresh: Boolean,
        val missingLiveStreams: Int,
        val upcomingWithoutStreams: Int,
    )

    /** Schedule rows that are live or starting soon but lack a matching `dlhd-event:` stream. */
    fun verifyStartedEvents(
        channels: List<SupplementChannel>,
        guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long = System.currentTimeMillis(),
        preStartWindowMs: Long = SupplementConfig.SPECIAL_EVENTS_PRE_START_WINDOW_MS,
    ): VerifyResult {
        val cachedTitleKeys = channels.asSequence()
            .filter { it.id.startsWith("dlhd-event:") }
            .mapNotNull { DlhdEventSourceMeta.parse(it.eventSourceUrl)?.title }
            .map { SpecialEventsMerger.normalizeTitleKey(it) }
            .toSet()

        var missingLive = 0
        var upcomingSoon = 0
        guideSchedules.values.forEach { rows ->
            rows.forEach { row ->
                if (!SpecialEventLifecycle.isActive(row.startMs, row.stopMs, nowMs)) return@forEach
                val titleKey = SpecialEventsMerger.normalizeTitleKey(row.title)
                if (titleKey in cachedTitleKeys) return@forEach
                when {
                    row.startMs <= nowMs -> missingLive++
                    row.startMs - nowMs <= preStartWindowMs -> upcomingSoon++
                }
            }
        }
        return VerifyResult(
            needsRefresh = missingLive > 0 || upcomingSoon > 0,
            missingLiveStreams = missingLive,
            upcomingWithoutStreams = upcomingSoon,
        )
    }

    /** Merge freshly fetched special-event rows into the existing catalog without dropping other sources. */
    fun mergeFetchedSpecialEvents(
        existing: List<SupplementChannel>,
        fetched: List<SupplementChannel>,
        fetchedGuideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        existingGuideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<SupplementChannel>, Map<String, List<SpecialEventsMerger.GuideEventRow>>> {
        val pruned = prune(
            channels = existing,
            guideSchedules = existingGuideSchedules,
            nowMs = nowMs,
        )
        val nonSpecial = pruned.channels.filter { !isSpecialEventChannel(it.id) }
        val existingSpecial = pruned.channels.filter { isSpecialEventChannel(it.id) }
        val fetchedSpecial = fetched.filter { isSpecialEventChannel(it.id) }

        val mergedSpecialById = linkedMapOf<String, SupplementChannel>()
        existingSpecial.forEach { mergedSpecialById[it.id] = it }
        fetchedSpecial.forEach { mergedSpecialById[it.id] = it }

        val mergedGuides = linkedMapOf<String, List<SpecialEventsMerger.GuideEventRow>>()
        pruned.guideSchedules.forEach { (guideId, rows) -> mergedGuides[guideId] = rows.toList() }
        fetchedGuideSchedules.forEach { (guideId, rows) ->
            if (rows.isEmpty()) return@forEach
            val prior = mergedGuides[guideId].orEmpty()
            val combined = (prior + rows).distinctBy { "${it.title}|${it.startMs}|${it.stopMs}" }
                .sortedBy { it.startMs }
            mergedGuides[guideId] = combined
        }

        val orderedSpecial = buildOrderedSpecialChannels(
            mergedSpecialById.values.toList(),
            mergedGuides,
            nowMs = nowMs,
        )
        return nonSpecial + orderedSpecial to mergedGuides
    }

    private fun buildOrderedSpecialChannels(
        channels: List<SupplementChannel>,
        guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SupplementChannel> {
        val guides = channels.filter { it.id.startsWith("dlhd-guide:") }
        val streams = channels.filter { channel ->
            channel.id.startsWith("dlhd-event:") &&
                isDlhdEventActive(channel, Instant.ofEpochMilli(nowMs))
        }
        val sports = channels.filter { it.id.startsWith("sport:") }
        if (guides.isEmpty() && streams.isEmpty()) return sports

        val streamsByCategory = streams.groupBy { channel ->
            categorySlug(channel).orEmpty()
        }
        val orderedGuides = guides.sortedWith(
            compareBy(
                { SpecialEventSort.guideBlockEventSortKey(it.id, guideSchedules, nowMs) },
                { SpecialEventSort.guideDisplayName(it).lowercase() },
            ),
        )
        val result = mutableListOf<SupplementChannel>()
        var streamCount = 0
        val maxStreams = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS
        for (guide in orderedGuides) {
            val slug = guide.id.removePrefix("dlhd-guide:")
            val categoryStreams = streamsByCategory[slug].orEmpty().sortedWith(
                compareBy(
                    { SpecialEventSort.streamWindowSortKey(it, nowMs) },
                    { it.name.lowercase() },
                ),
            )
            if (categoryStreams.isEmpty() &&
                guideSchedules[guide.id].orEmpty().isEmpty()
            ) {
                continue
            }
            result += guide
            if (streamCount >= maxStreams) continue
            for (stream in categoryStreams) {
                if (streamCount >= maxStreams) break
                result += stream
                streamCount++
            }
        }
        if (streamCount < maxStreams) {
            val orphanSports = sports.sortedWith(
                compareBy(
                    { SpecialEventSort.streamWindowSortKey(it, nowMs) },
                    { SpecialEventSort.sortKey(it.providerTag, it.name, it.eventSourceUrl) },
                    { it.name.lowercase() },
                ),
            )
            for (stream in orphanSports) {
                if (streamCount >= maxStreams) break
                result += stream
                streamCount++
            }
        }
        return result
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
        return SpecialEventLifecycle.isPlaylistVisible(start, stop, now)
    }

    fun categorySlug(channel: SupplementChannel): String? {
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl) ?: return null
        if (meta.category.isBlank()) return null
        return SpecialEventsMerger.slugify(meta.category)
    }
}

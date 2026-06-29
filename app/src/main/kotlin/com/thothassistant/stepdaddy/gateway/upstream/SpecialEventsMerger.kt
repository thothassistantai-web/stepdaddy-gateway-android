package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import java.time.Instant

import kotlinx.serialization.Serializable

/**
 * Merges DaddyLive schedule feeds and TheTvApp live events into one Special Events supplement set.
 */
object SpecialEventsMerger {
    data class MergeResult(
        val channels: List<SupplementChannel>,
        val dlhdStats: DaddyLiveEventResolver.ResolveStats = DaddyLiveEventResolver.ResolveStats(),
        val theTvAppCount: Int = 0,
    )

    @Serializable
    data class GuideEventRow(
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val category: String,
        val league: String,
        val regionCode: String? = null,
    )

    /** Per-guide-channel EPG rows keyed by supplement id. */
    data class EpgBundle(
        val channels: List<SupplementChannel>,
        val guideProgrammes: Map<String, List<GuideEventRow>> = emptyMap(),
    )

    fun merge(
        dlhdBaseUrl: String,
        dlhdResolver: DaddyLiveEventResolver,
        theTvAppChannels: List<SupplementChannel>,
        maxStreams: Int = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
    ): EpgBundle {
        val (dlhdEvents, dlhdStats) = dlhdResolver.resolveFromNetwork(dlhdBaseUrl)
        return buildFromParsed(dlhdEvents, dlhdStats, theTvAppChannels, maxStreams)
    }

    fun buildFromParsed(
        dlhdEvents: List<DaddyLiveEventResolver.ParsedEvent>,
        dlhdStats: DaddyLiveEventResolver.ResolveStats,
        theTvAppChannels: List<SupplementChannel>,
        maxStreams: Int = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
    ): EpgBundle {
        val group = GroupTitleResolver.SPECIAL_EVENTS
        val guideProgrammes = linkedMapOf<String, MutableList<GuideEventRow>>()
        val guides = linkedMapOf<String, SupplementChannel>()
        val streamsByCategory = linkedMapOf<String, MutableList<SupplementChannel>>()
        val theTvAppStreams = mutableListOf<SupplementChannel>()
        val occupiedStreamKeys = linkedSetOf<String>()
        val occupiedTitleKeys = mutableSetOf<String>()
        val nowMs = System.currentTimeMillis()

        dlhdEvents.forEach { event ->
            val schedule = EventTimeExtractor.fromDlhdSchedule(
                dateKey = event.dateKey,
                timeLabel = event.timeLabel,
                eventToken = event.title,
                source = when (event.streams.firstOrNull()?.source) {
                    DaddyLiveEventResolver.StreamSource.TV2 -> EventScheduleSource.DLHD_TV2
                    DaddyLiveEventResolver.StreamSource.TV,
                    null,
                    -> EventScheduleSource.DLHD_TV
                },
                now = Instant.ofEpochMilli(nowMs),
            )
            val (start, stop) = schedule.window()
            if (!SpecialEventLifecycle.isActive(start, stop, Instant.ofEpochMilli(nowMs))) return@forEach
            val guideSlug = slugify(event.category)
            val guideId = "dlhd-guide:$guideSlug"
            if (guideId !in guides) {
                val emoji = SpecialEventCategoryEmoji.forCategory(event.category, event.league)
                guides[guideId] = SupplementChannel(
                    id = guideId,
                    name = "$emoji ${event.category} Schedule",
                    tvgId = "DLHD.Guide.$guideSlug",
                    groupTitle = group,
                    streamUrl = "",
                    providerTag = event.league,
                    tags = listOf("#events", "#guide"),
                    languageCode = SpecialEventLanguageIdentifier.identify(
                        SpecialEventLanguageIdentifier.Context(
                            eventTitle = event.title,
                            category = event.category,
                            league = event.league,
                        ),
                    ),
                    regionCode = SpecialEventRegionIdentifier.identify(
                        SpecialEventRegionIdentifier.Context(
                            eventTitle = event.title,
                            category = event.category,
                            league = event.league,
                        ),
                    ),
                )
                guideProgrammes[guideId] = mutableListOf()
            }
            guideProgrammes.getValue(guideId) += GuideEventRow(
                title = event.title,
                startMs = start.toEpochMilli(),
                stopMs = stop.toEpochMilli(),
                category = event.category,
                league = event.league,
                regionCode = SpecialEventRegionIdentifier.identify(
                    SpecialEventRegionIdentifier.Context(
                        eventTitle = event.title,
                        category = event.category,
                        league = event.league,
                    ),
                ),
            )

            val cappedStreams = limitStreamLinks(event.streams)
            cappedStreams.forEachIndexed { linkIndex, stream ->
                val streamKey = "${stream.source.name}|${stream.channelId}"
                if (!occupiedStreamKeys.add(streamKey)) return@forEachIndexed
                val titleKey = normalizeTitleKey(event.title)
                occupiedTitleKeys += titleKey

                val streamSchedule = EventTimeExtractor.fromDlhdParsedEvent(
                    event = event,
                    stream = stream,
                    now = Instant.ofEpochMilli(nowMs),
                )
                val (streamStart, streamStop) = streamSchedule.window()

                val id = "dlhd-event:${shortHash(streamKey)}"
                val displayName = buildStreamName(event.title, stream.label, linkIndex)
                streamsByCategory.getOrPut(guideSlug) { mutableListOf() } += SupplementChannel(
                    id = id,
                    name = displayName,
                    tvgId = "DLHD.Event.${shortHash("$titleKey|$streamKey")}",
                    groupTitle = group,
                    streamUrl = "",
                    providerTag = event.league,
                    tags = listOf("#events"),
                    dlhdEventStreamKey = dlhdStreamKey(stream),
                    eventSourceUrl = "${event.category}|${event.dateKey}|${event.timeLabel}|${event.title}",
                    eventStartMs = streamStart.toEpochMilli(),
                    eventStopMs = streamStop.toEpochMilli(),
                    languageCode = SpecialEventLanguageIdentifier.identify(
                        SpecialEventLanguageIdentifier.Context(
                            eventTitle = event.title,
                            streamLabel = stream.label,
                            category = event.category,
                            league = event.league,
                        ),
                    ),
                    regionCode = SpecialEventRegionIdentifier.identify(
                        SpecialEventRegionIdentifier.Context(
                            eventTitle = event.title,
                            streamLabel = stream.label,
                            category = event.category,
                            league = event.league,
                        ),
                    ),
                )
            }
        }

        theTvAppChannels.forEach { channel ->
            val titleKey = normalizeTitleKey(channel.name)
            if (titleKey in occupiedTitleKeys) return@forEach
            occupiedTitleKeys += titleKey
            theTvAppStreams += channel.copy(groupTitle = group)
        }

        return EpgBundle(
            channels = interleaveGuidesAndStreams(
                guides = guides,
                streamsByCategory = streamsByCategory,
                theTvAppStreams = theTvAppStreams,
                guideProgrammes = guideProgrammes,
                maxStreams = maxStreams,
            ),
            guideProgrammes = guideProgrammes,
        )
    }

    /** Keeps primary link plus at most one backup per schedule event. */
    fun limitStreamLinks(
        streams: List<DaddyLiveEventResolver.ParsedStream>,
        maxLinks: Int = SupplementConfig.MAX_STREAM_LINKS_PER_EVENT,
    ): List<DaddyLiveEventResolver.ParsedStream> = streams.take(maxLinks.coerceAtLeast(1))

    /** Guide channel first, then that category's live streams; repeats per schedule category. */
    private fun interleaveGuidesAndStreams(
        guides: Map<String, SupplementChannel>,
        streamsByCategory: Map<String, List<SupplementChannel>>,
        theTvAppStreams: List<SupplementChannel>,
        guideProgrammes: Map<String, List<GuideEventRow>>,
        maxStreams: Int,
    ): List<SupplementChannel> {
        val nowMs = System.currentTimeMillis()
        val orderedGuides = guides.values.sortedWith(
            compareBy(
                { SpecialEventSort.guideBlockEventSortKey(it.id, guideProgrammes, nowMs) },
                { SpecialEventSort.guideDisplayName(it).lowercase() },
            ),
        )
        val result = mutableListOf<SupplementChannel>()
        var streamCount = 0
        for (guide in orderedGuides) {
            val slug = guide.id.removePrefix("dlhd-guide:")
            val streams = streamsByCategory[slug].orEmpty().sortedWith(
                compareBy(
                    { SpecialEventSort.streamWindowSortKey(it, nowMs) },
                    { it.name.lowercase() },
                ),
            )
            if (streams.isEmpty() && guideProgrammes[guide.id].orEmpty().isEmpty()) continue
            result += guide
            if (streamCount >= maxStreams) continue
            for (stream in streams) {
                if (streamCount >= maxStreams) break
                result += stream
                streamCount++
            }
        }
        if (streamCount < maxStreams) {
            val orphans = theTvAppStreams.sortedWith(
                compareBy(
                    { SpecialEventSort.streamWindowSortKey(it, nowMs) },
                    { SpecialEventSort.sortKey(it.providerTag, it.name, it.eventSourceUrl) },
                    { it.name.lowercase() },
                ),
            )
            for (stream in orphans) {
                if (streamCount >= maxStreams) break
                result += stream
                streamCount++
            }
        }
        return result
    }

    private fun buildStreamName(eventTitle: String, linkLabel: String, linkIndex: Int): String {
        val core = eventTitle.substringAfter(": ", eventTitle).trim().ifEmpty { eventTitle }
        val link = linkLabel.trim()
        if (link.isEmpty() || link.equals("link", ignoreCase = true) ||
            link.matches(Regex("""Link\s*-\s*\d+""", RegexOption.IGNORE_CASE))
        ) {
            return core
        }
        return "$core ($link)"
    }

    private fun dlhdStreamKey(stream: DaddyLiveEventResolver.ParsedStream): String =
        when (stream.source) {
            DaddyLiveEventResolver.StreamSource.TV -> "tv|${stream.channelId}"
            DaddyLiveEventResolver.StreamSource.TV2 -> "tv2|${stream.channelId}"
        }

    fun normalizeTitleKey(title: String): String {
        val core = title.substringAfter(": ", title).trim().ifEmpty { title.trim() }
        return core.lowercase()
            .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun slugify(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "events" }

    fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}

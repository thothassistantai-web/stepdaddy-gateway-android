package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror
import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import java.time.Instant

import kotlinx.serialization.Serializable

/**
 * Builds Special Events supplement channels from DaddyLive schedule feeds (tv.json / tv2.json).
 */
object SpecialEventsMerger {
    data class MergeResult(
        val channels: List<SupplementChannel>,
        val dlhdStats: DaddyLiveEventResolver.ResolveStats = DaddyLiveEventResolver.ResolveStats(),
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
        maxStreams: Int = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
    ): EpgBundle {
        val (dlhdEvents, dlhdStats) = dlhdResolver.resolveFromNetwork(dlhdBaseUrl)
        return buildFromParsed(dlhdEvents, dlhdStats, maxStreams)
    }

    fun buildFromParsed(
        dlhdEvents: List<DaddyLiveEventResolver.ParsedEvent>,
        dlhdStats: DaddyLiveEventResolver.ResolveStats = DaddyLiveEventResolver.ResolveStats(),
        maxStreams: Int = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
    ): EpgBundle {
        val group = GroupTitleResolver.SPECIAL_EVENTS
        val guideProgrammes = linkedMapOf<String, MutableList<GuideEventRow>>()
        val guides = linkedMapOf<String, SupplementChannel>()
        val streamsByCategory = linkedMapOf<String, MutableList<SupplementChannel>>()
        val occupiedEventKeys = linkedSetOf<String>()
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

            val eventKey = buildEventKey(event.category, event.title, start.toEpochMilli())
            if (!occupiedEventKeys.add(eventKey)) return@forEach

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

            val mirrors = buildMirrors(event)
            if (mirrors.isEmpty()) return@forEach
            val ranked = SpecialEventMirrorRanker.orderedMirrors(mirrors)
            val primary = ranked.first()

            val titleKey = normalizeTitleKey(event.title)
            if (titleKey in occupiedTitleKeys) return@forEach
            occupiedTitleKeys += titleKey

            val streamSchedule = EventTimeExtractor.fromDlhdParsedEvent(
                event = event,
                stream = event.streams.first(),
                now = Instant.ofEpochMilli(nowMs),
            )
            val (streamStart, streamStop) = streamSchedule.window()

            val id = "dlhd-event:$eventKey"
            val displayName = buildStreamName(event.title, primary.label, 0)
            streamsByCategory.getOrPut(guideSlug) { mutableListOf() } += SupplementChannel(
                id = id,
                name = displayName,
                tvgId = "DLHD.Event.$eventKey",
                groupTitle = group,
                streamUrl = "",
                providerTag = event.league,
                tags = listOf("#events"),
                dlhdEventKey = eventKey,
                dlhdEventStreamKey = primary.streamKey,
                dlhdEventMirrors = ranked,
                eventSourceUrl = "${event.category}|${event.dateKey}|${event.timeLabel}|${event.title}",
                eventStartMs = streamStart.toEpochMilli(),
                eventStopMs = streamStop.toEpochMilli(),
                languageCode = SpecialEventLanguageIdentifier.identify(
                    SpecialEventLanguageIdentifier.Context(
                        eventTitle = event.title,
                        streamLabel = primary.label,
                        category = event.category,
                        league = event.league,
                    ),
                ),
                regionCode = SpecialEventRegionIdentifier.identify(
                    SpecialEventRegionIdentifier.Context(
                        eventTitle = event.title,
                        streamLabel = primary.label,
                        category = event.category,
                        league = event.league,
                    ),
                ),
            )
        }

        return EpgBundle(
            channels = interleaveGuidesAndStreams(
                guides = guides,
                streamsByCategory = streamsByCategory,
                guideProgrammes = guideProgrammes,
                maxStreams = maxStreams,
            ),
            guideProgrammes = guideProgrammes,
        )
    }

    fun buildEventKey(category: String, title: String, startMs: Long): String =
        shortHash(
            "${category.trim().lowercase()}|" +
                "${normalizeTitleKey(title)}|$startMs",
        )

    fun buildMirrors(event: DaddyLiveEventResolver.ParsedEvent): List<DlhdEventMirror> {
        val seen = linkedSetOf<String>()
        return buildList {
            event.streams.forEach { stream ->
                val key = dlhdStreamKey(stream)
                if (!seen.add(key.lowercase())) return@forEach
                add(
                    DlhdEventMirror(
                        streamKey = key,
                        label = stream.label.trim().ifEmpty { "Link" },
                    ),
                )
            }
        }
    }

    /** @deprecated Playlist emits one row per event; mirrors are internal. */
    fun limitStreamLinks(
        streams: List<DaddyLiveEventResolver.ParsedStream>,
        maxLinks: Int = SupplementConfig.MAX_STREAM_LINKS_PER_EVENT,
    ): List<DaddyLiveEventResolver.ParsedStream> = streams.take(maxLinks.coerceAtLeast(1))

    /** Guide channel first, then that category's live streams; repeats per schedule category. */
    private fun interleaveGuidesAndStreams(
        guides: Map<String, SupplementChannel>,
        streamsByCategory: Map<String, List<SupplementChannel>>,
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
        if (linkIndex > 0) {
            return "$core ($link)"
        }
        return core
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

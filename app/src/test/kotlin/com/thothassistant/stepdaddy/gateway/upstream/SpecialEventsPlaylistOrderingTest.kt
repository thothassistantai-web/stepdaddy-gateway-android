package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * End-to-end ordering contract for Special Events: merger → dedupe → [PlaylistBuilder].
 * Covers alphabetical guides, guide-then-events grouping, and stream dedupe.
 */
class SpecialEventsPlaylistOrderingTest {
    private val futureDateKey = "Monday 23rd June 2026 - Schedule Time UK GMT"
    private val baseUrl = "http://127.0.0.1:3000"
    private val dlhdOrigin = "https://daddylive.org"
    private val nowMs = Instant.parse("2026-06-23T12:00:00Z").toEpochMilli()

    private fun dlhdEvent(
        category: String,
        title: String,
        league: String = "OTHER",
        channelId: String,
        source: DaddyLiveEventResolver.StreamSource = DaddyLiveEventResolver.StreamSource.TV,
    ) = DaddyLiveEventResolver.ParsedEvent(
        category = category,
        dateKey = futureDateKey,
        timeLabel = "live",
        title = title,
        league = league,
        streams = listOf(
            DaddyLiveEventResolver.ParsedStream(
                label = "Link - 1",
                channelId = channelId,
                source = source,
            ),
        ),
        live = false,
    )

    /** Mirrors SupplementSource: merge, dedupe, then playlist. */
    private fun supplementsAfterMergeAndDedupe(
        dlhdEvents: List<DaddyLiveEventResolver.ParsedEvent>,
        theTvApp: List<SupplementChannel> = emptyList(),
    ): List<SupplementChannel> {
        val raw = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = dlhdEvents.size),
            theTvAppChannels = theTvApp,
            maxStreams = 50,
        )
        return SpecialEventStreamDedup.dedupeBundle(raw).channels
    }

    private fun playlistFor(supplements: List<SupplementChannel>): String =
        PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
            nowMs = nowMs,
        )

    private fun specialEventRows(playlist: String): List<SpecialEventPlaylistRow> {
        val lines = playlist.lineSequence().toList()
        val rows = mutableListOf<SpecialEventPlaylistRow>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("#EXTINF:")) {
                i++
                continue
            }
            if (!line.contains("""group-title="${GroupTitleResolver.SPECIAL_EVENTS}"""")) {
                i++
                continue
            }
            val attrs = line.substringAfter("#EXTINF:-1 ").substringBeforeLast(',')
            val title = line.substringAfterLast(',')
            val stream = lines.getOrNull(i + 1).orEmpty()
            rows += SpecialEventPlaylistRow(title = title, stream = stream, extinf = line)
            i += 2
        }
        return rows
    }

    private data class SpecialEventPlaylistRow(
        val title: String,
        val stream: String,
        val extinf: String,
    )

    @Test
    fun `merge dedupe playlist pipeline orders guides alphabetically by display name`() {
        val supplements = supplementsAfterMergeAndDedupe(
            listOf(
                dlhdEvent("Wrestling", "Wrestling : Main Event", league = "WWE", channelId = "801"),
                dlhdEvent("Baseball MLB", "MLB : Yankees vs Red Sox", league = "MLB", channelId = "802"),
                dlhdEvent("Golf", "Golf : Round 1", league = "GOLF", channelId = "803"),
                dlhdEvent("PPV Events", "PPV Events : Main Card", channelId = "804"),
                dlhdEvent("Tennis", "Tennis : Semifinal", league = "TENNIS", channelId = "805"),
            ),
        )

        val guideDisplayNames = supplements
            .filter { it.id.startsWith("dlhd-guide:") }
            .map { SpecialEventSort.guideDisplayName(it).lowercase() }
        assertEquals(guideDisplayNames.sorted(), guideDisplayNames)

        val playlist = playlistFor(supplements)
        val guideTitles = specialEventRows(playlist)
            .filter { it.stream.contains("/dlhd-event-guide/") }
            .map { it.title.lowercase() }
        assertEquals(guideTitles.sorted(), guideTitles)
        assertTrue(guideTitles.indexOfFirst { it.contains("baseball") } <
            guideTitles.indexOfFirst { it.contains("golf") })
        assertTrue(guideTitles.indexOfFirst { it.contains("ppv") } <
            guideTitles.indexOfFirst { it.contains("tennis") })
    }

    @Test
    fun `merge dedupe playlist pipeline keeps each guide immediately above its events`() {
        val supplements = supplementsAfterMergeAndDedupe(
            listOf(
                dlhdEvent("Swimming", "Swimming : Final Heat", league = "SWIMMING", channelId = "201"),
                dlhdEvent("Swimming", "Swimming : Prelims", league = "SWIMMING", channelId = "202"),
                dlhdEvent("Golf", "Golf : Round 1", league = "GOLF", channelId = "203"),
                dlhdEvent("Golf", "Golf : Round 2", league = "GOLF", channelId = "204"),
            ),
        )

        assertGuideBlockContiguous(supplements, "golf", expectedEventCount = 2)
        assertGuideBlockContiguous(supplements, "swimming", expectedEventCount = 2)

        val rows = specialEventRows(playlistFor(supplements))
        assertPlaylistGuideBlock(rows, guideSlug = "golf", eventTitleFragments = listOf("ROUND 1", "ROUND 2"))
        assertPlaylistGuideBlock(rows, guideSlug = "swimming", eventTitleFragments = listOf("FINAL HEAT", "PRELIMS"))
    }

    @Test
    fun `playlist builder reorders shuffled supplements into guide blocks`() {
        val supplements = listOf(
            event("dlhd-event:swim1", "Final Heat", "SWIMMING", "Swimming|live|Swimming : Final Heat", "tv|501"),
            guide("swimming", "SWIMMING"),
            event("dlhd-event:golf2", "Round 2", "GOLF", "Golf|live|Golf : Round 2", "tv|504"),
            guide("golf", "GOLF"),
            event("dlhd-event:golf1", "Round 1", "GOLF", "Golf|live|Golf : Round 1", "tv|503"),
            event("dlhd-event:swim2", "Prelims", "SWIMMING", "Swimming|live|Swimming : Prelims", "tv|502"),
            guide("ppv-events", "OTHER"),
            event("dlhd-event:ppv1", "Main Card", "OTHER", "PPV Events|live|PPV Events : Main Card", "tv|505"),
            guide("tennis", "TENNIS"),
            event("dlhd-event:tennis1", "Semifinal", "TENNIS", "Tennis|live|Tennis : Semifinal", "tv|506"),
        ).shuffled()

        val rows = specialEventRows(playlistFor(supplements))
        val guideTitles = rows.filter { it.stream.contains("/dlhd-event-guide/") }.map { it.title.lowercase() }
        assertEquals(guideTitles.sorted(), guideTitles)

        assertPlaylistGuideBlock(rows, "golf", listOf("ROUND 1", "ROUND 2"))
        assertPlaylistGuideBlock(rows, "swimming", listOf("FINAL HEAT", "PRELIMS"))
        assertPlaylistGuideBlock(rows, "ppv-events", listOf("MAIN CARD"))
        assertPlaylistGuideBlock(rows, "tennis", listOf("SEMIFINAL"))
    }

    @Test
    fun `dedupe removes duplicate stream keys while preserving guide event grouping`() {
        val sharedKey = "tv|777"
        val supplements = SpecialEventStreamDedup.dedupeChannels(
            listOf(
                guide("boxing", "BOXING"),
                event("dlhd-event:box-a", "Fight A", "BOXING", "Boxing|live|Boxing : Fight A", sharedKey),
                event("dlhd-event:box-dup", "Link - 1", "BOXING", "Boxing|live|Boxing : Fight B", sharedKey),
                guide("golf", "GOLF"),
                event("dlhd-event:golf1", "Round 1", "GOLF", "Golf|live|Golf : Round 1", "tv|301"),
            ),
        ).channels

        assertEquals(4, supplements.size)
        assertGuideBlockContiguous(supplements, "boxing", expectedEventCount = 1)
        assertGuideBlockContiguous(supplements, "golf", expectedEventCount = 1)
        assertEquals("dlhd-event:box-a", supplements.first { it.id.startsWith("dlhd-event:") && it.name.contains("Fight A") }.id)

        val playlist = playlistFor(supplements)
        val streamLines = playlist.lines().filter { it.startsWith("http") }
        assertEquals(streamLines.size, streamLines.toSet().size)
        val rows = specialEventRows(playlist)
        assertPlaylistGuideBlock(rows, "boxing", listOf("FIGHT A"))
        assertFalse(rows.any { it.title.contains("FIGHT B", ignoreCase = true) })
    }

    @Test
    fun `thetvapp sport rows sort after all dlhd guide blocks in playlist`() {
        val supplements = supplementsAfterMergeAndDedupe(
            dlhdEvents = listOf(
                dlhdEvent("Golf", "Golf : Round 1", league = "GOLF", channelId = "401"),
            ),
            theTvApp = listOf(
                SupplementChannel(
                    id = "sport:nfl",
                    name = "Chiefs vs Bills",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "https://example.com/nfl.m3u8",
                    providerTag = "NFL",
                ),
                SupplementChannel(
                    id = "sport:nba",
                    name = "Lakers vs Celtics",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "https://example.com/nba.m3u8",
                    providerTag = "NBA",
                ),
            ),
        )

        val rows = specialEventRows(playlistFor(supplements))
        val lastGuideIndex = rows.indexOfLast { it.stream.contains("/dlhd-event-guide/") }
        val firstSportIndex = rows.indexOfFirst { it.stream.contains("example.com/nfl") || it.stream.contains("example.com/nba") }
        assertTrue(lastGuideIndex >= 0)
        assertTrue(firstSportIndex > lastGuideIndex)

        val nflIndex = rows.indexOfFirst { it.title.contains("CHIEFS VS BILLS") }
        val nbaIndex = rows.indexOfFirst { it.title.contains("LAKERS VS CELTICS") }
        assertTrue(nflIndex >= 0 && nbaIndex > nflIndex)
    }

    @Test
    fun `duplicate tv channel ids in playlist collapse to single proxy url per event`() {
        val supplements = listOf(
            guide("baseball-mlb", "MLB"),
            event(
                id = "dlhd-event:mlb1",
                name = "Yankees vs Red Sox",
                providerTag = "MLB",
                eventSourceUrl = "Baseball MLB|$futureDateKey|19:00|MLB : Yankees vs Red Sox",
                dlhdEventStreamKey = "tv|901",
            ),
            event(
                id = "dlhd-event:mlb2",
                name = "Mets vs Phillies",
                providerTag = "MLB",
                eventSourceUrl = "Baseball MLB|$futureDateKey|22:00|MLB : Mets vs Phillies",
                dlhdEventStreamKey = "tv|902",
            ),
        )

        val playlist = playlistFor(supplements)
        val proxyUrls = playlist.lines()
            .filter { it.startsWith("http") && it.contains("/tivimate-stream/") }
        assertEquals(proxyUrls.size, proxyUrls.toSet().size)
        assertEquals(2, proxyUrls.size)

        val rows = specialEventRows(playlist)
        assertPlaylistGuideBlock(rows, "baseball-mlb", listOf("YANKEES VS RED SOX", "METS VS PHILLIES"))
    }

    private fun assertGuideBlockContiguous(
        channels: List<SupplementChannel>,
        slug: String,
        expectedEventCount: Int,
    ) {
        val guideIndex = channels.indexOfFirst { it.id == "dlhd-guide:$slug" }
        assertTrue("guide $slug missing", guideIndex >= 0)
        val events = channels.drop(guideIndex + 1).takeWhile {
            !it.id.startsWith("dlhd-guide:")
        }.filter { it.id.startsWith("dlhd-event:") && SpecialEventSort.dlhdCategorySlug(it) == slug }
        assertEquals("event count for $slug", expectedEventCount, events.size)
        assertFalse(
            channels.subList(guideIndex + 1, guideIndex + 1 + events.size)
                .any { it.id.startsWith("dlhd-guide:") },
        )
    }

    private fun assertPlaylistGuideBlock(
        rows: List<SpecialEventPlaylistRow>,
        guideSlug: String,
        eventTitleFragments: List<String>,
    ) {
        val guideIndex = rows.indexOfFirst { it.stream.contains("/dlhd-event-guide/$guideSlug.") }
        assertTrue("guide stream for $guideSlug missing", guideIndex >= 0)
        val nextGuideIndex = rows.drop(guideIndex + 1).indexOfFirst { it.stream.contains("/dlhd-event-guide/") }
        val blockEnd = if (nextGuideIndex < 0) rows.size else guideIndex + 1 + nextGuideIndex
        val block = rows.subList(guideIndex, blockEnd)
        eventTitleFragments.forEach { fragment ->
            assertTrue(
                "Expected $fragment in $guideSlug block",
                block.any { it.title.contains(fragment, ignoreCase = true) },
            )
        }
    }

    private fun guide(slug: String, league: String): SupplementChannel =
        SupplementChannel(
            id = "dlhd-guide:$slug",
            name = "${slug.replace('-', ' ').replaceFirstChar { it.titlecase() }} Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = league,
            tags = listOf("#events", "#guide"),
        )

    private fun event(
        id: String,
        name: String,
        providerTag: String,
        eventSourceUrl: String,
        dlhdEventStreamKey: String,
    ): SupplementChannel =
        SupplementChannel(
            id = id,
            name = name,
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = providerTag,
            eventSourceUrl = eventSourceUrl,
            dlhdEventStreamKey = dlhdEventStreamKey,
        )
}

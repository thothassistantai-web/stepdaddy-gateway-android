package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventStreamDedupTest {
    @Test
    fun dedupeChannels_removesDuplicateDlhdStreamKeys() {
        val sharedKey = "tv2|admin/ppv-red-sox/1"
        val channels = listOf(
            guide("baseball-mlb"),
            stream(
                id = "dlhd-event:aaa111",
                name = "Red Sox vs Yankees",
                dlhdEventStreamKey = sharedKey,
                providerTag = "MLB",
                eventSourceUrl = "Baseball|date|live|Red Sox vs Yankees",
            ),
            stream(
                id = "dlhd-event:bbb222",
                name = "Link - 1",
                dlhdEventStreamKey = sharedKey,
                providerTag = "MLB",
            ),
        )

        val result = SpecialEventStreamDedup.dedupeChannels(channels)

        assertEquals(1, result.removedCount)
        assertEquals(2, result.channels.size)
        assertEquals("dlhd-guide:baseball-mlb", result.channels[0].id)
        assertEquals("dlhd-event:aaa111", result.channels[1].id)
        assertEquals("Red Sox vs Yankees", result.channels[1].name)
    }

    @Test
    fun dedupeChannels_removesDuplicateTheTvAppStreamUrls() {
        val sharedUrl = "https://cdn.example.com/live/game.m3u8?quality=720"
        val channels = listOf(
            sport(
                id = "sport:111111",
                name = "Maple Leafs vs Canadiens",
                streamUrl = sharedUrl,
                eventSourceUrl = "https://thetvapp.link/nhl/maple-leafs/123",
                providerTag = "NHL",
                tvgId = "TVAPP.Event.abc",
            ),
            sport(
                id = "sport:222222",
                name = "Leafs @ Habs",
                streamUrl = "$sharedUrl&token=rotate-me",
                eventSourceUrl = "https://thetvapp.link/nhl/leafs/456",
            ),
        )

        val result = SpecialEventStreamDedup.dedupeChannels(channels)

        assertEquals(1, result.removedCount)
        assertEquals(1, result.channels.size)
        assertEquals("sport:111111", result.channels[0].id)
    }

    @Test
    fun dedupeChannels_preservesGuideInterleaveOrder() {
        val channels = listOf(
            guide("golf"),
            stream(
                id = "dlhd-event:g1",
                name = "Round 1",
                dlhdEventStreamKey = "tv|201",
            ),
            guide("swimming"),
            stream(
                id = "dlhd-event:s1",
                name = "Final Heat",
                dlhdEventStreamKey = "tv|202",
            ),
            stream(
                id = "dlhd-event:s1-dup",
                name = "Heat duplicate",
                dlhdEventStreamKey = "tv|202",
            ),
        )

        val result = SpecialEventStreamDedup.dedupeChannels(channels)

        assertEquals(1, result.removedCount)
        assertEquals(
            listOf("dlhd-guide:golf", "dlhd-event:g1", "dlhd-guide:swimming", "dlhd-event:s1"),
            result.channels.map { it.id },
        )
    }

    @Test
    fun canonicalStreamKey_prefersDlhdTokenOverEmptyStreamUrl() {
        val channel = stream(
            id = "dlhd-event:abc",
            name = "Event",
            dlhdEventStreamKey = "tv|99",
            streamUrl = "",
        )
        assertEquals("dlhd:tv|99", SpecialEventStreamDedup.canonicalStreamKey(channel))
    }

    @Test
    fun metadataScore_prefersRicherTitles() {
        val rich = stream(
            id = "dlhd-event:rich",
            name = "Baseball : Seattle Mariners vs Boston Red Sox",
            dlhdEventStreamKey = "tv|1",
            providerTag = "MLB",
            tvgId = "DLHD.Event.1",
            eventSourceUrl = "Baseball|date|live|Mariners",
        )
        val sparse = stream(
            id = "dlhd-event:sparse",
            name = "Link - 1",
            dlhdEventStreamKey = "tv|1",
        )
        assertTrue(SpecialEventStreamDedup.metadataScore(rich) > SpecialEventStreamDedup.metadataScore(sparse))
    }

    private fun guide(slug: String): SupplementChannel =
        SupplementChannel(
            id = "dlhd-guide:$slug",
            name = "$slug Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )

    private fun stream(
        id: String,
        name: String,
        dlhdEventStreamKey: String,
        streamUrl: String = "",
        providerTag: String? = null,
        eventSourceUrl: String? = null,
        tvgId: String? = null,
    ): SupplementChannel =
        SupplementChannel(
            id = id,
            name = name,
            tvgId = tvgId,
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = streamUrl,
            providerTag = providerTag,
            eventSourceUrl = eventSourceUrl,
            dlhdEventStreamKey = dlhdEventStreamKey,
        )

    private fun sport(
        id: String,
        name: String,
        streamUrl: String,
        eventSourceUrl: String? = null,
        providerTag: String? = null,
        tvgId: String? = null,
    ): SupplementChannel =
        SupplementChannel(
            id = id,
            name = name,
            tvgId = tvgId,
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = streamUrl,
            providerTag = providerTag,
            eventSourceUrl = eventSourceUrl,
            referer = "https://gooz.aapmains.net/new-stream-embed/42",
        )
}

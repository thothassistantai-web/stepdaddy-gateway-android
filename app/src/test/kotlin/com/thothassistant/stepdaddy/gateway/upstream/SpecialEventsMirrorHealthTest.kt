package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventsMirrorHealthTest {
    @Test
    fun summarize_counts_mirrors_from_channel_list() {
        val channels = listOf(
            eventChannel(
                key = "soccer-a",
                mirrors = listOf(
                    DlhdEventMirror(streamKey = "tv|1", label = "Primary"),
                    DlhdEventMirror(streamKey = "tv|2", label = "Backup"),
                ),
            ),
            eventChannel(
                key = "soccer-b",
                mirrors = List(6) { index ->
                    DlhdEventMirror(streamKey = "tv2|path/$index", label = "Link $index")
                },
            ),
        )

        val summary = SpecialEventsMirrorHealth.summarize(channels)

        assertEquals(2, summary.eventsWithMirrors)
        assertEquals(8, summary.totalMirrors)
        assertEquals(0, summary.healthyMirrors)
        assertEquals(4f, summary.avgMirrorsPerEvent)
    }

    @Test
    fun summarize_uses_probe_store_for_healthy_mirrors() {
        val probeStore = DlhdEventMirrorProbeStore()
        probeStore.record("match-1", "tv|1", healthy = true)
        probeStore.record("match-1", "tv|2", healthy = false, error = "tv_resolve_failed")
        val channels = listOf(
            eventChannel(
                key = "match-1",
                mirrors = listOf(
                    DlhdEventMirror(streamKey = "tv|1"),
                    DlhdEventMirror(streamKey = "tv|2"),
                    DlhdEventMirror(streamKey = "tv|3"),
                ),
            ),
        )

        val summary = SpecialEventsMirrorHealth.summarize(
            channels = channels,
            mirrorProbeStore = probeStore,
        )

        assertEquals(3, summary.totalMirrors)
        assertEquals(1, summary.healthyMirrors)
        assertEquals(1, summary.events.single().mirrorsHealthy)
    }

    @Test
    fun summarize_falls_back_to_event_health_for_active_mirror() {
        val channels = listOf(
            eventChannel(
                key = "live-final",
                mirrors = List(8) { index ->
                    DlhdEventMirror(streamKey = "tv2|mirror/$index")
                },
            ),
        )

        val summary = SpecialEventsMirrorHealth.summarize(
            channels = channels,
            activeMirrorIndexByEvent = mapOf("live-final" to 2),
            eventHealthByKey = mapOf("live-final" to DlhdEventStreamHealth.Status.HEALTHY),
        )

        assertEquals(8, summary.totalMirrors)
        assertEquals(1, summary.healthyMirrors)
        assertEquals(2, summary.events.single().activeMirrorIndex)
    }

    @Test
    fun isMirrorHealthy_respects_explicit_mirror_flag() {
        assertTrue(
            SpecialEventsMirrorHealth.isMirrorHealthy(
                mirror = DlhdEventMirror(streamKey = "tv|1", healthy = true),
                eventKey = "x",
                eventStatus = DlhdEventStreamHealth.Status.UNKNOWN,
                activeIndex = 0,
                mirrorIndex = 0,
                mirrorProbeStore = null,
            ),
        )
        assertFalse(
            SpecialEventsMirrorHealth.isMirrorHealthy(
                mirror = DlhdEventMirror(streamKey = "tv|1", healthy = false),
                eventKey = "x",
                eventStatus = DlhdEventStreamHealth.Status.HEALTHY,
                activeIndex = 0,
                mirrorIndex = 0,
                mirrorProbeStore = null,
            ),
        )
    }

    @Test
    fun mirrorsFor_falls_back_to_primary_stream_key() {
        val channel = eventChannel(
            key = "solo",
            mirrors = emptyList(),
            streamKey = "tv|999",
        )
        assertEquals("tv|999", SpecialEventsMirrorHealth.mirrorsFor(channel).single().streamKey)
    }

    private fun eventChannel(
        key: String,
        mirrors: List<DlhdEventMirror>,
        streamKey: String = mirrors.firstOrNull()?.streamKey.orEmpty(),
    ): SupplementChannel = SupplementChannel(
        id = "dlhd-event:$key",
        name = "Event $key",
        groupTitle = "Special Events",
        streamUrl = "http://127.0.0.1/dlhd-event/$key.m3u8",
        dlhdEventKey = key,
        dlhdEventStreamKey = streamKey.ifEmpty { null },
        dlhdEventMirrors = mirrors,
    )
}

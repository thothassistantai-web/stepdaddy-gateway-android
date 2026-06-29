package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightEpgBuilderGapFillTest {
    @Test
    fun `playlistIdsForEpgshareMerge skips eastern preferred when tvtv bridge exists`() {
        val filtered = LightEpgBuilder.playlistIdsForEpgshareMerge(
            setOf("HBO2.us", "Showtime.us", "StarzInBlack.us", "StarzKidsFamily.us", "ESPN.us"),
        ) { id ->
            id in setOf("HBO2.us", "Showtime.us", "StarzInBlack.us", "StarzKidsFamily.us")
        }
        assertEquals(setOf("ESPN.us"), filtered)
    }

    @Test
    fun `playlistIdsForEpgshareMerge keeps eastern id when no tvtv bridge`() {
        val filtered = LightEpgBuilder.playlistIdsForEpgshareMerge(
            setOf("HBO2.us", "ESPN.us"),
        ) { false }
        assertEquals(setOf("HBO2.us", "ESPN.us"), filtered)
    }

    @Test
    fun `groupTvgIdsByFeed uses primary feeds only`() {
        val grouped = LightEpgBuilder.groupTvgIdsByFeed(
            setOf("ESPN.us", "WNYW-DT.us_locals1", "FS1.us"),
        )
        assertTrue(grouped.isNotEmpty())
        assertTrue(grouped.keys.all { it in EpgConfig.PRIMARY_FEED_URLS })
        assertTrue(grouped.keys.none { it in EpgConfig.GAP_FILL_FEED_URLS })
    }

    @Test
    fun `groupTvgIdsByFeed routes locals to US_LOCALS1`() {
        val grouped = LightEpgBuilder.groupTvgIdsByFeed(setOf("WNYW-DT.us_locals1"))
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
            grouped.keys.single(),
        )
        assertEquals(setOf("WNYW-DT.us_locals1"), grouped.values.single())
    }

    @Test
    fun `groupTvgIdsByFeed routes US cable to US2`() {
        val grouped = LightEpgBuilder.groupTvgIdsByFeed(setOf("ESPN.us"))
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            grouped.keys.single(),
        )
    }

    @Test
    fun `isHashStyleFastId detects FAST provider hashes`() {
        assertTrue(LightEpgBuilder.isHashStyleFastId("USBD42000073E"))
        assertTrue(LightEpgBuilder.isHashStyleFastId("NoDotsId"))
        assertFalse(LightEpgBuilder.isHashStyleFastId("ESPN.us"))
        assertFalse(LightEpgBuilder.isHashStyleFastId("ABCNewsLive.us@SD"))
        assertFalse(LightEpgBuilder.isHashStyleFastId("USANetwork.us"))
    }

    @Test
    fun `groupTvgIdsByGapFillFeed routes hash ids to PLEX1`() {
        val grouped = LightEpgBuilder.groupTvgIdsByGapFillFeed(setOf("USBD42000073E"))
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
            grouped.keys.single(),
        )
        assertEquals(setOf("USBD42000073E"), grouped.values.single())
    }

    @Test
    fun `gapFillUrlForTvgId selects distro feed for distro ids`() {
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_DISTROTV1.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("SomeDistroChannel.distro"),
        )
    }

    @Test
    fun `gapFillUrlForTvgId defaults missing primary programmes to PLEX1`() {
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("MysteryChannel.us"),
        )
    }
}

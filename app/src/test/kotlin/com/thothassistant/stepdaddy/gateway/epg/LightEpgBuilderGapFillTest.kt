package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightEpgBuilderGapFillTest {
    @Test
    fun `playlistIdsForEasternTvtvPass returns preferred order with bridge only`() {
        val ids = LightEpgBuilder.playlistIdsForEasternTvtvPass(
            tvgIds = setOf("ESPN.us", "StarzInBlack.us", "HBO2.us", "Showtime.us"),
            hasTvtvBridge = { it in setOf("HBO2.us", "Showtime.us", "StarzInBlack.us") },
        )
        assertEquals(listOf("HBO2.us", "Showtime.us", "StarzInBlack.us"), ids)
    }

    @Test
    fun `playlistIdsForEasternTvtvPass caps at MAX_EASTERN_CHANNELS_PER_BUILD`() {
        val bridged = TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS
        val ids = LightEpgBuilder.playlistIdsForEasternTvtvPass(
            tvgIds = bridged,
            hasTvtvBridge = { true },
        )
        assertEquals(bridged.toList(), ids)
        assertTrue(ids.size <= TvtvUsEpgConfig.MAX_EASTERN_CHANNELS_PER_BUILD)
    }

    @Test
    fun `playlistIdsForGeneralTvtvGapFill excludes eastern preferred`() {
        val ids = LightEpgBuilder.playlistIdsForGeneralTvtvGapFill(
            tvgIds = setOf("HBO2.us", "LifetimeNetwork.us", "Showtime.us"),
            supplementTvgIds = emptySet(),
            sportsTvgIds = emptySet(),
            fastEpgTvgIds = emptySet(),
            idsWithProgrammes = emptySet(),
            hasTvtvBridge = { it in setOf("HBO2.us", "LifetimeNetwork.us", "Showtime.us") },
        )
        assertEquals(listOf("LifetimeNetwork.us"), ids)
        assertFalse(ids.any { it in TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS })
    }

    @Test
    fun `playlistIdsForGeneralTvtvGapFill prioritizes playlist tvgIds before supplements`() {
        val ids = LightEpgBuilder.playlistIdsForGeneralTvtvGapFill(
            tvgIds = setOf("LifetimeNetwork.us"),
            supplementTvgIds = setOf("LifetimeMovieNetwork.us"),
            sportsTvgIds = emptySet(),
            fastEpgTvgIds = emptySet(),
            idsWithProgrammes = emptySet(),
            hasTvtvBridge = { true },
        )
        assertEquals(listOf("LifetimeNetwork.us", "LifetimeMovieNetwork.us"), ids)
    }

    @Test
    fun `playlistIdsForGeneralTvtvGapFill respects MAX_GENERAL_CHANNELS_PER_BUILD`() {
        val bridged = (1..20).map { "Channel$it.us" }.toSet()
        val ids = LightEpgBuilder.playlistIdsForGeneralTvtvGapFill(
            tvgIds = bridged,
            supplementTvgIds = emptySet(),
            sportsTvgIds = emptySet(),
            fastEpgTvgIds = emptySet(),
            idsWithProgrammes = emptySet(),
            hasTvtvBridge = { true },
        )
        assertEquals(TvtvUsEpgConfig.MAX_GENERAL_CHANNELS_PER_BUILD, ids.size)
    }

    @Test
    fun `playlistIdsForGeneralTvtvGapFill skips ids already with programmes`() {
        val ids = LightEpgBuilder.playlistIdsForGeneralTvtvGapFill(
            tvgIds = setOf("LifetimeNetwork.us", "AMC.us"),
            supplementTvgIds = emptySet(),
            sportsTvgIds = emptySet(),
            fastEpgTvgIds = emptySet(),
            idsWithProgrammes = setOf("LifetimeNetwork.us"),
            hasTvtvBridge = { true },
        )
        assertEquals(listOf("AMC.us"), ids)
    }

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
        val localsUrl = "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz"
        assertTrue(localsUrl in grouped.keys)
        assertEquals(setOf("WNYW-DT.us_locals1"), grouped[localsUrl])
        // Locals ids still prefer US_LOCALS1 first among primary routes.
        assertEquals(localsUrl, grouped.keys.first())
    }

    @Test
    fun `groupTvgIdsByFeed routes US cable to US2`() {
        val grouped = LightEpgBuilder.groupTvgIdsByFeed(setOf("ESPN.us"))
        val us2 = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"
        assertTrue(us2 in grouped.keys)
        assertTrue(grouped[us2]!!.contains("ESPN.us"))
        // US cable also merges against sports + locals primary feeds for bridge targets.
        assertTrue(
            grouped.keys.containsAll(
                listOf(
                    us2,
                    "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
                    "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
                ),
            ),
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
    fun `gapFillUrlForTvgId routes bare us ids to primary US2`() {
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("MysteryChannel.us"),
        )
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("HBO2.us"),
        )
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("HBO2.HD.us2"),
        )
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("MoreMax.us@East"),
        )
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("WATCDT571.us@HD"),
        )
    }

    @Test
    fun `groupTvgIdsByFeed assigns US ids to all primary feeds including locals`() {
        val grouped = LightEpgBuilder.groupTvgIdsByFeed(setOf("WATCDT571.us@HD", "MoreMax.us@East"))
        assertTrue(
            grouped.keys.contains(
                "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            ),
        )
        assertTrue(
            grouped.keys.contains(
                "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
            ),
        )
        assertTrue(
            grouped["https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz"]!!
                .contains("WATCDT571.us@HD"),
        )
        assertTrue(
            grouped["https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"]!!
                .contains("MoreMax.us@East"),
        )
    }

    @Test
    fun `gapFillUrlForTvgId still defaults unknown regions to PLEX1`() {
        assertEquals(
            "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
            LightEpgBuilder.gapFillUrlForTvgId("MysteryChannel.zz"),
        )
    }

    @Test
    fun `shouldUseCacheOnlyGapFill skips network when programmes merged and not forced`() {
        assertTrue(
            LightEpgBuilder.shouldUseCacheOnlyGapFill(
                programmeCount = EpgConfig.MIN_PROGRAMMES_BEFORE_CACHE_ONLY_GAP,
                forceRefresh = false,
            ),
        )
        assertFalse(
            LightEpgBuilder.shouldUseCacheOnlyGapFill(
                programmeCount = EpgConfig.MIN_PROGRAMMES_BEFORE_CACHE_ONLY_GAP,
                forceRefresh = true,
            ),
        )
    }

    @Test
    fun `gapFillNetworkAttempts uses full budget on force refresh`() {
        assertEquals(
            0,
            LightEpgBuilder.gapFillNetworkAttempts(
                programmeCount = 10_000,
                forceRefresh = false,
            ),
        )
        assertEquals(
            EpgConfig.MAX_GAP_FILL_NETWORK_ATTEMPTS_FORCE_REFRESH,
            LightEpgBuilder.gapFillNetworkAttempts(
                programmeCount = 10_000,
                forceRefresh = true,
            ),
        )
        assertEquals(
            EpgConfig.MAX_GAP_FILL_NETWORK_ATTEMPTS,
            LightEpgBuilder.gapFillNetworkAttempts(
                programmeCount = 0,
                forceRefresh = false,
            ),
        )
    }

    @Test
    fun `woftvCandidateIds includes playlist gaps and fast ids`() {
        val candidates = LightEpgBuilder.woftvCandidateIds(
            allIds = setOf("ESPN.us", "USBD42000073E", "PlutoTVComedyMovies.us"),
            idsWithProgrammes = setOf("ESPN.us"),
            fastEpgTvgIds = setOf("USBD42000073E"),
            iptvOrgSupplementTvgIds = setOf("PlutoTVComedyMovies.us"),
        )
        assertEquals(
            setOf("USBD42000073E", "PlutoTVComedyMovies.us"),
            candidates,
        )
    }

    @Test
    fun `woftvGapFillRetryIds targets fast ids filled only by gap fill`() {
        val retry = LightEpgBuilder.woftvGapFillRetryIds(
            idsWithProgrammes = setOf("ESPN.us", "USBD42000073E"),
            idsWithProgrammesBeforeGapFill = setOf("ESPN.us"),
            fastEpgTvgIds = setOf("USBD42000073E"),
            iptvOrgSupplementTvgIds = setOf("PlutoTVComedyMovies.us"),
        )
        assertEquals(setOf("USBD42000073E"), retry)
    }

    @Test
    fun `woftvGapFillRetryIds includes playlist mongo hex after thin PLEX1`() {
        val plutoHex = "5ca670f6593a5d78f0e85aed"
        val retry = LightEpgBuilder.woftvGapFillRetryIds(
            idsWithProgrammes = setOf("ESPN.us", plutoHex),
            idsWithProgrammesBeforeGapFill = setOf("ESPN.us"),
            // Hex may arrive via playlist merge expansion rather than iptv-only fastTvgIdsForEpg
            fastEpgTvgIds = emptySet(),
            iptvOrgSupplementTvgIds = emptySet(),
        )
        assertEquals(setOf(plutoHex), retry)
    }

    @Test
    fun `woftvGapFillRetryIds skips iptv-org dot ids not in fast sets`() {
        val retry = LightEpgBuilder.woftvGapFillRetryIds(
            idsWithProgrammes = setOf("ESPN.us", "PlutoTVComedyMovies.us"),
            idsWithProgrammesBeforeGapFill = setOf("ESPN.us"),
            fastEpgTvgIds = emptySet(),
            iptvOrgSupplementTvgIds = emptySet(),
        )
        assertTrue(retry.isEmpty())
    }
}

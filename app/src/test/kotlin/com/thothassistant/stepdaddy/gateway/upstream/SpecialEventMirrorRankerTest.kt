package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventMirrorRankerTest {
    @Test
    fun rankMirrors_prefersHealthyTvMirrors() {
        val mirrors = listOf(
            DlhdEventMirror(streamKey = "tv2|backup", label = "Backup", healthy = true),
            DlhdEventMirror(streamKey = "tv|101", label = "Primary", healthy = true),
            DlhdEventMirror(streamKey = "tv|102", label = "Dead", healthy = false),
        )
        val ranked = SpecialEventMirrorRanker.rankMirrors(mirrors, hotLimit = 2)
        assertEquals(2, ranked.hot.size)
        assertTrue(ranked.hot.first().streamKey.startsWith("tv|101"))
    }

    @Test
    fun selectFailoverIndex_skipsFailedMirror() {
        val mirrors = listOf(
            DlhdEventMirror(streamKey = "tv|1", healthy = false),
            DlhdEventMirror(streamKey = "tv|2", healthy = true),
            DlhdEventMirror(streamKey = "tv|3", healthy = true),
        )
        assertEquals(1, SpecialEventMirrorRanker.selectFailoverIndex(mirrors, failedIndex = 0))
        assertEquals(2, SpecialEventMirrorRanker.selectFailoverIndex(mirrors, failedIndex = 1))
    }

    @Test
    fun rankMirrors_splitsHotAndCold() {
        val mirrors = (1..12).map { index ->
            DlhdEventMirror(streamKey = "tv|$index", label = "Link $index")
        }
        val ranked = SpecialEventMirrorRanker.rankMirrors(mirrors, hotLimit = 8)
        assertEquals(8, ranked.hot.size)
        assertEquals(4, ranked.cold.size)
    }
}

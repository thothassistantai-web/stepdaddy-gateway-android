package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaddyLiveEventResolverTest {
    private val resolver = DaddyLiveEventResolver()

    @Test
    fun parseFeeds_readsTvAndTv2Schedules() {
        val tv = javaClass.getResourceAsStream("/dlhd-tv-sample.json")!!.bufferedReader().readText()
        val tv2 = javaClass.getResourceAsStream("/dlhd-tv2-sample.json")!!.bufferedReader().readText()
        val (events, stats) = resolver.parseFeeds(tv, tv2)
        assertEquals(2, stats.tvEvents)
        assertEquals(1, stats.tv2Events)
        assertTrue(events.any { it.title.contains("NASCAR", ignoreCase = true) })
        assertTrue(events.any { it.title.contains("Mariners", ignoreCase = true) })
        assertTrue(events.any { it.streams.any { s -> s.source == DaddyLiveEventResolver.StreamSource.TV2 } })
    }
}

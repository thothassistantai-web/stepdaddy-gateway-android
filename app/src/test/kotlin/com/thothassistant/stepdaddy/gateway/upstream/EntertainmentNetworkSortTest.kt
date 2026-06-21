package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntertainmentNetworkSortTest {
    @Test
    fun `country order is US CA UK then others`() {
        val ordered = listOf("FR", "UK", "US", "CA", "DE")
            .sortedBy { EntertainmentNetworkSort.countrySortKey(it) }
        assertEquals(listOf("US", "CA", "UK", "DE", "FR"), ordered)
    }

    @Test
    fun `fx variants share family`() {
        assertEquals("fx", EntertainmentNetworkSort.familyKey("FX USA"))
        assertEquals("fx", EntertainmentNetworkSort.familyKey("FXX USA"))
        assertEquals("fx", EntertainmentNetworkSort.familyKey("FX Movie Channel"))
    }

    @Test
    fun `itv variants share family`() {
        assertEquals("itv", EntertainmentNetworkSort.familyKey("ITV 1 UK"))
        assertEquals("itv", EntertainmentNetworkSort.familyKey("ITV 4 UK"))
    }

    @Test
    fun `bbc variants share family`() {
        assertEquals("bbc", EntertainmentNetworkSort.familyKey("BBC One UK"))
        assertEquals("bbc", EntertainmentNetworkSort.familyKey("BBC America (BBCA) USA"))
    }
}

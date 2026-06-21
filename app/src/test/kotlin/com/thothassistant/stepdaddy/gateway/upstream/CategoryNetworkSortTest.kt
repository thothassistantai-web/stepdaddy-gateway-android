package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryNetworkSortTest {
    @Test
    fun `country order is US CA UK then others`() {
        val ordered = listOf("FR", "UK", "US", "CA", "DE")
            .sortedBy { CategoryNetworkSort.countrySortKey(it) }
        assertEquals(listOf("US", "CA", "UK", "DE", "FR"), ordered)
    }

    @Test
    fun `local families group broadcast networks`() {
        assertEquals("abc", CategoryNetworkSort.familyKey(GroupTitleResolver.LOCAL_CHANNELS, "ABC USA"))
        assertEquals("abc", CategoryNetworkSort.familyKey(GroupTitleResolver.LOCAL_CHANNELS, "ABC NY USA"))
        assertEquals("cbs", CategoryNetworkSort.familyKey(GroupTitleResolver.LOCAL_CHANNELS, "CBSNY USA"))
    }

    @Test
    fun `sports families group espn and sportsnet variants`() {
        assertEquals("espn", CategoryNetworkSort.familyKey(GroupTitleResolver.SPORTS, "ESPN USA"))
        assertEquals("espn", CategoryNetworkSort.familyKey(GroupTitleResolver.SPORTS, "ESPN2 USA"))
        assertEquals("sportsnet", CategoryNetworkSort.familyKey(GroupTitleResolver.SPORTS, "Sportsnet 360"))
        assertEquals("sny", CategoryNetworkSort.familyKey(GroupTitleResolver.SPORTS, "SportsNet New York (SNY)"))
    }

    @Test
    fun `entertainment families still group fx variants`() {
        assertEquals("fx", CategoryNetworkSort.familyKey(GroupTitleResolver.ENTERTAINMENT, "FX USA"))
        assertEquals("fx", CategoryNetworkSort.familyKey(GroupTitleResolver.ENTERTAINMENT, "FXX USA"))
    }

    @Test
    fun `bulk sorted groups include local sports entertainment`() {
        assertTrue(CategoryNetworkSort.isBulkSortedGroup(GroupTitleResolver.LOCAL_CHANNELS))
        assertTrue(CategoryNetworkSort.isBulkSortedGroup(GroupTitleResolver.SPORTS))
        assertTrue(CategoryNetworkSort.isBulkSortedGroup(GroupTitleResolver.ENTERTAINMENT))
    }
}

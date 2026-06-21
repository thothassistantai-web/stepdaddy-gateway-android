package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoBackfillServiceTest {
    @Test
    fun sortedGroupEntries_processesSmallestCategoryFirst() {
        val targets = listOf(
            LogoBackfillService.Target("A", null, null, null, "Entertainment"),
            LogoBackfillService.Target("B", null, null, null, "Entertainment"),
            LogoBackfillService.Target("C", null, null, null, "Sports"),
            LogoBackfillService.Target("D", null, null, null, "Kids"),
            LogoBackfillService.Target("E", null, null, null, "Kids"),
            LogoBackfillService.Target("F", null, null, null, "Music"),
        )
        val order = LogoBackfillService.sortedGroupEntries(targets.groupBy { it.groupTitle })
            .map { it.first }
        assertEquals(listOf("Music", "Sports", "Entertainment", "Kids"), order)
    }
}

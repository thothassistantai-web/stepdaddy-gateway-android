package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodSortTest {
    @Test
    fun movieSortKey_prefersReleaseDate() {
        assertEquals(2026, VodSort.movieSortKey("2026-03-01", "Old Title (1999)"))
    }

    @Test
    fun movieSortKey_fallsBackToDisplayNameYear() {
        assertEquals(2024, VodSort.movieSortKey(null, "Example (2024)"))
    }

    @Test
    fun compareMoviesByYearDesc_ordersNewestFirst() {
        assertTrue(
            VodSort.compareMoviesByYearDesc("2026", "A", "2020", "B") > 0,
        )
    }
}

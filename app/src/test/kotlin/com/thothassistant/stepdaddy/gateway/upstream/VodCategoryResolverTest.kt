package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCategoryResolverTest {
    @Test
    fun `movie genre maps to shelf`() {
        assertEquals("🎬 Action", VodCategoryResolver.movieGroupTitle("Action / Adventure"))
        assertEquals(VodCategoryResolver.LATEST_MOVIES, VodCategoryResolver.movieGroupTitle(null))
    }

    @Test
    fun `series show shelf uses show title`() {
        assertEquals(
            "📺 Breaking Bad",
            VodCategoryResolver.seriesGroupTitle("Drama", "Breaking Bad", showShelf = true),
        )
    }

    @Test
    fun `category id is stable slug`() {
        assertTrue(VodCategoryResolver.categoryId("🎬 Action").contains("action"))
    }
}

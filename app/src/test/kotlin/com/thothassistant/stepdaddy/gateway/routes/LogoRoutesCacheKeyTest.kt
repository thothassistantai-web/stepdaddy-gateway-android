package com.thothassistant.stepdaddy.gateway.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoRoutesCacheKeyTest {
    @Test
    fun `cache keys differ for urls that share a trailing filename`() {
        val a = LogoRoutes.cacheKey("https://images.metahub.space/poster/large/tt1/img")
        val b = LogoRoutes.cacheKey("https://images.metahub.space/poster/large/tt2/img")
        assertTrue(a.endsWith(".bin"))
        assertFalse(a == b)
    }
}

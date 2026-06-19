package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultChannelMatcherTest {
    @Test
    fun `matches daddylive adult site names`() {
        assertTrue(AdultChannelMatcher.matches("Pornhub"))
        assertTrue(AdultChannelMatcher.matches("Brazzers"))
        assertTrue(AdultChannelMatcher.matches("XVideos"))
        assertTrue(AdultChannelMatcher.matches("YouPorn"))
    }

    @Test
    fun `does not match adult swim`() {
        assertFalse(AdultChannelMatcher.matches("Adult Swim"))
    }
}

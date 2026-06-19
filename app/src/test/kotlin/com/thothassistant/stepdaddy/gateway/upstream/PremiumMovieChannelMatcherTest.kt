package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumMovieChannelMatcherTest {
    @Test
    fun `matches premium movie network names`() {
        assertTrue(PremiumMovieChannelMatcher.matches("HBO USA"))
        assertTrue(PremiumMovieChannelMatcher.matches("HBO2 West"))
        assertTrue(PremiumMovieChannelMatcher.matches("Showtime Next (SHO Next) USA"))
        assertTrue(PremiumMovieChannelMatcher.matches("Starz Encore Classic"))
        assertTrue(PremiumMovieChannelMatcher.matches("MGM+ USA / Epix"))
        assertTrue(PremiumMovieChannelMatcher.matches("The Movie Channel Xtra"))
        assertTrue(PremiumMovieChannelMatcher.matches("Cinemáx West"))
        assertTrue(PremiumMovieChannelMatcher.matches("Reelz Channel"))
    }

    @Test
    fun `excludes non movie brands`() {
        assertFalse(PremiumMovieChannelMatcher.matches("HBO Max USA"))
        assertFalse(PremiumMovieChannelMatcher.matches("StarzPlay CricLife 1 HD"))
        assertFalse(PremiumMovieChannelMatcher.matches("CNN USA"))
    }
}

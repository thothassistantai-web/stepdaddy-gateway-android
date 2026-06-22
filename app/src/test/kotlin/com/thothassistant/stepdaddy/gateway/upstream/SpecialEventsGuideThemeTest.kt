package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialEventsGuideThemeTest {
    @Test
    fun baseballTheme_usesBaseballWatermark() {
        val theme = SpecialEventsGuideTheme.forCategory("Baseball (MLB)", "⚾")
        assertEquals("⚾", theme.watermarkEmoji)
    }

    @Test
    fun golfTheme_usesGolfWatermark() {
        val theme = SpecialEventsGuideTheme.forCategory("Golf", "⛳")
        assertEquals("⛳", theme.watermarkEmoji)
    }
}

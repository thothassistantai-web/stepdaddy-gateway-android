package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialEventLanguageIdentifierTest {
    @Test
    fun tvaSportsStreamLabelDetectsFrench() {
        val code = SpecialEventLanguageIdentifier.identify(
            SpecialEventLanguageIdentifier.Context(
                eventTitle = "NHL : Canadiens vs Maple Leafs",
                streamLabel = "TVA Sports",
                category = "Hockey",
                league = "NHL",
            ),
        )
        assertEquals("fr", code)
    }

    @Test
    fun espnDeportesDetectsSpanish() {
        val code = SpecialEventLanguageIdentifier.identify(
            SpecialEventLanguageIdentifier.Context(
                eventTitle = "La Liga : Real Madrid vs Barcelona",
                streamLabel = "ESPN Deportes",
                category = "Soccer",
                league = "SOCCER",
            ),
        )
        assertEquals("es", code)
    }

    @Test
    fun usLeagueDefaultsToEnglish() {
        val code = SpecialEventLanguageIdentifier.identify(
            SpecialEventLanguageIdentifier.Context(
                eventTitle = "NFL : Chiefs vs Bills",
                streamLabel = "Link - 1",
                category = "Football",
                league = "NFL",
            ),
        )
        assertEquals("en", code)
    }

    @Test
    fun explicitFrenchTagInTitle() {
        val code = SpecialEventLanguageIdentifier.identify(
            SpecialEventLanguageIdentifier.Context(
                eventTitle = "Soccer : PSG vs Lyon [FR]",
                streamLabel = "Stream 1",
            ),
        )
        assertEquals("fr", code)
    }

    @Test
    fun frenchDiacriticsInTitle() {
        val code = SpecialEventLanguageIdentifier.identify(
            SpecialEventLanguageIdentifier.Context(
                eventTitle = "Football : équipe nationale",
                streamLabel = "Link",
            ),
        )
        assertEquals("fr", code)
    }

    @Test
    fun emptyContextReturnsNull() {
        assertNull(SpecialEventLanguageIdentifier.identify(SpecialEventLanguageIdentifier.Context()))
    }

    @Test
    fun identifyFromSupplementParsesDlhdMetadata() {
        val code = SpecialEventLanguageIdentifier.identifyFromSupplement(
            name = "Canadiens vs Leafs",
            providerTag = "NHL",
            eventSourceUrl = "Hockey|Sunday|20:00|NHL : Canadiens vs Leafs",
            streamLabel = "RDS",
        )
        assertEquals("fr", code)
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialEventRegionIdentifierTest {
    @Test
    fun identify_explicitTitlePrefix() {
        val code = SpecialEventRegionIdentifier.identify(
            SpecialEventRegionIdentifier.Context(
                eventTitle = "UK: Premier League : Arsenal vs Chelsea",
                category = "Soccer",
                league = "SOCCER",
            ),
        )
        assertEquals("UK", code)
    }

    @Test
    fun identify_tvaSports_isCanada() {
        val code = SpecialEventRegionIdentifier.identify(
            SpecialEventRegionIdentifier.Context(
                eventTitle = "Canadiens vs Maple Leafs",
                streamLabel = "TVA Sports",
                category = "Hockey",
                league = "NHL",
            ),
        )
        assertEquals("CA", code)
    }

    @Test
    fun identify_usLeagueDefaultsToUs() {
        val code = SpecialEventRegionIdentifier.identify(
            SpecialEventRegionIdentifier.Context(
                eventTitle = "Chiefs vs Bills",
                category = "NFL",
                league = "NFL",
            ),
        )
        assertEquals("US", code)
    }

    @Test
    fun identify_streamLabelUkFeed() {
        val code = SpecialEventRegionIdentifier.identify(
            SpecialEventRegionIdentifier.Context(
                eventTitle = "Arsenal vs Chelsea",
                streamLabel = "UK - Sky Sports",
                league = "SOCCER",
            ),
        )
        assertEquals("UK", code)
    }

    @Test
    fun identify_blankContext_returnsNull() {
        assertNull(SpecialEventRegionIdentifier.identify(SpecialEventRegionIdentifier.Context()))
    }

    @Test
    fun identifyFromSupplement_parsesDlhdMeta() {
        val code = SpecialEventRegionIdentifier.identifyFromSupplement(
            name = "Final Heat",
            providerTag = "SWIMMING",
            eventSourceUrl = "Swimming|Sunday|14:00|CA: Swimming : Final Heat",
        )
        assertEquals("CA", code)
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventLanguageResolverTest {
    @Test
    fun parseStreamLabel_extractsTrailingBroadcaster() {
        assertEquals(
            "TVA Sports",
            EventLanguageResolver.parseStreamLabel("NHL : Canadiens vs Maple Leafs (TVA Sports)"),
        )
    }

    @Test
    fun parseStreamLabel_ignoresGenericLinkLabels() {
        assertNull(EventLanguageResolver.parseStreamLabel("NFL : Chiefs vs Bills (Link - 1)"))
    }

    @Test
    fun resolveFromChannelName_tvaSportsDetectsFrench() {
        assertEquals(
            "fr",
            EventLanguageResolver.resolveFromChannelName("NHL : Canadiens vs Maple Leafs (TVA Sports)"),
        )
    }

    @Test
    fun resolveFromChannelName_rdsDetectsFrench() {
        assertEquals(
            "fr",
            EventLanguageResolver.resolveFromChannelName("NHL : Canadiens vs Maple Leafs (RDS)"),
        )
    }

    @Test
    fun resolveFromChannelName_espnDeportesDetectsSpanish() {
        assertEquals(
            "es",
            EventLanguageResolver.resolveFromChannelName("La Liga : Real Madrid vs Barcelona (ESPN Deportes)"),
        )
    }

    @Test
    fun resolveFromChannelName_univisionDetectsSpanish() {
        assertEquals(
            "es",
            EventLanguageResolver.resolveFromChannelName("MLS : LAFC vs LA Galaxy (Univision)"),
        )
    }

    @Test
    fun resolveFromChannelName_usEventDefaultsToEnglish() {
        assertEquals(
            "en",
            EventLanguageResolver.resolveFromChannelName("NFL : Chiefs vs Bills (Link - 1)"),
        )
    }

    @Test
    fun resolveFromChannelName_explicitFrenchTag() {
        assertEquals(
            "fr",
            EventLanguageResolver.resolveFromChannelName("Soccer : PSG vs Lyon [FR]"),
        )
    }

    @Test
    fun resolveFromChannelName_frenchDiacritics() {
        assertEquals(
            "fr",
            EventLanguageResolver.resolveFromChannelName("Football : équipe nationale"),
        )
    }

    @Test
    fun resolveFromChannelName_spanishDiacritics() {
        assertEquals(
            "es",
            EventLanguageResolver.resolveFromChannelName("Fútbol : selección española"),
        )
    }

    @Test
    fun resolveFromChannelName_blankReturnsNull() {
        assertNull(EventLanguageResolver.resolveFromChannelName("   "))
    }

    @Test
    fun toTvgLanguageCode_mapsIso6391() {
        assertEquals("fra", EventLanguageResolver.toTvgLanguageCode("fr"))
        assertEquals("eng", EventLanguageResolver.toTvgLanguageCode("en"))
        assertEquals("spa", EventLanguageResolver.toTvgLanguageCode("es"))
    }
}

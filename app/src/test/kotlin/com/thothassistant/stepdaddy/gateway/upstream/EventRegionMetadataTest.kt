package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRegionMetadataTest {
    @Test
    fun parseExplicitPrefix_usUkCa() {
        assertEquals("US", EventRegionMetadata.parseExplicitPrefix("US: NFL : Chiefs vs Bills"))
        assertEquals("UK", EventRegionMetadata.parseExplicitPrefix("UK: Premier League : Arsenal vs Chelsea"))
        assertEquals("CA", EventRegionMetadata.parseExplicitPrefix("CA: Swimming : Final Heat"))
    }

    @Test
    fun parseExplicitPrefix_caseInsensitive() {
        assertEquals("UK", EventRegionMetadata.parseExplicitPrefix("uk: soccer match"))
        assertEquals("CA", EventRegionMetadata.parseExplicitPrefix("Ca: hockey game"))
    }

    @Test
    fun parseExplicitPrefix_noPrefix_returnsNull() {
        assertNull(EventRegionMetadata.parseExplicitPrefix("Chiefs vs Bills"))
        assertNull(EventRegionMetadata.parseExplicitPrefix(""))
    }

    @Test
    fun normalizeCode_gbMapsToUk() {
        assertEquals("UK", EventRegionMetadata.normalizeCode("gb"))
    }

    @Test
    fun tvgCountryAttribute_emitsQuotedCode() {
        assertEquals("""tvg-country="UK"""", EventRegionMetadata.tvgCountryAttribute("uk"))
        assertEquals("""tvg-country="CA"""", EventRegionMetadata.tvgCountryAttribute("CA"))
        assertEquals("""tvg-country="US"""", EventRegionMetadata.tvgCountryAttribute("us"))
    }

    @Test
    fun tvgCountryAttribute_emptyOrInt_returnsNull() {
        assertNull(EventRegionMetadata.tvgCountryAttribute(""))
        assertNull(EventRegionMetadata.tvgCountryAttribute("INT"))
    }

    @Test
    fun escapeAttributeValue_escapesEmbeddedQuotes() {
        assertEquals("""US\"bad""", EventRegionMetadata.escapeAttributeValue("""US"bad"""))
    }

    @Test
    fun resolve_persistedRegionTakesPrecedence() {
        val result = EventRegionMetadata.resolve(
            eventTitle = "UK: Premier League : Arsenal vs Chelsea",
            league = "SOCCER",
            persistedRegionCode = "CA",
        )
        assertEquals("CA", result.countryCode)
        assertEquals("UK", result.explicitPrefix)
    }

    @Test
    fun resolve_identifiesFromTitlePrefix() {
        val result = EventRegionMetadata.resolve(
            eventTitle = "UK: Premier League : Arsenal vs Chelsea",
            category = "Soccer",
            league = "SOCCER",
        )
        assertEquals("UK", result.countryCode)
        assertEquals("UK", result.explicitPrefix)
        assertEquals("🇬🇧", result.flagEmoji)
    }

    @Test
    fun resolve_tvaSports_isCanada() {
        val result = EventRegionMetadata.resolve(
            eventTitle = "Canadiens vs Maple Leafs",
            streamLabel = "TVA Sports",
            category = "Hockey",
            league = "NHL",
        )
        assertEquals("CA", result.countryCode)
    }

    @Test
    fun resolveFromSupplement_parsesDlhdMetaPrefix() {
        val supplement = SupplementChannel(
            id = "dlhd-event:swim1",
            name = "Final Heat",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "SWIMMING",
            eventSourceUrl = "Swimming|Sunday|14:00|CA: Swimming : Final Heat",
        )
        val result = EventRegionMetadata.resolveFromSupplement(supplement)
        assertEquals("CA", result.countryCode)
        assertEquals("CA", result.explicitPrefix)
    }

    @Test
    fun resolveFromSupplement_usesPersistedRegionCode() {
        val supplement = SupplementChannel(
            id = "dlhd-event:uk1",
            name = "Arsenal vs Chelsea (Sky Sports)",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "SOCCER",
            regionCode = "UK",
            eventSourceUrl = "Soccer|Sunday|15:00|UK: Premier League : Arsenal vs Chelsea",
        )
        val result = EventRegionMetadata.resolveFromSupplement(supplement)
        assertEquals("UK", result.countryCode)
        assertTrue(
            EventRegionMetadata.tvgCountryAttribute(result.countryCode)
                ?.contains("""tvg-country="UK"""")
                ?: false,
        )
    }
}

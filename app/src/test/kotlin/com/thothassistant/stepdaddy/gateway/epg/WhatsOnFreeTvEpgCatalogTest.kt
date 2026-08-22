package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsOnFreeTvEpgCatalogTest {
    @Test
    fun placeholderTitle_detected() {
        assertTrue(
            "program information currently unavailable".contains(
                WhatsOnFreeTvEpgConfig.PLACEHOLDER_TITLE,
            ),
        )
    }

    @Test
    fun configUrls_includeJsDelivr() {
        val urls = WhatsOnFreeTvEpgConfig.cdnUrls("epg-us.json")
        assertTrue(urls.any { "jsdelivr" in it })
        assertTrue(urls.any { "raw.githubusercontent.com" in it })
    }

    @Test
    fun nameAliases_coverAuditGaps() {
        val aliases = WhatsOnFreeTvEpgConfig.NAME_ALIASES
        assertEquals("stingray greatest hits", aliases["stingray greatest holiday hits"])
        assertEquals("pluto true crime", aliases["pluto american true crime"])
        assertEquals("fox local new york", aliases["fox 5 new york ny"])
    }

    @Test
    fun minLookupScore_matchesAuditThreshold() {
        assertTrue(WhatsOnFreeTvEpgConfig.MIN_LOOKUP_SCORE >= 0.55f)
    }

    @Test
    fun fuzzyMatchKey_prefersLongestMatchingKey() {
        val keys = setOf("pluto comedy", "pluto comedy movies", "comedy")
        val norm = EpgChannelMapper.normalizeName("UK: PLUTO TV COMEDY MOVIES")
        assertEquals(
            "pluto comedy movies",
            WhatsOnFreeTvEpgCatalog.fuzzyMatchKeyStatic(norm, keys),
        )
    }

    @Test
    fun normalizeName_matchesAuditForFastChannel() {
        val norm = EpgChannelMapper.normalizeName("US: AT HOME WITH FAMILY HANDYMAN")
        assertEquals("at home with family handyman", norm)
    }
}

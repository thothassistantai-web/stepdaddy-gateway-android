package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastChannelContextTest {
    @Test
    fun parseProviderFromName_detectsSamsungSuffix() {
        assertEquals(
            "Samsung",
            FastChannelContext.parseProviderFromName("60 Days In by A&E (720p) 🇺🇸 US Samsung"),
        )
    }

    @Test
    fun parseProviderFromName_detectsPlutoWithoutEmoji() {
        assertEquals(
            "Pluto",
            FastChannelContext.parseProviderFromName("Pluto TV Crime US Pluto"),
        )
    }

    @Test
    fun parseProviderFromName_detectsEmbeddedStirrToken() {
        assertEquals(
            "STIRR",
            FastChannelContext.parseProviderFromName("City News STIRR"),
        )
    }

    @Test
    fun parseProviderFromGroup_readsIptvOrgPlaylistLabel() {
        assertEquals(
            "Samsung",
            FastChannelContext.parseProviderFromGroup("🌐 | iptv-org | Us Samsung"),
        )
        assertEquals(
            "STIRR",
            FastChannelContext.parseProviderFromGroup("🌐 | iptv-org | Us Stirr"),
        )
    }

    @Test
    fun isHashStyleFastId_matchesSamsungHashAndPlainHash() {
        assertTrue(FastChannelContext.isHashStyleFastId("USBD42000073E"))
        assertTrue(FastChannelContext.isHashStyleFastId("US1800015K5"))
        assertTrue(FastChannelContext.isHashStyleFastId("abc123hash"))
        assertFalse(FastChannelContext.isHashStyleFastId("VevoPop.us@SD"))
    }

    @Test
    fun isMongoHexId_detects24CharPlutoMongoIds() {
        assertTrue(FastChannelContext.isMongoHexId("692ebafce72f03e07e7df985"))
        assertTrue(FastChannelContext.isHashStyleFastId("692ebafce72f03e07e7df985"))
        assertFalse(FastChannelContext.isMongoHexId("PlutoTVTrueCrime.us"))
    }

    @Test
    fun isIptvOrgDotId_matchesDotAndAtSuffix() {
        assertTrue(FastChannelContext.isIptvOrgDotId("ABCNewsLive.us@SD"))
        assertTrue(FastChannelContext.isIptvOrgDotId("STIRRCityAbilene.us"))
        assertFalse(FastChannelContext.isIptvOrgDotId("USBD42000073E"))
    }

    @Test
    fun tvgIdMatchesProvider_samsungExpectsHashNotDot() {
        assertTrue(
            FastChannelContext.tvgIdMatchesProvider("USBD42000073E", "Samsung"),
        )
        assertFalse(
            FastChannelContext.tvgIdMatchesProvider("Buzzr.us@SD", "Samsung"),
        )
    }

    @Test
    fun tvgIdMatchesProvider_stirrExpectsDotNotHash() {
        assertTrue(
            FastChannelContext.tvgIdMatchesProvider("STIRRCityAbilene.us", "STIRR"),
        )
        assertFalse(
            FastChannelContext.tvgIdMatchesProvider("USBD42000073E", "STIRR"),
        )
    }

    @Test
    fun playlistIdsForHashFastEpgMerge_includesAllHashStylePlaylistIds() {
        val hex = "62ba60f059624e000781c436"
        val samsungHash = "USBD42000073E"
        val samsungNoProv = "USBB320000397"
        val cableDot = "ESPN.us"
        val ids = FastChannelContext.playlistIdsForHashFastEpgMerge(
            tvgIds = setOf(hex, samsungHash, samsungNoProv, cableDot, "PlutoTVComedyMovies.us"),
            channelNamesByTvgId = mapOf(
                hex to "US: 00S REPLAY",
                samsungHash to "US: 60 DAYS IN BY A&E",
                samsungNoProv to "US: 21 JUMP STREET",
                cableDot to "ESPN",
            ),
        )
        assertEquals(setOf(hex, samsungHash, samsungNoProv), ids)
    }

    @Test
    fun isHashFastGapFillRetryId_allowsMongoHexNotDotIds() {
        assertTrue(FastChannelContext.isHashFastGapFillRetryId("5ca670f6593a5d78f0e85aed"))
        assertTrue(FastChannelContext.isHashFastGapFillRetryId("USBD42000073E"))
        assertFalse(FastChannelContext.isHashFastGapFillRetryId("PlutoTVComedyMovies.us"))
        assertFalse(FastChannelContext.isHashFastGapFillRetryId("ESPN.us"))
    }

    @Test
    fun normalizeProvider_mapsLocalAlias() {
        assertEquals("LocalNow", FastChannelContext.normalizeProvider("local"))
        assertNull(FastChannelContext.normalizeProvider(""))
    }
}

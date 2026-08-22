package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastChannelTvgIdResolverTest {
    private val catalog = mapOf(
        "Samsung|60 days in by a and e samsung" to "USBD42000073E",
        "STIRR|city news stirr" to "STIRRCityAbilene.us",
    )

    private val resolver = FastChannelTvgIdResolver(
        catalogLookup = { name, provider ->
            val providerKey = FastChannelContext.normalizeProvider(provider) ?: return@FastChannelTvgIdResolver null
            val stripped = name.replace(Regex("\\(\\s*\\d+p\\s*\\)", RegexOption.IGNORE_CASE), "").trim()
            val norm = EpgChannelMapper.normalizeName(stripped)
            catalog["$providerKey|$norm"]
        },
        epgChannelMapper = null,
    )

    @Test
    fun resolve_prefersCatalogHashForSamsungWhenDotIdPresent() {
        val match = resolver.resolve(
            displayName = "60 Days In by A&E (720p) 🇺🇸 US Samsung",
            groupTitle = "Entertainment",
            providerTag = "Samsung",
            currentTvgId = "Buzzr.us@SD",
        )
        assertEquals("USBD42000073E", match?.tvgId)
        assertEquals("catalog_context_fix", match?.method)
    }

    @Test
    fun resolve_keepsValidSamsungHash() {
        val match = resolver.resolve(
            displayName = "60 Days In by A&E (720p) 🇺🇸 US Samsung",
            groupTitle = "Entertainment",
            providerTag = "Samsung",
            currentTvgId = "USBD42000073E",
        )
        assertEquals("USBD42000073E", match?.tvgId)
        assertEquals("current_valid", match?.method)
    }

    @Test
    fun resolve_rejectsWrongPlexHashOnStirrChannel() {
        val match = resolver.resolve(
            displayName = "City News STIRR",
            groupTitle = "News",
            providerTag = "STIRR",
            currentTvgId = "USBD42000073E",
        )
        assertEquals("STIRRCityAbilene.us", match?.tvgId)
        assertEquals("catalog_context_fix", match?.method)
    }

    @Test
    fun validateAndFix_returnsNullWhenCurrentIdValid() {
        assertNull(
            resolver.validateAndFix(
                currentTvgId = "USBD42000073E",
                displayName = "60 Days In by A&E (720p) 🇺🇸 US Samsung",
                groupTitle = null,
                providerTag = "Samsung",
            ),
        )
    }

    @Test
    fun validateAndFix_replacesWrongDotIdOnSamsung() {
        assertEquals(
            "USBD42000073E",
            resolver.validateAndFix(
                currentTvgId = "Buzzr.us@SD",
                displayName = "60 Days In by A&E (720p) 🇺🇸 US Samsung",
                groupTitle = null,
                providerTag = "Samsung",
            ),
        )
    }

    @Test
    fun resolve_fillsEmptySamsungFromCatalog() {
        val match = resolver.resolve(
            displayName = "60 Days In by A&E (720p) 🇺🇸 US Samsung",
            groupTitle = null,
            providerTag = "Samsung",
            currentTvgId = null,
        )
        assertEquals("USBD42000073E", match?.tvgId)
        assertEquals("fast_catalog", match?.method)
    }

    @Test
    fun validateAndFix_stripsQualitySuffixFromDotId() {
        assertEquals(
            "RuntimeEspanol.us",
            resolver.validateAndFix(
                currentTvgId = "RuntimeEspanol.us@SD",
                displayName = "US: RUNTIME ESPAÑOL ᴿᴬᵂ",
                groupTitle = "Movies",
                providerTag = "Roku",
            ),
        )
    }

    @Test
    fun validateAndFix_keepsRegionalSuffixOnDotId() {
        assertNull(
            resolver.validateAndFix(
                currentTvgId = "Telemundo.us@EastHD",
                displayName = "US: TELEMUNDO EAST HD ᴿᴬᵂ",
                groupTitle = "Entertainment",
                providerTag = null,
            ),
        )
    }

    @Test
    fun isMongoHexId_detects24CharPlutoMongoIds() {
        assertTrue(FastChannelContext.isMongoHexId("692ebafce72f03e07e7df985"))
        assertTrue(FastChannelContext.isHashStyleFastId("692ebafce72f03e07e7df985"))
        assertFalse(FastChannelContext.isMongoHexId("PlutoTVTrueCrime.us"))
        assertFalse(FastChannelContext.isMongoHexId("USBD42000073E"))
    }
}

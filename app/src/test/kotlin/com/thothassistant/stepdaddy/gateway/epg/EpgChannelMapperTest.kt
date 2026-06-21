package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgChannelMapperTest {
    @Test
    fun normalizeName_stripsRegionAndQualityTokens() {
        assertEquals(
            "network",
            EpgChannelMapper.normalizeName("USA Network USA HD"),
        )
    }

    @Test
    fun normalizeName_handlesAmpersandAndPlus() {
        assertEquals(
            "a and e",
            EpgChannelMapper.normalizeName("A&E USA"),
        )
    }
}

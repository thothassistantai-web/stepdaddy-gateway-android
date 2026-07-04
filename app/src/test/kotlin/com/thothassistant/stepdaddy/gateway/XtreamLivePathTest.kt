package com.thothassistant.stepdaddy.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamLivePathTest {
    @Test
    fun stripsTsAndM3u8Extensions() {
        assertEquals("44", GatewayServer.xtreamStreamId("44.m3u8"))
        assertEquals("44", GatewayServer.xtreamStreamId("44.ts"))
        assertEquals("360", GatewayServer.xtreamStreamId("360.m3u8"))
    }

    @Test
    fun keepsSeriesIdPartsBeforeFinalExtension() {
        assertEquals("12345.1.2", GatewayServer.xtreamStreamId("12345.1.2.m3u8"))
        assertEquals("12345.1.2", GatewayServer.xtreamStreamId("12345.1.2.ts"))
    }

    @Test
    fun rejectsBlankAndDotOnly() {
        assertEquals("", GatewayServer.xtreamStreamId(""))
        assertEquals("", GatewayServer.xtreamStreamId("   "))
        assertEquals("", GatewayServer.xtreamStreamId(".m3u8"))
    }
}

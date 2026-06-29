package com.thothassistant.stepdaddy.gateway.streamvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamVaultPluginSupportTest {
    @Test
    fun `manifest includes provider and configuration capabilities`() {
        val manifest = StreamVaultPluginSupport.manifestJson()
        assertTrue(manifest.contains(StreamVaultPluginContract.CAPABILITY_PROVIDER_M3U))
        assertTrue(manifest.contains(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA))
        assertTrue(manifest.contains(StreamVaultPluginContract.PLUGIN_ID))
    }

    @Test
    fun `provider url uses streamvault playlist path`() {
        val prefs = FakePluginSettings(gatewayBaseUrl = "http://127.0.0.1:3000")
        val url = StreamVaultPluginSupport.providerUrl(prefs, environment = null)
        assertEquals("http://127.0.0.1:3000/streamvault.m3u", url)
    }

    @Test
    fun `provider epg url uses gateway loopback xmltv endpoint`() {
        val prefs = FakePluginSettings(gatewayBaseUrl = "http://127.0.0.1:3000")
        val url = StreamVaultPluginSupport.providerEpgUrl(prefs, environment = null)
        assertEquals("http://127.0.0.1:3000/epg.xml", url)
    }

    @Test
    fun `apply configuration rejects invalid gateway url`() {
        val prefs = FakePluginSettings()
        val error = StreamVaultPluginSupport.applyConfigurationValues(
            prefs,
            """{"gatewayBaseUrl":""}""",
        )
        assertEquals("Gateway URL is required", error)
    }

    @Test
    fun `apply configuration persists valid gateway url`() {
        val prefs = FakePluginSettings()
        val error = StreamVaultPluginSupport.applyConfigurationValues(
            prefs,
            """{"gatewayBaseUrl":"http://192.168.1.20:3000","lanMode":true}""",
        )
        assertNull(error)
        assertEquals("http://192.168.1.20:3000", prefs.gatewayBaseUrl)
        assertTrue(prefs.lanMode)
    }
}

private class FakePluginSettings(
    override var enabled: Boolean = true,
    override var gatewayBaseUrl: String = "http://127.0.0.1:3000",
    override var lanMode: Boolean = false,
) : StreamVaultPluginSettings

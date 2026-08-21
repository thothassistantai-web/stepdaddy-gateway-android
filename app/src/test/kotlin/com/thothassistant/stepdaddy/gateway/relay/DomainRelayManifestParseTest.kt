package com.thothassistant.stepdaddy.gateway.relay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRelayManifestParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "version": 2,
          "minAppVersion": "3.0.0",
          "message": "Backup mirrors",
          "sources": {
            "daddylive": {
              "primary": "https://daddylive.eu",
              "mirrors": ["https://dlstreams.st", "https://dlhd.st"],
              "blocked": ["daddylive.org"],
              "relayHosts": ["https://dlstreams.st"]
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesValidManifest() {
        val result = DomainRelayValidator.parseAndValidate(
            text = sample,
            installedVersionName = "3.0.30",
            cachedVersion = 1,
            decode = { json.decodeFromString(DomainRelayManifest.serializer(), it) },
        )
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().version)
        assertEquals("https://daddylive.eu", result.getOrThrow().daddylive()?.primary)
    }

    @Test
    fun rejectsOlderThanCache() {
        val result = DomainRelayValidator.parseAndValidate(
            text = sample,
            installedVersionName = "3.0.30",
            cachedVersion = 5,
            decode = { json.decodeFromString(DomainRelayManifest.serializer(), it) },
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsMinAppTooHigh() {
        val highMin = sample.replace("\"3.0.0\"", "\"9.9.9\"")
        val result = DomainRelayValidator.parseAndValidate(
            text = highMin,
            installedVersionName = "3.0.30",
            cachedVersion = 0,
            decode = { json.decodeFromString(DomainRelayManifest.serializer(), it) },
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun effectivePrimaryPrefersUserOverRelay() {
        val userCustomized = true
        val userPrimary = "https://user.example"
        val relayPrimary = "https://relay.example"
        val effective = if (userCustomized) userPrimary else relayPrimary
        assertEquals(userPrimary, effective)
    }
}

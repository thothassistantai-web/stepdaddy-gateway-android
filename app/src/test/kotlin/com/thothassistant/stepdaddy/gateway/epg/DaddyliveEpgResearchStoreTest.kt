package com.thothassistant.stepdaddy.gateway.epg

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaddyliveEpgResearchStoreTest {
    @Test
    fun lookupByChannelId_returnsBundledMapping() {
        val tempDir = Files.createTempDirectory("epg-research-bundled").toFile()
        val runtimeFile = File(tempDir, "daddylive_epg_research.json")
        val store = DaddyliveEpgResearchStore.forTest(
            bundledJson = """
                {
                  "version": 1,
                  "mappings": {
                    "42": {
                      "tvg_id": "ESPN.us",
                      "confidence": 0.95,
                      "method": "iptv_org_exact",
                      "channel_name": "ESPN USA"
                    }
                  }
                }
            """.trimIndent(),
            runtimeFile = runtimeFile,
        )

        val match = store.lookupByChannelId("42")
        assertEquals("ESPN.us", match?.tvgId)
        assertEquals(0.95f, match?.confidence ?: 0f, 0.001f)
        assertEquals("iptv_org_exact", match?.method)
        tempDir.deleteRecursively()
    }

    @Test
    fun lookupByName_usesNormalizedChannelName() {
        val tempDir = Files.createTempDirectory("epg-research-name").toFile()
        val store = DaddyliveEpgResearchStore.forTest(
            bundledJson = """
                {
                  "version": 1,
                  "mappings": {
                    "100": {
                      "tvg_id": "Dave.uk",
                      "confidence": 0.92,
                      "method": "manual",
                      "channel_name": "Dave UK HD"
                    }
                  }
                }
            """.trimIndent(),
            runtimeFile = File(tempDir, "daddylive_epg_research.json"),
        )

        val match = store.lookupByName("Dave UK HD")
        assertEquals("Dave.uk", match?.tvgId)
        tempDir.deleteRecursively()
    }

    @Test
    fun runtimeOverlayOverridesBundledMapping() {
        val tempDir = Files.createTempDirectory("epg-research-runtime").toFile()
        val runtimeFile = File(tempDir, "daddylive_epg_research.json")
        runtimeFile.parentFile?.mkdirs()
        runtimeFile.writeText(
            """
            {
              "version": 1,
              "mappings": {
                "7": {
                  "tvg_id": "Fox.us",
                  "confidence": 1.0,
                  "method": "admin_import"
                }
              }
            }
            """.trimIndent(),
        )
        val store = DaddyliveEpgResearchStore.forTest(
            bundledJson = """
                {
                  "version": 1,
                  "mappings": {
                    "7": {
                      "tvg_id": "FOX.us",
                      "confidence": 0.80,
                      "method": "fuzzy_name"
                    }
                  }
                }
            """.trimIndent(),
            runtimeFile = runtimeFile,
        )

        assertEquals("Fox.us", store.lookupByChannelId("7")?.tvgId)
        tempDir.deleteRecursively()
    }

    @Test
    fun putRuntimeMapping_persistsToRuntimeFile() {
        val tempDir = Files.createTempDirectory("epg-research-save").toFile()
        val runtimeFile = File(tempDir, "daddylive_epg_research.json")
        val store = DaddyliveEpgResearchStore.forTest(
            bundledJson = """{"version":1,"mappings":{}}""",
            runtimeFile = runtimeFile,
        )

        store.putRuntimeMapping(
            channelId = "55",
            tvgId = "NBC.us",
            confidence = 0.97f,
            method = "iptv_org_exact",
            channelName = "NBC USA",
        )
        store.save()

        assertTrue(runtimeFile.isFile)
        val reloaded = DaddyliveEpgResearchStore.forTest(
            bundledJson = """{"version":1,"mappings":{}}""",
            runtimeFile = runtimeFile,
        )
        assertEquals("NBC.us", reloaded.lookupByChannelId("55")?.tvgId)
        assertEquals("NBC.us", reloaded.lookupByName("NBC USA")?.tvgId)
        tempDir.deleteRecursively()
    }

    @Test
    fun lookupByChannelId_returnsNullForUnknownId() {
        val tempDir = Files.createTempDirectory("epg-research-miss").toFile()
        val store = DaddyliveEpgResearchStore.forTest(
            bundledJson = """{"version":1,"mappings":{}}""",
            runtimeFile = File(tempDir, "daddylive_epg_research.json"),
        )

        assertNull(store.lookupByChannelId("missing"))
        tempDir.deleteRecursively()
    }
}

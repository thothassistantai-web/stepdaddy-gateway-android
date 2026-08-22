package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

class EpgStoreTest {
    @Test
    fun `feedDownloadUrl appends cache-bust query param`() {
        val url = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"
        assertEquals(
            "$url?cb=123",
            EpgConfig.feedDownloadUrl(url, cacheBustMs = 123),
        )
        assertEquals(
            "https://example.com/feed.xml.gz?x=1&cb=456",
            EpgConfig.feedDownloadUrl("https://example.com/feed.xml.gz?x=1", cacheBustMs = 456),
        )
    }

    @Test
    fun `isValidGzipFile accepts gzip magic bytes`() {
        val root = createTempDir("epg-store-test")
        val store = EpgStore.forTest(root)
        val file = File(root, "sample.xml.gz")
        GZIPOutputStream(file.outputStream()).use { gz ->
            gz.write("<?xml version=\"1.0\"?><tv/>".toByteArray())
        }
        assertTrue(store.isValidGzipFile(file))
    }

    @Test
    fun `isValidGzipFile rejects html error pages`() {
        val root = createTempDir("epg-store-test")
        val store = EpgStore.forTest(root)
        val file = File(root, "404.html")
        file.writeText("<!DOCTYPE html><title>404</title>")
        assertFalse(store.isValidGzipFile(file))
    }

    @Test
    fun `trimFeedCache keeps primary feeds and drops oldest regional caches`() {
        val root = createTempDir("epg-store-test")
        val store = EpgStore.forTest(root)
        val primary = store.feedCacheFile(EpgConfig.PRIMARY_FEED_URLS.first())
        val regional = store.feedCacheFile(EpgConfig.GAP_FILL_FEED_URLS.first())
        sparseFile(primary, 200L * 1024 * 1024)
        sparseFile(regional, 150L * 1024 * 1024)
        Thread.sleep(5)
        val olderRegional = store.feedCacheFile(EpgConfig.GAP_FILL_FEED_URLS[1])
        sparseFile(olderRegional, 150L * 1024 * 1024)

        store.trimFeedCache()

        assertTrue("primary feed cache must survive trim", primary.exists())
        assertFalse("oldest regional cache should be trimmed first", olderRegional.exists())
    }

    private fun sparseFile(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }
}

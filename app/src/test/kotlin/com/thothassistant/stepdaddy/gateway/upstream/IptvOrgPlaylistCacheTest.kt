package com.thothassistant.stepdaddy.gateway.upstream

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOrgPlaylistCacheTest {
    @Test
    fun `loadDiskOnly returns cached m3u without network`() {
        val root = Files.createTempDirectory("iptv-org-playlist-cache").toFile()
        val cacheDir = File(root, "supplement/iptv-org").also { it.mkdirs() }
        val body = """
            #EXTM3U
            #EXTINF:-1 tvg-id="Demo.us",Demo Channel
            http://127.0.0.1/stream.m3u8
        """.trimIndent()
        File(cacheDir, "us_demo.m3u").writeText(body)

        val cache = IptvOrgPlaylistCache(root, OkHttpClient(), testOnly = true)
        assertTrue(cache.hasCachedBody("us_demo.m3u"))
        assertFalse(cache.hasCachedBody("missing.m3u"))

        val loaded = cache.loadDiskOnly("us_demo.m3u")
        assertNotNull(loaded)
        assertEquals("us_demo.m3u", loaded!!.filename)
        assertTrue(loaded.fromCache)
        assertEquals(body, loaded.body)
        assertNull(cache.loadDiskOnly("missing.m3u"))
        root.deleteRecursively()
    }
}

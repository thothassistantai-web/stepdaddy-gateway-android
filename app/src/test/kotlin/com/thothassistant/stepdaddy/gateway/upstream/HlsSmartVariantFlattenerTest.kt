package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSmartVariantFlattenerTest {
    @Test
    fun isMasterPlaylist_detectsMasterWithoutExtinf() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            https://cdn.example/live.m3u8
        """.trimIndent()
        assertTrue(HlsSmartVariantFlattener.isMasterPlaylist(master))
        assertFalse(HlsSmartVariantFlattener.isMediaPlaylist(master))
    }

    @Test
    fun isMediaPlaylist_detectsExtinf() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            https://cdn.example/seg.ts
        """.trimIndent()
        assertTrue(HlsSmartVariantFlattener.isMediaPlaylist(media))
        assertFalse(HlsSmartVariantFlattener.isMasterPlaylist(media))
    }

    @Test
    fun normalizeVersionTag_stripsLeadingZero() {
        val fixed = HlsSmartVariantFlattener.normalizeVersionTag("#EXT-X-VERSION:03\n#EXTINF:1,\nhttp://x")
        assertTrue(fixed.contains("#EXT-X-VERSION:3"))
        assertFalse(fixed.contains("#EXT-X-VERSION:03"))
    }

    @Test
    fun flattenMasterToMedia_followsSingleVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            http://127.0.0.1:9/not-used
        """.trimIndent()
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:4.0,
            http://127.0.0.1:9/seg.ts
        """.trimIndent()
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(okhttp3.ResponseBody.create(null, media))
                    .build()
            }
            .build()
        val flat = HlsSmartVariantFlattener.flattenMasterToMedia(master, client)
        assertTrue(HlsSmartVariantFlattener.isMediaPlaylist(flat))
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.UpstreamChannelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlhdEmbedUrlTest {
    @Test
    fun channelIdFromEmbedUrl_numericId() {
        assertEquals(
            "51",
            DlhdEmbedUrl.channelIdFromEmbedUrl("https://daddylive.eu/player/embed.php?id=51"),
        )
    }

    @Test
    fun channelIdFromEmbedUrl_streamPrefixId() {
        assertEquals(
            "stream-144",
            DlhdEmbedUrl.channelIdFromEmbedUrl("https://daddylive.eu/player/embed.php?id=stream-144"),
        )
    }

    @Test
    fun streamSlug_stripsStreamPrefixOnce() {
        assertEquals("144", DlhdEmbedUrl.streamSlugFromChannelId("stream-144"))
        assertEquals("51", DlhdEmbedUrl.streamSlugFromChannelId("51"))
    }

    @Test
    fun buildRelayWatchUrls_prefersLiveStreamPath() {
        val urls = DlhdEmbedUrl.buildRelayWatchUrls("51", listOf("https://dlstreams.st"))
        assertEquals("https://dlstreams.st/live/stream=51", urls.first())
        assertTrue(urls.any { it.contains("/player/stream-51.php") })
        assertTrue(urls.any { it.contains("/casting/stream-51.php") })
    }

    @Test
    fun liveStreamUrl_andChannelIdFromLivePath() {
        assertEquals(
            "https://daddylive.li/live/stream=51",
            DlhdEmbedUrl.liveStreamUrlForMirror("https://daddylive.li", "51"),
        )
        assertEquals(
            "51",
            DlhdEmbedUrl.channelIdFromEmbedUrl("https://daddylive.li/live/stream=51"),
        )
        assertTrue(DlhdEmbedUrl.isLiveStreamPageUrl("https://daddylive.li/live/stream=51"))
    }

    @Test
    fun modernWatchUrls_liveThenEmbed() {
        val urls = DlhdEmbedUrl.modernWatchUrlsForMirror("https://daddylive.li", "51")
        assertEquals("https://daddylive.li/live/stream=51", urls[0])
        assertEquals("https://daddylive.li/player/embed.php?id=51", urls[1])
    }

    @Test
    fun upstreamChannelRow_resolvesNewApiShape() {
        val row = UpstreamChannelRow(
            channelName = "ABC USA",
            url = "https://daddylive.eu/player/embed.php?id=51",
        )
        assertEquals("51", row.resolvedChannelId())
        assertEquals(
            "https://daddylive.eu/player/embed.php?id=51",
            row.resolvedEmbedUrl(),
        )
    }

    @Test
    fun upstreamChannelRow_prefersLegacyChannelId() {
        val row = UpstreamChannelRow(
            channelId = "857",
            channelName = "20 Mediaset Italy",
            url = "https://daddylive.eu/player/embed.php?id=999",
        )
        assertEquals("857", row.resolvedChannelId())
    }

    @Test
    fun mirrorEmbedUrl_rewritesHost() {
        assertEquals(
            "https://daddylive.li/player/embed.php?id=51",
            DlhdEmbedUrl.mirrorEmbedUrl(
                "https://daddylive.eu/player/embed.php?id=51",
                "https://daddylive.li",
            ),
        )
    }
}

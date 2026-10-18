package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.*
import org.junit.Test

class UpstreamMigrationTest {
    @Test fun publicDirectoryIgnoresNavigationAndDeduplicatesChannels() {
        val html = """<a href="/">Home</a><a class="card" href="/watch.php?id=51"><div class="card__title">ABC &amp; News</div><div>ID: 51</div></a><a href='/watch.php?id=51'><div class='card__title'>Duplicate</div></a>"""
        val rows = DaddyLiveCatalogParser.parse(html, "https://dlive.sx/")
        assertEquals(1, rows.size)
        assertEquals("51", rows.single().channelId)
        assertEquals("ABC & News", rows.single().channelName)
        assertEquals("https://dlive.sx/stream/stream-51.php", rows.single().url)
        assertTrue(DaddyLiveCatalogParser.parse("<html>Maintenance</html>", "https://dlive.sx").isEmpty())
    }
    @Test fun playlistTokenEndpointUsesSameOriginAndCorrectChannelSlug() {
        assertEquals("https://edge.example/premium797/index.m3u8", "https://edge.example/premium797/index.m3u8")
        assertEquals("https://edge.example/api/playlist-token?slug=premium797", CdnPlaylistAccess.tokenEndpoint("https://edge.example/premium797/index.m3u8?t=expired")?.toString())
        assertEquals("https://dembed.st", CdnPlaylistAccess.origin("https://dembed.st/premiumtv/daddy.php?id=797"))
        assertNull(CdnPlaylistAccess.tokenEndpoint("https://unrelated.example/index.m3u8"))
        assertNull(CdnPlaylistAccess.tokenEndpoint("http://edge.example/premium797/index.m3u8"))
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheTvAppSportsResolverTest {
  private val resolver = TheTvAppSportsResolver()

  @Test
  fun `extracts event urls from homepage`() {
    val html = """
      <a href="https://thetvapp.link/nba/san-antonio-spurs-new-york-knicks/43353157680">Game</a>
      <a href="https://thetvapp.link/mlb/boston-red-sox-toronto-blue-jays/43112409480">MLB</a>
    """.trimIndent()
    val urls = TheTvAppSportsResolver.extractEventUrls(html)
    assertEquals(2, urls.size)
    assertTrue(urls.first().contains("/nba/"))
  }

  @Test
  fun `resolves embed playlist to supplement channel`() {
    val home = """<a href="https://thetvapp.link/world-championship-gr-l/panama-ghana/40362886704">x</a>"""
    val eventHtml = """
      <title>Panama vs Ghana | TheTvApp</title>
      <iframe src="https://gooz.aapmains.net/new-stream-embed/52168"></iframe>
    """.trimIndent()
    val playlist = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720
      https://pl.goozekhar3.space/playlist/52168/usiicard8/caxi
    """.trimIndent()

    val (channels, stats) = resolver.resolveLiveEvents(
      homepageHtml = home,
      fetchEventHtml = { eventHtml },
      fetchPlaylist = { _, _ -> playlist },
      maxEvents = 4,
    )

    assertEquals(1, stats.playable)
    assertEquals(1, channels.size)
    val ch = channels.first()
    assertTrue(ch.name.contains("Panama", ignoreCase = true))
    assertEquals(SupplementConfig.SPORTS_GROUP_TITLE, ch.groupTitle)
    assertNotNull(ch.referer)
    assertTrue(ch.streamUrl.contains("goozekhar3.space"))
  }
}

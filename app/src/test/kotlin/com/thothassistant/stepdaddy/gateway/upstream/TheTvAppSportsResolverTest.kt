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
      <span class="time-badge">In Progress</span>
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
    assertEquals(GroupTitleResolver.SPECIAL_EVENTS, ch.groupTitle)
    assertNotNull(ch.eventSourceUrl)
    assertNotNull(ch.referer)
    assertTrue(ch.streamUrl.contains("goozekhar3.space"))
    assertNotNull(ch.eventStartMs)
    assertNotNull(ch.eventStopMs)
    assertTrue((ch.eventStopMs ?: 0L) > (ch.eventStartMs ?: 0L))
  }
}

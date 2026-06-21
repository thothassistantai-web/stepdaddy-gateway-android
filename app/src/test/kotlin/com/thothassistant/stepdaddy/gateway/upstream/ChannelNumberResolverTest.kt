package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberResolverTest {
  private fun ch(
    id: String,
    name: String,
    tags: List<String> = emptyList(),
    tvgId: String? = null,
  ) = Channel(id = id, name = name, tags = tags, tvgId = tvgId)

  @Test
  fun `bulk local sports and entertainment use dedicated bands without legacy pins`() {
    val channels = listOf(
      ch("1", "CBS USA", listOf("🇺🇸", "#local")),
      ch("2", "NBC USA", listOf("🇺🇸", "#local")),
      ch("3", "FOX USA", listOf("🇺🇸", "#local")),
      ch("4", "ABC USA", listOf("🇺🇸", "#local")),
      ch("7", "SportsNet New York (SNY)", listOf("🇺🇸", "#sports")),
      ch("8", "MSG USA", listOf("🇺🇸", "#sports")),
      ch("9", "ESPN USA", listOf("🇺🇸", "#sports")),
      ch("10", "ESPN2 USA", listOf("🇺🇸", "#sports")),
      ch("11", "YES Network USA", listOf("🇺🇸", "#sports")),
      ch("12", "Fox Sports 1 USA", listOf("🇺🇸", "#sports")),
      ch("13", "MLB Network USA", listOf("🇺🇸", "#sports")),
      ch("14", "NFL Network", listOf("🇺🇸", "#sports")),
      ch("15", "CNN USA", listOf("🇺🇸", "#news")),
      ch("16", "MSNBC", listOf("🇺🇸", "#news")),
      ch("17", "Fox News", listOf("🇺🇸", "#news")),
      ch("18", "Disney Channel", listOf("🇺🇸", "#kids", "#cartoons")),
      ch("19", "Nickelodeon", listOf("🇺🇸", "#kids")),
      ch("20", "Showtime USA", listOf("🇺🇸", "#movies", "#premium")),
      ch("21", "HBO USA", listOf("🇺🇸", "#movies", "#premium")),
      ch("22", "FX USA", listOf("🇺🇸", "#entertainment")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertTrue(numbers.getValue("1") in 1..499)
    assertTrue(numbers.getValue("2") in 1..499)
    assertTrue(numbers.getValue("9") in 500..1599)
    assertTrue(numbers.getValue("14") in 500..1599)
    assertTrue(numbers.getValue("22") >= 1600)
    assertEquals(100, numbers["15"])
    assertEquals(103, numbers["16"])
    assertEquals(118, numbers["17"])
    assertEquals(250, numbers["18"])
    assertEquals(252, numbers["19"])
    assertTrue(numbers.getValue("20") >= 4000)
    assertTrue(numbers.getValue("21") >= 4000)
    assertTrue(numbers.getValue("21") < numbers.getValue("20"))
  }

  @Test
  fun `local bulk keeps US before CA and groups network families`() {
    val channels = listOf(
      ch("ca", "CBC CA", listOf("🇨🇦", "#local")),
      ch("fox", "FOX USA", listOf("🇺🇸", "#local")),
      ch("abc", "ABC USA", listOf("🇺🇸", "#local")),
      ch("cbs", "CBS USA", listOf("🇺🇸", "#local")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    val ordered = numbers.entries.sortedBy { it.value }.map { it.key }

    assertTrue(numbers.values.all { it in 1..499 })
    assertTrue(ordered.indexOf("abc") < ordered.indexOf("cbs"))
    assertTrue(ordered.indexOf("cbs") < ordered.indexOf("fox"))
    assertTrue(ordered.indexOf("fox") < ordered.indexOf("ca"))
  }

  @Test
  fun `sports bulk keeps US before CA and groups espn variants`() {
    val channels = listOf(
      ch("ca", "Sportsnet 360", listOf("🇨🇦", "#sports")),
      ch("espn2", "ESPN2 USA", listOf("🇺🇸", "#sports")),
      ch("espn", "ESPN USA", listOf("🇺🇸", "#sports")),
      ch("sny", "SportsNet New York (SNY)", listOf("🇺🇸", "#sports")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    val ordered = numbers.entries.sortedBy { it.value }.map { it.key }

    assertTrue(numbers.values.all { it in 500..1599 })
    assertTrue(ordered.indexOf("espn") < ordered.indexOf("espn2"))
    assertTrue(ordered.indexOf("ca") > ordered.indexOf("sny"))
  }

  @Test
  fun `no duplicate channel numbers across assignment`() {
    val channels = listOf(
      ch("1", "CBS USA", listOf("🇺🇸", "#local")),
      ch("2", "NBC USA", listOf("🇺🇸", "#local")),
      ch("3", "ESPN USA", listOf("🇺🇸", "#sports")),
      ch("4", "CNN USA", listOf("🇺🇸", "#news")),
      ch("5", "Random Sports", listOf("🇺🇸", "#sports")),
      ch("6", "Another Sports", listOf("🇺🇸", "#sports")),
      ch("7", "HBO USA", listOf("🇺🇸", "#movies", "#premium")),
      ch("8", "Extra Movie", listOf("🇺🇸", "#movies", "#premium")),
      ch("9", "18+ Channel", listOf("🔞", "#NSFW", "#adult")),
      ch("10", "Foreign News", listOf("🌐", "#news")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    val assigned = numbers.values

    assertEquals(assigned.size, assigned.toSet().size)
    assertTrue(numbers["9"]!! in 900..999)
  }

  @Test
  fun `tvg-id pin resolves for non-bulk categories only`() {
    val channels = listOf(
      ch("1", "WCBS HD", listOf("🇺🇸", "#local"), tvgId = "WCBSHD.us"),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    assertTrue(numbers.getValue("1") in 1..499)
  }

  @Test
  fun `adult channels land in 900 band`() {
    val channels = listOf(
      ch("1", "18+ Test", listOf("🔞", "#NSFW", "#adult")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    assertEquals(900, numbers["1"])
  }

  @Test
  fun `regional NBC sports stays in sports bulk band`() {
    val channels = listOf(
      ch("rsn", "NBC Sports Bay Area", listOf("🇺🇸", "#sports", "#regional")),
      ch("nbc", "NBC USA", listOf("🇺🇸", "#local"), tvgId = "NBC.East.Stream.us2"),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertTrue(numbers.getValue("nbc") in 1..499)
    assertTrue(numbers.getValue("rsn") in 500..1599)
  }

  @Test
  fun `international channels start at 1400`() {
    val channels = listOf(
      ch("1", "3 Schweiz", listOf("🌐")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    assertEquals(1400, numbers["1"])
  }
}

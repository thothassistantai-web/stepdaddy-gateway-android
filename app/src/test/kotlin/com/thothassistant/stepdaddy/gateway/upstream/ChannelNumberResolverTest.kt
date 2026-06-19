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
  fun `NYC anchor pins assign exact numbers`() {
    val channels = listOf(
      ch("1", "CBS USA", listOf("🇺🇸", "#local")),
      ch("2", "NBC USA", listOf("🇺🇸", "#local")),
      ch("3", "FOX USA", listOf("🇺🇸", "#local")),
      ch("4", "ABC USA", listOf("🇺🇸", "#local")),
      ch("5", "CW PIX 11 USA", listOf("🇺🇸", "#local")),
      ch("6", "PBS USA", listOf("🇺🇸", "#local")),
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
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertEquals(2, numbers["1"])
    assertEquals(4, numbers["2"])
    assertEquals(5, numbers["3"])
    assertEquals(7, numbers["4"])
    assertEquals(11, numbers["5"])
    assertEquals(13, numbers["6"])
    assertEquals(26, numbers["7"])
    assertEquals(27, numbers["8"])
    assertEquals(70, numbers["9"])
    assertEquals(74, numbers["10"])
    assertEquals(53, numbers["11"])
    assertEquals(83, numbers["12"])
    assertEquals(86, numbers["13"])
    assertEquals(88, numbers["14"])
    assertEquals(100, numbers["15"])
    assertEquals(103, numbers["16"])
    assertEquals(118, numbers["17"])
    assertEquals(250, numbers["18"])
    assertEquals(252, numbers["19"])
    assertEquals(365, numbers["20"])
    assertEquals(401, numbers["21"])
  }

  @Test
  fun `CA sports channel uses same scheme with parallel pin`() {
    val channels = listOf(
      ch("sny", "SportsNet New York (SNY)", listOf("🇺🇸", "#sports")),
      ch("sn", "Sportsnet 360", listOf("🇨🇦", "#sports")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertEquals(26, numbers["sny"])
    assertEquals(28, numbers["sn"])
  }

  @Test
  fun `unpinned channels fill sequentially within group range`() {
    val channels = listOf(
      ch("a", "Zebra Local", listOf("🇺🇸", "#local")),
      ch("b", "Alpha Local", listOf("🇺🇸", "#local")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    val values = numbers.values.toSet()

    assertEquals(2, values.size)
    assertTrue(values.all { it in 1..49 })
    assertTrue(values.contains(1))
    assertTrue(values.contains(2))
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
  fun `pin collision gives CA channel next sequential sports slot`() {
    val channels = listOf(
      ch("us", "ESPN USA", listOf("🇺🇸", "#sports")),
      ch("ca", "ESPN Canada", listOf("🇨🇦", "#sports")),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertEquals(70, numbers["us"])
    assertTrue(numbers["ca"]!! in 26..449)
    assertTrue(numbers["ca"] != 70)
  }

  @Test
  fun `tvg-id pin resolves when name is unknown`() {
    val channels = listOf(
      ch("1", "WCBS HD", listOf("🇺🇸", "#local"), tvgId = "WCBSHD.us"),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)
    assertEquals(2, numbers["1"])
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
  fun `regional NBC sports does not steal NBC local pin`() {
    val channels = listOf(
      ch("rsn", "NBC Sports Bay Area", listOf("🇺🇸", "#sports", "#regional")),
      ch("nbc", "NBC USA", listOf("🇺🇸", "#entertainment"), tvgId = "NBC.East.Stream.us2"),
    )

    val numbers = ChannelNumberResolver.assignAll(channels)

    assertEquals(4, numbers["nbc"])
    assertTrue(numbers["rsn"]!! != 4)
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

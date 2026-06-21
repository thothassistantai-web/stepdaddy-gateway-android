package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupTitleResolverTest {
  @Test
  fun `group sort order matches approved sidebar sequence`() {
    GroupTitleResolver.PLAYLIST_GROUP_SEQUENCE.forEachIndexed { index, group ->
      assertEquals(index, GroupTitleResolver.groupSortOrder(group))
    }
    assertEquals(2, GroupTitleResolver.groupSortOrder("Locals"))
    assertEquals(1, GroupTitleResolver.groupSortOrder("Premium"))
    assertEquals(8, GroupTitleResolver.groupSortOrder("🎬 | Adult Swim | Marathon"))
  }
  @Test
  fun `ACC Network USA resolves to Sports with US suffix`() {
    val resolution = GroupTitleResolver.resolve(
      "ACC Network USA",
      listOf("🇺🇸", "#sports", "#college"),
    )
    assertEquals("Sports", resolution.groupTitle)
    assertEquals("US", resolution.countryCode)
    assertEquals("🇺🇸", resolution.flagEmoji)
    assertEquals(
      "ACC Network USA 🇺🇸 US",
      ChannelTitleNormalizer.displayTitle("ACC Network USA", resolution),
    )
  }

  @Test
  fun `sports overrides premium`() {
    val resolution = GroupTitleResolver.resolve(
      "beIN Sports MAX 4 France",
      listOf("🇫🇷", "#sports", "#premium"),
    )
    assertEquals("Sports", resolution.groupTitle)
    assertEquals(
      "beIN Sports MAX 4 France 🇫🇷 FR",
      ChannelTitleNormalizer.displayTitle("beIN Sports MAX 4 France", resolution),
    )
  }

  @Test
  fun `CA sports channel uses US category scheme with CA suffix`() {
    val resolution = GroupTitleResolver.resolve(
      "Sportsnet 360",
      listOf("🇨🇦", "#sports"),
    )
    assertEquals("Sports", resolution.groupTitle)
    assertEquals("CA", resolution.countryCode)
    assertEquals(
      "Sportsnet 360 🇨🇦 CA",
      ChannelTitleNormalizer.displayTitle("Sportsnet 360", resolution),
    )
  }

  @Test
  fun `local news affiliate goes to News not Local Channels`() {
    val resolution = GroupTitleResolver.resolve(
      "ABC NY USA",
      listOf("🇺🇸", "#local", "#news"),
    )
    assertEquals("News", resolution.groupTitle)
  }

  @Test
  fun `regional sports stays in Sports`() {
    val resolution = GroupTitleResolver.resolve(
      "Sportsnet East",
      listOf("🇨🇦", "#sports", "#regional"),
    )
    assertEquals("Sports", resolution.groupTitle)
  }

  @Test
  fun `hash movies tag lands in Movies`() {
    val resolution = GroupTitleResolver.resolve(
      "ION USA",
      listOf("🇺🇸", "#movies", "#thriller"),
    )
    assertEquals("Movies", resolution.groupTitle)
  }

  @Test
  fun `premium movie tier only lands in Movies`() {
    val movies = GroupTitleResolver.resolve(
      "HBO Poland",
      listOf("🇵🇱", "#movies", "#premium"),
    )
    assertEquals("Movies", movies.groupTitle)
  }

  @Test
  fun `named premium movie networks land in Movies without premium tag`() {
    val hbo = GroupTitleResolver.resolve(
      "HBO USA",
      listOf("🇺🇸", "#movies", "#general"),
    )
    assertEquals("Movies", hbo.groupTitle)

    val showtimeNext = GroupTitleResolver.resolve(
      "Showtime Next (SHO Next) USA",
      listOf("🇺🇸", "#entertainment", "#premium"),
    )
    assertEquals("Movies", showtimeNext.groupTitle)

    val encore = GroupTitleResolver.resolve(
      "Starz Encore Classic",
      listOf("🇺🇸", "#classic", "#movies"),
    )
    assertEquals("Movies", encore.groupTitle)
  }

  @Test
  fun `mature cartoon goes to Entertainment not Kids or Adult`() {
    val resolution = GroupTitleResolver.resolve(
      "Adult Swim",
      listOf("🇺🇸", "#animation", "#adult", "#comedy"),
    )
    assertEquals("Entertainment", resolution.groupTitle)
  }

  @Test
  fun `kids cartoon channel lands in Kids`() {
    val resolution = GroupTitleResolver.resolve(
      "Cartoon Network",
      listOf("🇺🇸", "#cartoons", "#kids", "#entertainment"),
    )
    assertEquals("Kids", resolution.groupTitle)
  }

  @Test
  fun `spanish channel lands in En Espanol`() {
    val resolution = GroupTitleResolver.resolve(
      "Telemundo",
      listOf("🇺🇸", "#news", "#entertainment", "#Spanish"),
    )
    assertEquals("En Español", resolution.groupTitle)
  }

  @Test
  fun `xxx adult bucket`() {
    val resolution = GroupTitleResolver.resolve(
      "18+ Channel",
      listOf("🔞", "#NSFW", "#adult"),
    )
    assertEquals("XXX Adult", resolution.groupTitle)
    assertEquals(false, resolution.appendCountrySuffix)
  }

  @Test
  fun `pornhub lands in xxx adult`() {
    val resolution = GroupTitleResolver.resolve("Pornhub", emptyList())
    assertEquals("XXX Adult", resolution.groupTitle)
  }

  @Test
  fun `adult swim stays entertainment`() {
    val resolution = GroupTitleResolver.resolve(
      "Adult Swim",
      listOf("🇺🇸", "#animation", "#adult", "#comedy"),
    )
    assertEquals("Entertainment", resolution.groupTitle)
  }

  @Test
  fun `international globe sports stays in Sports`() {
    val resolution = GroupTitleResolver.resolve(
      "Arena Sport 1 Premium",
      listOf("🌐", "#sports", "#premium"),
    )
    assertEquals("Sports", resolution.groupTitle)
    assertEquals("INT", resolution.countryCode)
  }

  @Test
  fun `strips legacy category suffix from display title`() {
    val resolution = GroupTitleResolver.resolve(
      "ACC Network USA",
      listOf("🇺🇸", "#sports", "#college"),
    )
    assertEquals(
      "ACC Network USA 🇺🇸 US",
      ChannelTitleNormalizer.displayTitle("ACC Network USA [Sports]", resolution),
    )
  }
}

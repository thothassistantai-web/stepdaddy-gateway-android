package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberResolverMoviesTest {
    private fun ch(id: String, name: String, tags: List<String> = emptyList()) =
        Channel(id = id, name = name, tags = tags)

    @Test
    fun `movies keeps US before CA and UK`() {
        val channels = listOf(
            ch("uk1", "Starz UK", listOf("🇬🇧", "#movies")),
            ch("ca1", "HBO Canada", listOf("🇨🇦", "#movies")),
            ch("us1", "Z Movie Channel USA", listOf("🇺🇸", "#movies")),
            ch("us2", "HBO USA", listOf("🇺🇸", "#movies")),
        )

        val (numbers, _) = ChannelNumberResolver.assignPlaylist(channels, emptyList())
        val ordered = numbers.entries.sortedBy { it.value }.map { it.key }

        assertTrue(ordered.indexOf("us2") < ordered.indexOf("ca1"))
        assertTrue(ordered.indexOf("ca1") < ordered.indexOf("uk1"))
    }

    @Test
    fun `movies bulk uses 4000 band with HBO before Showtime before Starz`() {
        val channels = listOf(
            ch("starz", "Starz West", listOf("🇺🇸", "#movies")),
            ch("sho", "Showtime USA", listOf("🇺🇸", "#movies")),
            ch("hbo", "HBO USA", listOf("🇺🇸", "#movies")),
        )

        val (numbers, _) = ChannelNumberResolver.assignPlaylist(channels, emptyList())
        numbers.values.forEach { number ->
            assertTrue("expected 4000+ band, got $number", number >= 4000)
        }
        val ordered = numbers.entries.sortedBy { it.value }.map { it.key }
        assertTrue(ordered.indexOf("hbo") < ordered.indexOf("sho"))
        assertTrue(ordered.indexOf("sho") < ordered.indexOf("starz"))
    }

    @Test
    fun `premium movie names land in Movies category`() {
        val resolution = GroupTitleResolver.resolve(
            "Showtime Next (SHO Next) USA",
            listOf("🇺🇸", "#entertainment"),
        )
        assertEquals(GroupTitleResolver.MOVIES, resolution.groupTitle)
    }
}

package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberResolverEntertainmentTest {
    private fun ch(id: String, name: String, tags: List<String> = emptyList()) =
        Channel(id = id, name = name, tags = tags)

    private fun sup(id: String, name: String, tags: List<String> = emptyList()) =
        SupplementChannel(
            id = id,
            name = name,
            groupTitle = GroupTitleResolver.ENTERTAINMENT,
            streamUrl = "https://example.com/$id.m3u8",
            tags = tags,
        )

    @Test
    fun `entertainment keeps all US before CA and UK`() {
        val channels = listOf(
            ch("uk1", "BBC One UK", listOf("🇬🇧", "#entertainment")),
            ch("ca1", "CTV 2 Canada", listOf("🇨🇦", "#entertainment")),
            ch("us1", "Zebra Channel USA", listOf("🇺🇸", "#entertainment")),
            ch("us2", "Alpha Channel USA", listOf("🇺🇸", "#entertainment")),
        )
        val supplements = listOf(
            sup("iptv:us3", "Beta FAST USA", listOf("🇺🇸", "#entertainment")),
            sup("iptv:uk2", "Dave UK", listOf("🇬🇧", "#entertainment")),
        )

        val (channelNumbers, supplementNumbers) = ChannelNumberResolver.assignPlaylist(channels, supplements)
        val ordered = buildList {
            channelNumbers.forEach { (id, number) -> add(number to id) }
            supplementNumbers.forEach { (id, number) -> add(number to id) }
        }.sortedBy { it.first }

        val regions = ordered.map { (_, id) ->
            when {
                id.startsWith("iptv:") -> supplements.first { it.id == id }.name
                else -> channels.first { it.id == id }.name
            }
        }

        val firstCa = regions.indexOfFirst { it.contains("Canada") || it.contains("CTV") }
        val firstUk = regions.indexOfFirst { it.contains(" UK") || it.endsWith("UK") }
        val lastUs = regions.indexOfLast { it.contains("USA") || it.contains(" US") }

        assertTrue(firstCa > lastUs)
        assertTrue(firstUk > firstCa)
    }

    @Test
    fun `entertainment bulk uses 1600 band with no pin exceptions`() {
        val channels = listOf(
            ch("derry", "DerryTV 23 (720p)", listOf("🇺🇸", "#entertainment")),
            ch("fx", "FX USA", listOf("🇺🇸", "#entertainment")),
            ch("usa", "USA Network", listOf("🇺🇸", "#entertainment")),
            ch("tnt", "TNT USA", listOf("🇺🇸", "#entertainment")),
        )
        val supplements = listOf(
            sup("iptv:movies", "24 Hour Free Movies (720p)", listOf("🇺🇸", "#entertainment")),
            sup("iptv:uk", "AMC Europe", listOf("🇬🇧", "#entertainment")),
        )

        val (channelNumbers, supplementNumbers) = ChannelNumberResolver.assignPlaylist(channels, supplements)
        (channelNumbers.values + supplementNumbers.values).forEach { number ->
            assertTrue("expected 1600+ band, got $number", number >= 1600)
        }
        assertTrue(supplementNumbers.getValue("iptv:uk") > supplementNumbers.getValue("iptv:movies"))
    }

    @Test
    fun `entertainment groups fx variants together`() {
        val channels = listOf(
            ch("fxx", "FXX West", listOf("🇺🇸", "#entertainment")),
            ch("fx", "FX West", listOf("🇺🇸", "#entertainment")),
            ch("other", "AMC West", listOf("🇺🇸", "#entertainment")),
        )

        val (numbers, _) = ChannelNumberResolver.assignPlaylist(channels, emptyList())
        val ordered = numbers.entries.sortedBy { it.value }.map { it.key }

        assertTrue(ordered.indexOf("fx") < ordered.indexOf("fxx"))
    }
}

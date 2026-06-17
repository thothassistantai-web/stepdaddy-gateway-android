package com.nova.stepdaddylivehd.gateway.upstream

import com.nova.stepdaddylivehd.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBuilderTest {
    private fun ch(
        id: String,
        name: String,
        tags: List<String> = emptyList(),
        tvgId: String? = null,
    ) = Channel(id = id, name = name, tags = tags, tvgId = tvgId)

    @Test
    fun `tivimate playlist emits channels in ascending tvg-chno order`() {
        val channels = listOf(
            ch("espn", "ESPN USA", listOf("🇺🇸", "#sports")),
            ch("cbs", "CBS USA", listOf("🇺🇸", "#local")),
            ch("cnn", "CNN USA", listOf("🇺🇸", "#news")),
            ch("nbc", "NBC USA", listOf("🇺🇸", "#local")),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
        )

        val chnos = Regex("""tvg-chno="(\d+)"""")
            .findAll(playlist)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals(listOf(2, 4, 70, 100), chnos)
        assertTrue(chnos.zipWithNext().all { (left, right) -> left <= right })
    }
}

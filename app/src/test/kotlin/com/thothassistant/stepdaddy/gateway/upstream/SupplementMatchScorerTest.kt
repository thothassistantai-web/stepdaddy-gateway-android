package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementMatchScorerTest {
    private fun indexes(vararg channels: Channel) =
        SupplementImportMatcher.buildDaddyIndexes(channels.toList())

    @Test
    fun `rejects ESPN vs ESPN Deportes`() {
        val daddy = Channel(id = "70", name = "ESPN USA", tags = listOf("🇺🇸", "#sports"), tvgId = "ESPN.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "ESPN Deportes",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateCountryHint = "US",
        )
        assertNull(match)
    }

    @Test
    fun `rejects BBC One vs BBC News`() {
        val daddy = Channel(id = "1", name = "BBC One", tags = listOf("🇬🇧"), tvgId = "BBCOne.uk")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "BBC News",
            candidateTvgId = "BBCNews.uk",
            indexes = indexes(daddy),
        )
        assertNull(match)
    }

    @Test
    fun `rejects US vs UK same core name`() {
        val daddy = Channel(id = "2", name = "Sky Sports Main Event USA", tags = listOf("🇺🇸", "#sports"))
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "Sky Sports Main Event UK",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateCountryHint = "UK",
        )
        assertNull(match)
    }

    @Test
    fun `rejects CNN vs CNN Turk without shared identity`() {
        val daddy = Channel(id = "3", name = "CNN", tags = listOf("🇺🇸", "#news"), tvgId = "CNN.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "CNN Türk",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateCountryHint = "TR",
        )
        assertNull(match)
    }

    @Test
    fun `accepts exact same name with matching region`() {
        val daddy = Channel(id = "70", name = "ESPN USA", tags = listOf("🇺🇸", "#sports"), tvgId = "ESPN.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "ESPN USA",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateCountryHint = "US",
        )
        assertEquals("70", match?.daddyChannelId)
        assertTrue(match!!.score >= SupplementMatchScorer.MIN_SCORE)
    }

    @Test
    fun `accepts tvg-id match including at-suffix`() {
        val daddy = Channel(id = "1", name = "BBC One", tags = listOf("🇬🇧"), tvgId = "BBCOne.uk")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "BBC One HD",
            candidateTvgId = "BBCOne.uk@HD",
            indexes = indexes(daddy),
        )
        assertEquals("1", match?.daddyChannelId)
        assertEquals(100, match?.score)
    }

    @Test
    fun `accepts exact tvg-id even when candidate country hint is INT`() {
        // Free-TV / dulo often tag rows INT while DaddyLive tvg encodes .us — must still consolidate.
        val daddy = Channel(id = "345", name = "CNN USA", tags = listOf("🇺🇸", "#news"), tvgId = "CNN.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "CNN",
            candidateTvgId = "CNN.us",
            indexes = indexes(daddy),
            candidateCountryHint = "INT",
        )
        assertEquals("345", match?.daddyChannelId)
        assertEquals(100, match?.score)
    }

    @Test
    fun `INT country hint is compatible with US core-name match`() {
        val daddy = Channel(id = "327", name = "MSNBC", tags = listOf("🇺🇸", "#news"), tvgId = "MSNBC.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "MSNBC",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateCountryHint = "INT",
        )
        assertEquals("327", match?.daddyChannelId)
        assertTrue(match!!.score >= SupplementMatchScorer.MIN_SCORE)
    }

    @Test
    fun `accepts short name with region hint from playlist`() {
        val daddy = Channel(id = "3", name = "CNN", tags = listOf("🇺🇸", "#news"), tvgId = "CNN.us")
        val match = SupplementMatchScorer.bestMatch(
            candidateName = "CNN",
            candidateTvgId = null,
            indexes = indexes(daddy),
            candidateSourcePlaylist = "playlist_usa.m3u8",
        )
        assertEquals("3", match?.daddyChannelId)
    }

    @Test
    fun `does not consolidate on legacy normalize alone across regions`() {
        // Old matcher stripped USA/UK so both cores became identical.
        val us = Channel(id = "10", name = "Discovery USA", tags = listOf("🇺🇸"))
        val match = SupplementImportMatcher.resolveDaddyChannelId(
            name = "Discovery UK",
            tvgId = null,
            indexes = indexes(us),
            countryHint = "UK",
        )
        assertNull(match)
    }

    @Test
    fun `override applier strips denylist and adds manuals`() {
        val auto = mapOf(
            "70" to listOf(
                com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
                    streamUrl = "https://a.example/espn.m3u8",
                    label = "Free-TV",
                ),
                com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
                    streamUrl = "https://b.example/bad.m3u8",
                    label = "iptv",
                ),
            ),
        )
        val bad = auto["70"]!![1]
        val denylist = listOf(
            SupplementMatchScorer.pairKey("70", SupplementMatchScorer.mirrorFingerprint(bad)),
        )
        val manual = ManualFallbackAttachment(
            daddyChannelId = "70",
            mirror = com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
                duloChannelId = "espn-id",
                label = "dulo",
            ),
            supplementName = "ESPN",
        )
        val applied = SupplementFallbackOverridesApplier.apply(
            auto,
            ConsolidationOverrides(denylist = denylist, manualAttachments = listOf(manual)),
        )
        assertEquals(2, applied["70"]?.size)
        assertTrue(applied["70"]!!.none { it.streamUrl.contains("bad") })
        assertTrue(applied["70"]!!.any { it.duloChannelId == "espn-id" })
    }

    @Test
    fun `filter consolidate uses scorer not legacy normalize`() {
        val daddy = listOf(Channel(id = "70", name = "ESPN USA", tags = listOf("🇺🇸"), tvgId = "ESPN.us"))
        val entries = listOf(
            M3uParser.Entry(
                name = "ESPN Deportes",
                streamUrl = "https://example.com/deportes.m3u8",
                sourcePlaylist = "playlist_usa.m3u8",
            ),
            M3uParser.Entry(
                name = "ESPN USA",
                streamUrl = "https://example.com/espn.m3u8",
                sourcePlaylist = "playlist_usa.m3u8",
            ),
        )
        val out = SupplementDedup.filterNewChannels(
            entries = entries,
            daddyChannels = daddy,
            importMode = SupplementImportMode.CONSOLIDATE_FALLBACKS,
        ) { entry, group ->
            com.thothassistant.stepdaddy.gateway.model.SupplementChannel(
                id = "t:${entry.name}",
                name = entry.name,
                groupTitle = group.ifEmpty { "x" },
                streamUrl = entry.streamUrl,
            )
        }
        assertEquals(1, out.channels.size)
        assertEquals("ESPN Deportes", out.channels.single().name)
        assertEquals(1, out.daddyFallbacks["70"]?.size)
        assertFalse(out.daddyFallbacks["70"].orEmpty().any { it.streamUrl.contains("deportes") })
    }
}

package com.thothassistant.stepdaddy.gateway.relay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCatalogRelayMergeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesAndDedupesByTmdbId() {
        val raw = """
            {
              "version": 1,
              "movies": [
                {
                  "tmdbId": 550,
                  "title": "Fight Club",
                  "year": "1999",
                  "streams": [
                    { "url": "https://cdn.example/a.m3u8", "quality": "720p" },
                    { "url": "https://cdn.example/b.m3u8", "quality": "1080p" }
                  ]
                },
                {
                  "tmdbId": 550,
                  "title": "Fight Club (dup)",
                  "streams": [
                    { "url": "https://cdn.example/c.m3u8", "quality": "480p" }
                  ]
                }
              ],
              "shows": [
                {
                  "tmdbId": 1396,
                  "title": "Breaking Bad",
                  "season": 1,
                  "episode": 1,
                  "streams": [{ "url": "https://cdn.example/bb.m3u8" }]
                }
              ]
            }
        """.trimIndent()
        val parsed = VodCatalogRelayValidator.parseAndValidate(
            text = raw,
            installedVersionName = "3.0.30",
            cachedVersion = 0,
            decode = { json.decodeFromString(VodCatalogRelayManifest.serializer(), it) },
        ).getOrThrow()
        val deduped = VodCatalogRelayMerge.dedupeManifest(parsed)
        assertEquals(1, deduped.movies.size)
        assertEquals(3, deduped.movies.first().streams.size)
        assertEquals(1, deduped.shows.size)
    }

    @Test
    fun titleYearDedupWhenNoTmdb() {
        val a = VodRelayMovie(title = "The Matrix", year = "1999", streams = emptyList())
        val b = VodRelayMovie(title = "Matrix", year = "1999", streams = emptyList())
        // normalize strips articles — "the matrix" and "matrix" both become "matrix"
        assertEquals(
            VodCatalogRelayValidator.movieIdentityKey(a),
            VodCatalogRelayValidator.movieIdentityKey(b),
        )
    }

    @Test
    fun mergeIntoCatalogPrefersOverlayQuality() {
        val existing = listOf(
            com.thothassistant.stepdaddy.gateway.upstream.TmdbVodCatalog.Movie(
                tmdbId = 550,
                title = "Fight Club",
                releaseDate = "1999",
            ),
        )
        val overlay = listOf(
            VodRelayMovie(
                tmdbId = 550,
                title = "Fight Club",
                year = "1999",
                streams = listOf(VodRelayStream(url = "https://cdn.example/x.m3u8", quality = "1080p")),
            ),
            VodRelayMovie(
                tmdbId = 680,
                title = "Pulp Fiction",
                year = "1994",
                streams = listOf(VodRelayStream(url = "https://cdn.example/y.m3u8")),
            ),
        )
        val merged = VodCatalogRelayMerge.mergeMoviesIntoCatalog(existing, overlay)
        assertEquals(2, merged.movies.size)
        val fight = merged.movies.first { it.tmdbId == 550 }
        assertEquals("1080p", fight.streamQuality)
        assertTrue(merged.removedCount >= 0)
    }

    @Test
    fun rejectsInvalidStreamHost() {
        val raw = """
            {"version":1,"movies":[{"tmdbId":1,"title":"X","streams":[{"url":"javascript:alert(1)"}]}]}
        """.trimIndent()
        val result = VodCatalogRelayValidator.parseAndValidate(
            text = raw,
            installedVersionName = "3.0.30",
            cachedVersion = 0,
            decode = { json.decodeFromString(VodCatalogRelayManifest.serializer(), it) },
        )
        assertTrue(result.isFailure)
    }
}

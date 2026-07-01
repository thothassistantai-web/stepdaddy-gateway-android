package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsembedListCatalogParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses vsembed latest movies page`() {
        val payload = """
            {
              "result": [
                {
                  "imdb_id": "tt0137523",
                  "tmdb_id": "550",
                  "title": "Fight Club 1999",
                  "embed_url": "https://vidsrc.me/embed/movie?imdb=tt0137523",
                  "quality": "1080p",
                  "time_added": "2026-06-30 12:00:00"
                }
              ],
              "pages": 1816
            }
        """.trimIndent()

        val parsed = json.decodeFromString<VsembedListCatalog.ListResponse>(payload)
        assertEquals(1, parsed.result.size)
        assertEquals("550", parsed.result.first().tmdb_id)
        assertEquals("tt0137523", parsed.result.first().imdb_id)
        assertEquals("1080p", parsed.result.first().quality)
    }

    @Test
    fun `cleanListTitle strips trailing year`() {
        assertEquals("Fight Club", TmdbVodConfig.cleanListTitle("Fight Club 1999"))
        assertEquals("Dune", TmdbVodConfig.cleanListTitle("Dune"))
    }

    @Test
    fun `embed mirrors prefer vsembed domains`() {
        assertTrue(VsembedConfig.EMBED_MIRRORS.first().contains("vsembed"))
    }

    @Test
    fun `parses vsembed latest episodes page`() {
        val payload = """
            {
              "result": [
                {
                  "imdb_id": "tt0944947",
                  "tmdb_id": "1399",
                  "show_title": "Game of Thrones",
                  "season": "1",
                  "episode": "1",
                  "quality": "1080p",
                  "time_added": "2026-06-30 12:00:00"
                }
              ],
              "pages": 420
            }
        """.trimIndent()

        @kotlinx.serialization.Serializable
        data class EpisodeListResponse(
            val result: List<VsembedListCatalog.EpisodeListEntry> = emptyList(),
        )

        val parsed = json.decodeFromString<EpisodeListResponse>(payload)
        assertEquals(1, parsed.result.size)
        assertEquals("1399", parsed.result.first().tmdb_id)
        assertEquals("1", parsed.result.first().season)
        assertEquals("1", parsed.result.first().episode)
        assertEquals("Game of Thrones", parsed.result.first().show_title)
    }

    @Test
    fun `episodeDisplayTitle formats season and episode`() {
        assertEquals("Game of Thrones - S01E01", TmdbVodConfig.episodeDisplayTitle("Game of Thrones", 1, 1))
    }

    @Test
    fun `movieDisplayTitle adds year in xtream style`() {
        assertEquals("Fight Club (1999)", TmdbVodConfig.movieDisplayTitle("Fight Club", "1999-01-01"))
        assertEquals("Dune (2021)", TmdbVodConfig.movieDisplayTitle("Dune 2021", null))
    }

    @Test
    fun `metahub poster url uses imdb id`() {
        assertTrue(TmdbVodConfig.metahubPosterUrl("tt0137523").contains("tt0137523"))
    }
}

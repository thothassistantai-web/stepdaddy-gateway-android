package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbVodCatalogParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CinemetaCatalog(
        val metas: List<CinemetaMeta> = emptyList(),
    )

    @Serializable
    private data class CinemetaMeta(
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("moviedb_id") val moviedbId: Int? = null,
        val name: String = "",
        val description: String = "",
        val year: String? = null,
        val poster: String? = null,
        @SerialName("imdbRating") val imdbRating: String? = null,
    )

    @Test
    fun `parses cinemeta catalog payload`() {
        val payload = """
            {
              "metas": [
                {
                  "imdb_id": "tt0137523",
                  "moviedb_id": 550,
                  "name": "Fight Club",
                  "description": "A ticking-time-bomb insomniac",
                  "year": "1999",
                  "poster": "https://images.metahub.space/poster/small/tt0137523/img",
                  "imdbRating": "8.4"
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<CinemetaCatalog>(payload)
        assertEquals(1, parsed.metas.size)
        val meta = parsed.metas.first()
        assertEquals(550, meta.moviedbId)
        assertEquals("Fight Club", meta.name)
        assertEquals("tt0137523", meta.imdbId)
        assertTrue(meta.description.contains("insomniac"))
    }
}

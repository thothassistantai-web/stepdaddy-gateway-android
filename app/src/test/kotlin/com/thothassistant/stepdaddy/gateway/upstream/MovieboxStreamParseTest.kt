package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieboxStreamParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ApiEnvelope(
        val code: Int = -1,
        val data: StreamData? = null,
    )

    @Serializable
    private data class StreamData(
        val streams: List<StreamFile> = emptyList(),
    )

    @Serializable
    private data class StreamFile(
        val url: String = "",
        val resolutions: String = "",
    )

    @Test
    fun `parses moviebox stream envelope`() {
        val payload = """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "streams": [
                  {
                    "format": "MP4",
                    "url": "https://cdn.example.com/movie.mp4",
                    "resolutions": "720"
                  },
                  {
                    "format": "MP4",
                    "url": "https://cdn.example.com/movie-1080.mp4",
                    "resolutions": "1080"
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ApiEnvelope>(payload)
        val best = parsed.data?.streams?.maxByOrNull { it.resolutions.toIntOrNull() ?: 0 }
        assertEquals(0, parsed.code)
        assertTrue(best!!.url.contains("1080"))
    }
}

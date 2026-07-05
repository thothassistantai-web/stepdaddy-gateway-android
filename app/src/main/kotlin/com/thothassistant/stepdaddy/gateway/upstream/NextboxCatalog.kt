package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Scrapes nextbox.uno homepage and featured shelves for TMDB movie/show rows.
 * Categories mirror on-site section titles (Popular Movies, Horror Movies, etc.).
 */
class NextboxCatalog(
    private val httpClient: OkHttpClient,
) {
    data class MovieRow(
        val tmdbId: Int,
        val title: String,
        val year: String?,
        val category: String,
    )

    data class ShowRow(
        val showTmdbId: Int,
        val title: String,
        val year: String?,
        val category: String,
    )

    fun fetchMovies(): List<MovieRow> {
        val merged = linkedMapOf<Int, MovieRow>()
        for (path in NextboxConfig.MOVIE_PAGES) {
            val html = fetchPage(path) ?: continue
            parseMovieSections(html, NextboxConfig.MOVIE_SECTIONS).forEach { row ->
                merged.putIfAbsent(row.tmdbId, row)
            }
        }
        return merged.values.toList()
    }

    fun fetchShows(): List<ShowRow> {
        val merged = linkedMapOf<Int, ShowRow>()
        for (path in NextboxConfig.TV_PAGES) {
            val html = fetchPage(path) ?: continue
            parseShowSections(html, NextboxConfig.TV_SECTIONS).forEach { row ->
                merged.putIfAbsent(row.showTmdbId, row)
            }
        }
        return merged.values.toList()
    }

    internal fun parseMovieSections(html: String, sectionTitles: List<String>): List<MovieRow> {
        val rows = mutableListOf<MovieRow>()
        for (title in sectionTitles) {
            val idx = html.indexOf(title)
            if (idx < 0) continue
            val chunk = html.substring(idx, minOf(html.length, idx + 40_000))
            rows += parseMovieCards(chunk, title)
        }
        return rows
    }

    internal fun parseShowSections(html: String, sectionTitles: List<String>): List<ShowRow> {
        val rows = mutableListOf<ShowRow>()
        for (title in sectionTitles) {
            val idx = html.indexOf(title)
            if (idx < 0) continue
            val chunk = html.substring(idx, minOf(html.length, idx + 40_000))
            rows += parseShowCards(chunk, title)
        }
        return rows
    }

    private fun parseMovieCards(chunk: String, category: String): List<MovieRow> {
        val rows = mutableListOf<MovieRow>()
        val cardPattern = Regex(
            """href="/movie/(\d+)/[^"]*"[^>]*>[\s\S]*?<h3[^>]*>([^<]+)</h3>[\s\S]*?<span>(\d{4})</span>""",
            RegexOption.IGNORE_CASE,
        )
        cardPattern.findAll(chunk).forEach { match ->
            val tmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (tmdbId <= 0) return@forEach
            val title = match.groupValues[2].trim().ifBlank { return@forEach }
            rows += MovieRow(
                tmdbId = tmdbId,
                title = title,
                year = match.groupValues[3],
                category = category,
            )
        }
        if (rows.isEmpty()) {
            LINK_MOVIE.findAll(chunk).forEach { match ->
                val tmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
                if (tmdbId <= 0) return@forEach
                rows += MovieRow(
                    tmdbId = tmdbId,
                    title = "",
                    year = null,
                    category = category,
                )
            }
        }
        return rows
    }

    private fun parseShowCards(chunk: String, category: String): List<ShowRow> {
        val rows = mutableListOf<ShowRow>()
        val cardPattern = Regex(
            """href="/tv/(\d+)/[^"]*"[^>]*>[\s\S]*?<h3[^>]*>([^<]+)</h3>[\s\S]*?<span>(\d{4})</span>""",
            RegexOption.IGNORE_CASE,
        )
        cardPattern.findAll(chunk).forEach { match ->
            val showTmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (showTmdbId <= 0) return@forEach
            val title = match.groupValues[2].trim().ifBlank { return@forEach }
            rows += ShowRow(
                showTmdbId = showTmdbId,
                title = title,
                year = match.groupValues[3],
                category = category,
            )
        }
        if (rows.isEmpty()) {
            LINK_TV.findAll(chunk).forEach { match ->
                val showTmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
                if (showTmdbId <= 0) return@forEach
                rows += ShowRow(
                    showTmdbId = showTmdbId,
                    title = "",
                    year = null,
                    category = category,
                )
            }
        }
        return rows
    }

    private fun fetchPage(path: String): String? {
        val url = NextboxConfig.BASE_URL.trimEnd('/') + path
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SupplementConfig.USER_AGENT)
                .header("Referer", NextboxConfig.REFERER)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }.getOrElse { exc ->
            Log.w(TAG, "nextbox fetch failed: $url", exc)
            null
        }
    }

    companion object {
        private const val TAG = "NextboxCatalog"
        private val LINK_MOVIE = Regex("""href="/movie/(\d+)/""")
        private val LINK_TV = Regex("""href="/tv/(\d+)/""")
    }
}

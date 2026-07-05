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
        val categories: List<String>,
    ) {
        val category: String get() = categories.firstOrNull().orEmpty()
    }

    data class ShowRow(
        val showTmdbId: Int,
        val title: String,
        val year: String?,
        val categories: List<String>,
    ) {
        val category: String get() = categories.firstOrNull().orEmpty()
    }

    fun fetchMovies(): List<MovieRow> {
        val categoriesById = linkedMapOf<Int, LinkedHashSet<String>>()
        val titleById = linkedMapOf<Int, String>()
        val yearById = linkedMapOf<Int, String?>()
        for (path in NextboxConfig.MOVIE_PAGES) {
            val html = fetchPage(path) ?: continue
            parseMovieSections(html, NextboxConfig.MOVIE_SECTIONS).forEach { row ->
                categoriesById.getOrPut(row.tmdbId) { linkedSetOf() }.add(row.category)
                if (row.title.isNotBlank()) {
                    titleById[row.tmdbId] = row.title
                }
                if (row.year != null) {
                    yearById[row.tmdbId] = row.year
                }
            }
        }
        return categoriesById.map { (tmdbId, categories) ->
            MovieRow(
                tmdbId = tmdbId,
                title = titleById[tmdbId].orEmpty(),
                year = yearById[tmdbId],
                categories = categories.toList(),
            )
        }
    }

    fun fetchShows(): List<ShowRow> {
        val categoriesById = linkedMapOf<Int, LinkedHashSet<String>>()
        val titleById = linkedMapOf<Int, String>()
        val yearById = linkedMapOf<Int, String?>()
        for (path in NextboxConfig.TV_PAGES) {
            val html = fetchPage(path) ?: continue
            parseShowSections(html, NextboxConfig.TV_SECTIONS).forEach { row ->
                categoriesById.getOrPut(row.showTmdbId) { linkedSetOf() }.add(row.category)
                if (row.title.isNotBlank()) {
                    titleById[row.showTmdbId] = row.title
                }
                if (row.year != null) {
                    yearById[row.showTmdbId] = row.year
                }
            }
        }
        return categoriesById.map { (showTmdbId, categories) ->
            ShowRow(
                showTmdbId = showTmdbId,
                title = titleById[showTmdbId].orEmpty(),
                year = yearById[showTmdbId],
                categories = categories.toList(),
            )
        }
    }

    internal data class ParsedMovieRow(
        val tmdbId: Int,
        val title: String,
        val year: String?,
        val category: String,
    )

    internal data class ParsedShowRow(
        val showTmdbId: Int,
        val title: String,
        val year: String?,
        val category: String,
    )

    internal fun parseMovieSections(html: String, sectionTitles: List<String>): List<ParsedMovieRow> {
        val rows = mutableListOf<ParsedMovieRow>()
        for ((index, title) in sectionTitles.withIndex()) {
            val idx = html.indexOf(title)
            if (idx < 0) continue
            val chunkEnd = sectionTitles.drop(index + 1)
                .map { next -> html.indexOf(next, idx + title.length) }
                .filter { it >= 0 }
                .minOrNull()
                ?: minOf(html.length, idx + 40_000)
            val chunk = html.substring(idx, chunkEnd)
            rows += parseMovieCards(chunk, title)
        }
        return rows
    }

    internal fun parseShowSections(html: String, sectionTitles: List<String>): List<ParsedShowRow> {
        val rows = mutableListOf<ParsedShowRow>()
        for ((index, title) in sectionTitles.withIndex()) {
            val idx = html.indexOf(title)
            if (idx < 0) continue
            val chunkEnd = sectionTitles.drop(index + 1)
                .map { next -> html.indexOf(next, idx + title.length) }
                .filter { it >= 0 }
                .minOrNull()
                ?: minOf(html.length, idx + 40_000)
            val chunk = html.substring(idx, chunkEnd)
            rows += parseShowCards(chunk, title)
        }
        return rows
    }

    private fun parseMovieCards(chunk: String, category: String): List<ParsedMovieRow> {
        val rows = mutableListOf<ParsedMovieRow>()
        val cardPattern = Regex(
            """href="/movie/(\d+)/[^"]*"[^>]*>[\s\S]*?<h3[^>]*>([^<]+)</h3>[\s\S]*?<span>(\d{4})</span>""",
            RegexOption.IGNORE_CASE,
        )
        cardPattern.findAll(chunk).forEach { match ->
            val tmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (tmdbId <= 0) return@forEach
            val title = match.groupValues[2].trim().ifBlank { return@forEach }
            rows += ParsedMovieRow(
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
                rows += ParsedMovieRow(
                    tmdbId = tmdbId,
                    title = "",
                    year = null,
                    category = category,
                )
            }
        }
        return rows
    }

    private fun parseShowCards(chunk: String, category: String): List<ParsedShowRow> {
        val rows = mutableListOf<ParsedShowRow>()
        val cardPattern = Regex(
            """href="/tv/(\d+)/[^"]*"[^>]*>[\s\S]*?<h3[^>]*>([^<]+)</h3>[\s\S]*?<span>(\d{4})</span>""",
            RegexOption.IGNORE_CASE,
        )
        cardPattern.findAll(chunk).forEach { match ->
            val showTmdbId = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (showTmdbId <= 0) return@forEach
            val title = match.groupValues[2].trim().ifBlank { return@forEach }
            rows += ParsedShowRow(
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
                rows += ParsedShowRow(
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

package com.thothassistant.stepdaddy.gateway.upstream

/** Sort VOD movies/series by release year descending (newest first). */
object VodSort {
    private val TITLE_YEAR = Regex("""\((\d{4})\)$""")

    fun yearFromReleaseDate(releaseDate: String?): Int? =
        releaseDate?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toIntOrNull()

    fun yearFromDisplayName(name: String): Int? =
        TITLE_YEAR.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun movieSortKey(releaseDate: String?, displayName: String = ""): Int =
        yearFromReleaseDate(releaseDate) ?: yearFromDisplayName(displayName) ?: 0

    fun compareMoviesByYearDesc(
        releaseDate: String?,
        displayName: String,
        otherReleaseDate: String?,
        otherDisplayName: String,
    ): Int = movieSortKey(releaseDate, displayName).compareTo(
        movieSortKey(otherReleaseDate, otherDisplayName),
    )

    fun compareChannelNamesByYearDesc(a: String, b: String): Int =
        (yearFromDisplayName(a) ?: 0).compareTo(yearFromDisplayName(b) ?: 0)
}

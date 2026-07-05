package com.thothassistant.stepdaddy.gateway.upstream

/** nextbox.uno catalog endpoints and scrape settings. */
object NextboxConfig {
    const val BASE_URL = "https://nextbox.uno"
    const val PROVIDER_TAG = "Nextbox"
    const val REFERER = "$BASE_URL/"

    val MOVIE_PAGES = listOf(
        "/",
        "/movie/featured",
    )

    val TV_PAGES = listOf(
        "/",
        "/tv/featured",
    )

    /** Site section titles mapped to VOD group prefixes. */
    val MOVIE_SECTIONS = listOf(
        "Popular Movies",
        "Horror Movies",
        "Trending Movies",
        "Movies Airing Lately",
        "Top Rated Movies",
    )

    val TV_SECTIONS = listOf(
        "Trending TV Series",
        "TV Series Airing Today",
        "Top Rated TV Series",
    )
}

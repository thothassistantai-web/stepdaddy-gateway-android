package com.thothassistant.stepdaddy.gateway.upstream

/** Moviebox.ph API constants (ported from moviebox-js-sdk). */
object MovieboxConfig {
    val MIRROR_HOSTS = listOf(
        "h5.aoneroom.com",
        "movieboxapp.in",
        "moviebox.pk",
        "moviebox.ph",
        "moviebox.id",
        "v.moviebox.ph",
        "netnaija.video",
    )

    const val APP_INFO_PATH = "/wefeed-h5-bff/app/get-latest-app-pkgs"
    const val SEARCH_PATH = "/wefeed-h5-bff/web/subject/search"
    const val STREAM_PATH = "/wefeed-h5-bff/web/subject/play"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}

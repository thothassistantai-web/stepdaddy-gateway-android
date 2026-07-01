package com.thothassistant.stepdaddy.gateway.upstream

object VsembedConfig {
    /** Primary embed + list JSON hosts (vidsrc successor domains). */
    val EMBED_MIRRORS = listOf(
        "https://vsembed.ru",
        "https://vsembed.su",
        "https://vidsrc-embed.ru",
        "https://vidsrc-embed.su",
    )

    const val LIST_MOVIES_PATH = "/movies/latest/page-%d.json"
    const val LIST_TVSHOWS_PATH = "/tvshows/latest/page-%d.json"
    const val LIST_EPISODES_PATH = "/episodes/latest/page-%d.json"

    /** Pages of vsembed latest JSON merged per catalog sync (50 titles/page). */
    const val CATALOG_LIST_PAGES = 3

    /** Episode list pages (latest aired episodes). */
    const val SERIES_CATALOG_LIST_PAGES = 3

    fun embedBase(): String = EMBED_MIRRORS.first()

    fun embedReferer(base: String = embedBase()): String =
        base.trimEnd('/') + "/"
}

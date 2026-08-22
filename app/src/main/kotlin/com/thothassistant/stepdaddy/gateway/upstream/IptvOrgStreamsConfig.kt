package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Curated iptv-org/iptv stream playlists (maintained upstream on GitHub).
 * @see <a href="https://github.com/iptv-org/iptv/tree/master/streams">iptv-org/iptv streams</a>
 */
object IptvOrgStreamsConfig {
    const val RAW_BASE_URL =
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/"

    /**
     * Fallback CDNs when GitHub raw times out on phone networks.
     * Prefer jsDelivr variants — same bytes, often reachable when raw.githubusercontent.com stalls.
     */
    val CDN_BASE_URLS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/",
        "https://fastly.jsdelivr.net/gh/iptv-org/iptv@master/streams/",
        "https://gcore.jsdelivr.net/gh/iptv-org/iptv@master/streams/",
        "https://testingcf.jsdelivr.net/gh/iptv-org/iptv@master/streams/",
    )

    /** Inclusive range: uk.m3u … us_xumo.m3u (39 playlists). */
    val PLAYLIST_FILES: List<String> = listOf(
        "uk.m3u",
        "uk_bbc.m3u",
        "uk_distro.m3u",
        "uk_pluto.m3u",
        "uk_rakuten.m3u",
        "uk_samsung.m3u",
        "uk_sportstribal.m3u",
        "us.m3u",
        "us_30a.m3u",
        "us_3abn.m3u",
        "us_abcnews.m3u",
        "us_afrolandtv.m3u",
        "us_amagi.m3u",
        "us_canelatv.m3u",
        "us_cbsn.m3u",
        "us_cineversetv.m3u",
        "us_distro.m3u",
        "us_firetv.m3u",
        "us_frequency.m3u",
        "us_glewedtv.m3u",
        "us_klowdtv.m3u",
        "us_local.m3u",
        "us_malimartv.m3u",
        "us_pbs.m3u",
        "us_plex.m3u",
        "us_pluto.m3u",
        "us_roku.m3u",
        "us_samsung.m3u",
        "us_sofast.m3u",
        "us_ssh101.m3u",
        "us_stirr.m3u",
        "us_tcl.m3u",
        "us_tubi.m3u",
        "us_uplynk.m3u",
        "us_vegasplus.m3u",
        "us_vizio.m3u",
        "us_wfmz.m3u",
        "us_wowza.m3u",
        "us_xumo.m3u",
    )

    /** ~3421 raw entries across 39 playlists; dedup vs DaddyLive trims overlap. */
    const val MAX_CHANNELS_AFTER_DEDUP = 3000

    const val MAX_BYTES_PER_PLAYLIST = 2 * 1024 * 1024

    /** Phone / non-Fire concurrency for GitHub raw fetches (1 avoids LTE HTTPS stampede). */
    const val MAX_CONCURRENT_FETCH = 1

    /** Fire Stick / low-RAM concurrency. */
    const val MAX_CONCURRENT_FETCH_FIRE = 1

    /**
     * Network budget for one iptv-org sync slot; on expiry [IptvOrgStreamsSource.fetchChannelsFromDiskCache]
     * rebuilds the catalog from files/supplement/iptv-org without GitHub/CDN.
     */
    const val FETCH_BUDGET_MS = 300_000L

    /**
     * Prefer these first when cache sizes are unknown so progressive publish
     * surfaces small catalogs before huge Pluto/local lists.
     */
    val SMALL_FIRST_HINTS: List<String> = listOf(
        "uk_bbc.m3u",
        "uk_sportstribal.m3u",
        "us_cbsn.m3u",
        "us_abcnews.m3u",
        "us_3abn.m3u",
        "us_pbs.m3u",
        "us_wfmz.m3u",
        "uk_rakuten.m3u",
        "uk_distro.m3u",
    )

  /** Legacy sidebar label; iptv-org channels now use flat [GroupTitleResolver] groups in the playlist. */
    const val GROUP_PREFIX = "🌐 | iptv-org"

    private val PROVIDER_TAG_OVERRIDES = mapOf(
        "firetv" to "FireTV",
        "cbsn" to "CBSN",
        "abcnews" to "ABC News",
        "3abn" to "3ABN",
        "pbs" to "PBS",
        "tcl" to "TCL",
        "plex" to "Plex",
        "roku" to "Roku",
        "xumo" to "Xumo",
        "pluto" to "Pluto",
        "samsung" to "Samsung",
        "tubi" to "Tubi",
        "local" to "LocalNow",
        "stirr" to "STIRR",
        "vizio" to "Vizio",
        "bbc" to "BBC",
        "distro" to "Distro",
        "rakuten" to "Rakuten",
        "sportstribal" to "SportsTribal",
    )

    fun rawUrl(filename: String): String = RAW_BASE_URL + filename

    fun candidateUrls(filename: String): List<String> =
        // Prefer jsDelivr first on phone networks — raw.githubusercontent.com often stalls.
        (CDN_BASE_URLS + listOf(RAW_BASE_URL)).map { it + filename }

    /** Provider suffix for TiviMate display title, e.g. us_pluto.m3u → Pluto. */
    fun providerTagFor(filename: String): String {
        val slug = filename.removeSuffix(".m3u")
        val parts = slug.split('_', limit = 2)
        if (parts.size == 1) return ""
        val raw = parts[1]
        return PROVIDER_TAG_OVERRIDES[raw.lowercase()] ?: formatProviderSlug(raw)
    }

    /** Human-readable label for settings playlist toggles. */
    fun playlistDisplayName(filename: String): String {
        val slug = filename.removeSuffix(".m3u")
        return slug.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
    }

    fun groupTitleFor(filename: String): String {
        val slug = filename.removeSuffix(".m3u")
        val label = slug
            .split('_')
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                }
            }
        return "$GROUP_PREFIX | $label"
    }

    private fun formatProviderSlug(raw: String): String =
        raw.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
}

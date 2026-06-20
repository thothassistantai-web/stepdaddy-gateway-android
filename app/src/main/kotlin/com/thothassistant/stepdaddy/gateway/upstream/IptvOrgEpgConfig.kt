package com.thothassistant.stepdaddy.gateway.upstream

/**
 * iptv-org/epg site guides for FAST providers in our supplement playlists.
 * Guides use iptv-org [xmltv_id] channel ids (e.g. ABCNewsLive.us@SD) matching M3U tvg-id.
 *
 * Pre-built guides are generated off-device via [scripts/grab-iptv-org-fast-epg.sh]
 * and downloaded on supplement sync (or bundled under assets/epg/).
 */
object IptvOrgEpgConfig {
    /** Sites with matching iptv-org/epg scrapers for our UK/US FAST playlists. */
    val EPG_SITES: List<String> = listOf(
        "pluto.tv",
        "plex.tv",
        "xumo.tv",
        "distro.tv",
    )

    /**
     * Maps supplement playlist file → iptv-org/epg site (when not inferrable from name).
     */
    val PLAYLIST_TO_SITE: Map<String, String> = mapOf(
        "us_pluto.m3u" to "pluto.tv",
        "uk_pluto.m3u" to "pluto.tv",
        "us_plex.m3u" to "plex.tv",
        "us_xumo.m3u" to "xumo.tv",
        "us_distro.m3u" to "distro.tv",
        "uk_distro.m3u" to "distro.tv",
    )

    /**
     * Remote pre-built guide URLs (gzip). Override via [GatewayEnvironment.iptvOrgEpgGuideUrls].
     * Empty = skip remote fetch (use bundled asset only).
     *
     * Host your own after running grab-iptv-org-fast-epg.sh, or point at a LAN mirror.
     */
    val DEFAULT_GUIDE_URLS: Map<String, String> = emptyMap()

    /** Bundled asset path (see scripts/grab-iptv-org-fast-epg.sh). */
    const val BUNDLED_MERGED_ASSET = IptvOrgEpgRepository.BUNDLED_ASSET_DAT

    /** Max bytes per guide download (merged FAST guide is typically 2–8 MB gzip). */
    const val MAX_GUIDE_BYTES = 24 * 1024 * 1024

    const val GUIDE_CACHE_TTL_MS = 24 * 3600_000L

    const val DOWNLOAD_TIMEOUT_MS = 120_000L

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) StepDaddy-Gateway/1.0"

    fun siteForPlaylist(playlistFile: String): String? {
        PLAYLIST_TO_SITE[playlistFile]?.let { return it }
        val slug = playlistFile.removeSuffix(".m3u")
        val parts = slug.split('_', limit = 2)
        if (parts.size < 2) return null
        val provider = parts[1]
        return when (provider) {
            "pluto" -> "pluto.tv"
            "plex" -> "plex.tv"
            "xumo" -> "xumo.tv"
            "distro" -> "distro.tv"
            else -> null
        }
    }
}

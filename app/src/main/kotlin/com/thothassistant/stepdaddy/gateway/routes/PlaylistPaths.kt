package com.thothassistant.stepdaddy.gateway.routes

/**
 * Canonical user playlist URLs and legacy diagnostic aliases.
 *
 * **User** — paste into IPTV apps: [streamvault.m3u], [tivimate.m3u], [vlc.m3u] (+ `.m3u8` aliases).
 * **Diagnostic** — bootstrap / legacy paths kept for FUSA probes and existing bookmarks.
 */
object PlaylistPaths {
    const val HEADER_KIND = "X-Playlist-Kind"
    const val KIND_USER = "user"
    const val KIND_DIAGNOSTIC = "diagnostic"

    /** Full StreamVault catalog (plain gateway proxy URLs). */
    const val STREAMVAULT = "/streamvault.m3u"
    const val STREAMVAULT_M3U8 = "/streamvault.m3u8"

    /** Full TiviMate catalog (pipe-suffixed stream lines). */
    const val TIVIMATE = "/tivimate.m3u"
    const val TIVIMATE_M3U8 = "/tivimate.m3u8"

    /** Full catalog for VLC and other generic players (plain proxy URLs). */
    const val VLC = "/vlc.m3u"
    const val VLC_M3U8 = "/vlc.m3u8"

    /** 50-channel bootstrap for TiviMate FUSA / wizard diagnostics. */
    const val TIVIMATE_SETUP = "/tivimate-setup-playlist.m3u8"

    /** Legacy StreamVault path — same full catalog as [STREAMVAULT]; diagnostic label only. */
    const val STREAMVAULT_SETUP = "/streamvault-setup-playlist.m3u8"

    /** Legacy alias for [TIVIMATE]. */
    const val TIVIMATE_LEGACY = "/tivimate-playlist.m3u8"

    /** Legacy alias for [STREAMVAULT]. */
    const val STREAMVAULT_LEGACY = "/streamvault-playlist.m3u8"

    val USER = listOf(
        STREAMVAULT,
        STREAMVAULT_M3U8,
        TIVIMATE,
        TIVIMATE_M3U8,
        VLC,
        VLC_M3U8,
        TIVIMATE_LEGACY,
        STREAMVAULT_LEGACY,
    )

    val DIAGNOSTIC = listOf(
        TIVIMATE_SETUP,
        STREAMVAULT_SETUP,
    )
}

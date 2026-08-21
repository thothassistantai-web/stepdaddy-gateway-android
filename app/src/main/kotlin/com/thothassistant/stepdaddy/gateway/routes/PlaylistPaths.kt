package com.thothassistant.stepdaddy.gateway.routes

/**
 * Canonical user playlist URLs and legacy diagnostic aliases.
 *
 * **User** — paste into IPTV apps: [streamvault.m3u], [tivimate.m3u], [tivimate-smart.m3u], [vlc.m3u]
 * (+ `.m3u8` / bare-path aliases).
 * **Diagnostic** — bootstrap / legacy paths kept for FUSA probes and existing bookmarks.
 */
object PlaylistPaths {
    const val HEADER_KIND = "X-Playlist-Kind"
    /** Playlist body revision — forces clients/caches to treat catalog as fresh after fixes. */
    const val HEADER_REV = "X-Playlist-Rev"
    const val KIND_USER = "user"
    const val KIND_DIAGNOSTIC = "diagnostic"

    /** Full StreamVault catalog (plain gateway proxy URLs). */
    const val STREAMVAULT = "/streamvault.m3u"
    const val STREAMVAULT_M3U8 = "/streamvault.m3u8"

    /**
     * Full TiviMate catalog — **fast** path: stream URLs hit direct DaddyLive resolve
     * (`/tivimate-stream/{id}.m3u8`), no multi-variant master for consolidate backups.
     */
    const val TIVIMATE = "/tivimate.m3u"
    const val TIVIMATE_M3U8 = "/tivimate.m3u8"
    /** Bare alias for [TIVIMATE]. */
    const val TIVIMATE_BARE = "/tivimate"

    /**
     * TiviMate Smart — same catalog/groups, but DaddyLive channels with consolidate backups
     * point at multi-variant masters (`/tivimate-smart-stream/{id}.m3u8`).
     */
    const val TIVIMATE_SMART = "/tivimate-smart.m3u"
    const val TIVIMATE_SMART_M3U8 = "/tivimate-smart.m3u8"
    /** Bare alias for [TIVIMATE_SMART]. */
    const val TIVIMATE_SMART_BARE = "/tivimate-smart"

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
        TIVIMATE_BARE,
        TIVIMATE_SMART,
        TIVIMATE_SMART_M3U8,
        TIVIMATE_SMART_BARE,
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

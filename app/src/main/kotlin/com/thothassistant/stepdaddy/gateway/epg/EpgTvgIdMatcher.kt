package com.thothassistant.stepdaddy.gateway.epg

/**
 * Expands iptv-org playlist [tvg-id] values for XMLTV lookup.
 * Playlist may use `ABCNewsLive.us@SD` while guides use `@US`, base id only, etc.
 */
object EpgTvgIdMatcher {
    private val QUALITY_SUFFIXES = listOf("@SD", "@HD", "@UHD", "@4K", "@FHD")
    private val REGION_SUFFIXES = listOf(
        "@US", "@UK", "@East", "@West", "@Central", "@Mountain", "@Pacific",
        "@Germany", "@France", "@Panregional", "@EastHD", "@HDEast", "@WestHD",
    )

    fun expandWantedIds(playlistTvgIds: Set<String>): IdExpansion {
        val lookupIds = linkedSetOf<String>()
        val remapToPlaylist = linkedMapOf<String, String>()
        playlistTvgIds.forEach { playlistId ->
            val trimmed = playlistId.trim()
            if (trimmed.isEmpty()) return@forEach
            lookupIds += trimmed
            remapToPlaylist.putIfAbsent(trimmed, trimmed)
            val base = trimmed.substringBefore('@')
            if (base.isNotEmpty() && base != trimmed) {
                lookupIds += base
                remapToPlaylist.putIfAbsent(base, trimmed)
            }
            for (suffix in QUALITY_SUFFIXES + REGION_SUFFIXES) {
                val variant = "$base$suffix"
                lookupIds += variant
                remapToPlaylist.putIfAbsent(variant, trimmed)
            }
            if (trimmed.endsWith("@US", ignoreCase = true)) {
                val sd = "${base}@SD"
                lookupIds += sd
                remapToPlaylist.putIfAbsent(sd, trimmed)
            }
            if (trimmed.endsWith("@SD", ignoreCase = true)) {
                val us = "${base}@US"
                lookupIds += us
                remapToPlaylist.putIfAbsent(us, trimmed)
            }
        }
        return IdExpansion(lookupIds = lookupIds, remapToPlaylist = remapToPlaylist)
    }

    fun canonicalPlaylistId(expansion: IdExpansion, feedChannelId: String): String? {
        val trimmed = feedChannelId.trim()
        return expansion.remapToPlaylist[trimmed]
            ?: expansion.remapToPlaylist[trimmed.substringBefore('@')]
    }

    data class IdExpansion(
        val lookupIds: Set<String>,
        val remapToPlaylist: Map<String, String>,
    )
}

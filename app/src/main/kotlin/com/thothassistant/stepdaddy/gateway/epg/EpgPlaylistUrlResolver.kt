package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import java.io.File

/**
 * Resolves the XMLTV URL(s) embedded in the TiviMate playlist header.
 *
 * When [GatewayEnvironment.gatewayEpgEnabled] is true, TiviMate fetches merged EPG from the
 * gateway loopback endpoint. When disabled, [GatewayEnvironment.externalEpgUrls] are passed
 * through (comma-separated in `url-tvg`) so TiviMate downloads and merges feeds directly.
 */
object EpgPlaylistUrlResolver {
    fun resolveUrls(environment: GatewayEnvironment): List<String> {
        if (!environment.gatewayEpgEnabled) {
            return environment.externalEpgUrls()
        }
        return listOf("${environment.loopbackBase().trimEnd('/')}/epg.xml")
    }

    /**
     * Playlist header URLs including loopback [sports-epg.xml] when gateway EPG is off but
     * Special Events supplement is enabled and a sports guide file exists.
     */
    fun resolvePlaylistEpgUrls(
        environment: GatewayEnvironment,
        sportsEpgFile: File? = null,
    ): List<String> {
        val urls = resolveUrls(environment).toMutableList()
        if (!environment.gatewayEpgEnabled &&
            environment.supplementSportsEnabled &&
            sportsEpgFile != null &&
            sportsEpgFile.isFile &&
            sportsEpgFile.length() > 0L
        ) {
            urls += "${environment.loopbackBase().trimEnd('/')}/sports-epg.xml"
        }
        return urls
    }

    fun resolve(
        environment: GatewayEnvironment,
        sportsEpgFile: File? = null,
    ): String? =
        resolvePlaylistEpgUrls(environment, sportsEpgFile)
            .takeIf { it.isNotEmpty() }
            ?.let(PlaylistEpgHeader::joinUrls)

    fun headerLine(
        environment: GatewayEnvironment,
        sportsEpgFile: File? = null,
    ): String = PlaylistEpgHeader.line(resolvePlaylistEpgUrls(environment, sportsEpgFile))

    fun playlistCacheKey(environment: GatewayEnvironment): String =
        if (environment.gatewayEpgEnabled) {
            "gateway"
        } else {
            buildString {
                append(environment.externalEpgUrls().joinToString("|"))
                if (environment.supplementSportsEnabled) append("|sports")
            }
        }
}

/** Builds the `#EXTM3U` header with optional `url-tvg` / `x-tvg-url` attributes. */
object PlaylistEpgHeader {
    /** Bumped with releases so TiviMate re-fetches treat the catalog as changed. */
    const val PLAYLIST_REV = "3.0.34"

    fun line(epgUrls: List<String>): String {
        val urls = epgUrls.map { it.trim() }.filter { it.isNotEmpty() }
        val rev = "stepdaddy-rev=\"$PLAYLIST_REV\""
        if (urls.isEmpty()) return "#EXTM3U $rev\n"
        val joined = joinUrls(urls)
        val escaped = joined.replace("\\", "\\\\").replace("\"", "\\\"")
        return "#EXTM3U url-tvg=\"$escaped\" x-tvg-url=\"$escaped\" $rev\n"
    }

    fun line(epgUrl: String?): String {
        val trimmed = epgUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return line(emptyList())
        return line(EpgConfig.parseExternalEpgUrls(trimmed))
    }

    fun joinUrls(urls: List<String>): String =
        urls.joinToString(",") { it.trim() }
}

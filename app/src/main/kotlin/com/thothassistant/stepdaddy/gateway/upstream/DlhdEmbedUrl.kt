package com.thothassistant.stepdaddy.gateway.upstream

import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses DaddyLive `/api/channels` embed URLs and builds relay page candidates.
 *
 * As of 2026-08 the channel API returns `{ channel_name, url }` where `url` points at
 * `/player/embed.php?id=…` instead of legacy `{ channel_id, channel_name }`.
 */
object DlhdEmbedUrl {
    private val EMBED_PATH = "/player/embed.php"

    /** Playlist / cache id extracted from an embed URL or legacy channel_id. */
    fun channelIdFromRow(channelId: String, embedUrl: String): String {
        channelId.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return channelIdFromEmbedUrl(embedUrl).orEmpty()
    }

    fun channelIdFromEmbedUrl(embedUrl: String): String? {
        val id = idParam(embedUrl)?.trim().orEmpty()
        return id.takeIf { it.isNotEmpty() }
    }

    /**
     * Slug used in relay paths like `stream-{slug}.php`.
     * `id=stream-144` maps to `stream-144.php`, not `stream-stream-144.php`.
     */
    fun streamSlugFromChannelId(channelId: String): String {
        val trimmed = channelId.trim()
        return trimmed.removePrefix("stream-").removePrefix("stream-")
    }

    fun embedUrlForMirror(mirrorBase: String, channelId: String): String {
        val base = mirrorBase.trimEnd('/')
        val id = channelId.trim()
        return "$base$EMBED_PATH?id=$id"
    }

    fun mirrorEmbedUrl(embedUrl: String, mirrorBase: String): String {
        val id = idParam(embedUrl)?.trim().orEmpty()
        if (id.isEmpty()) {
            return embedUrl
        }
        return embedUrlForMirror(mirrorBase, id)
    }

    fun isEmbedPageUrl(url: String): Boolean {
        val path = runCatching { URL(url).path.lowercase() }.getOrNull() ?: return false
        return path.endsWith(EMBED_PATH.lowercase()) || path.contains("/embed.php")
    }

    fun relayStreamPaths(): List<String> = GatewayConfig.DLHD_PK_STREAM_PATHS

    fun buildRelayWatchUrls(channelId: String, relayHosts: Collection<String>): List<String> {
        val slug = streamSlugFromChannelId(channelId)
        if (slug.isEmpty()) return emptyList()
        val orderedPaths = relayStreamPaths()
        val ordered = linkedSetOf<String>()
        for (host in relayHosts) {
            val base = host.trimEnd('/')
            for (path in orderedPaths) {
                ordered += "$base/$path/stream-$slug.php"
            }
        }
        return ordered.toList()
    }

    private fun idParam(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val parsed = URL(trimmed)
            parsed.query?.split('&')?.firstOrNull { it.startsWith("id=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        }.getOrNull()
    }
}

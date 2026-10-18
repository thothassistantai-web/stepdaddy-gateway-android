package com.thothassistant.stepdaddy.gateway.upstream

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Mirrors the public embed player's short-lived playlist access handshake. */
object CdnPlaylistAccess {
    fun tokenEndpoint(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || !parsed.host.startsWith("edge.")) return null
        val slug = Regex("^/(premium[0-9]+)/.*\\.m3u8$").find(parsed.encodedPath)?.groupValues?.get(1) ?: return null
        return parsed.newBuilder().encodedPath("/api/playlist-token").query(null).addQueryParameter("slug", slug).build()
    }
    fun origin(referer: String): String? = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
}

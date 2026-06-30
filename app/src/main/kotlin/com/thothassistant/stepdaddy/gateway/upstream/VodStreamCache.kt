package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.ConcurrentHashMap

/** Short-lived cache for resolved VOD stream URLs (vidsrc tokens expire). */
class VodStreamCache {
    private data class Entry(
        val stream: VidsrcMovieResolver.ResolvedStream,
        val cachedAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(tmdbId: String): VidsrcMovieResolver.ResolvedStream? {
        val entry = cache[tmdbId] ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs > TTL_MS) {
            cache.remove(tmdbId)
            return null
        }
        return entry.stream
    }

    fun put(tmdbId: String, stream: VidsrcMovieResolver.ResolvedStream) {
        cache[tmdbId] = Entry(stream, System.currentTimeMillis())
    }

    companion object {
        private const val TTL_MS = 45 * 60 * 1000L
    }
}

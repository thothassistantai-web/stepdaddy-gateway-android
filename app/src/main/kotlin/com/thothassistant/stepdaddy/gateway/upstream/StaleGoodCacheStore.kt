package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest
import org.json.JSONObject

/**
 * Persists recently-good rewritten playlists and upstream manifests so outage mode
 * can still serve playable HLS after process restarts or memory cache eviction.
 */
class StaleGoodCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadStream(cacheKey: String, maxAgeMs: Long): DiskStreamEntry? {
        val raw = prefs.getString(streamKey(cacheKey), null) ?: return null
        return runCatching {
            val row = JSONObject(raw)
            val savedAtMs = row.getLong("saved_at")
            if (System.currentTimeMillis() - savedAtMs > maxAgeMs) {
                return null
            }
            DiskStreamEntry(
                savedAtMs = savedAtMs,
                playlist = row.getString("playlist"),
            )
        }.getOrNull()
    }

    fun saveStream(cacheKey: String, channelId: String, playlist: String) {
        val payload = JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("channel_id", channelId)
            .put("playlist", playlist)
        prefs.edit()
            .putString(streamKey(cacheKey), payload.toString())
            .putLong(lastSuccessKey(channelId), System.currentTimeMillis())
            .apply()
        trimToMaxEntries()
    }

    fun loadUpstream(channelId: String, maxAgeMs: Long): DiskUpstreamEntry? {
        val raw = prefs.getString(upstreamKey(channelId), null) ?: return null
        return runCatching {
            val row = JSONObject(raw)
            val savedAtMs = row.getLong("saved_at")
            if (System.currentTimeMillis() - savedAtMs > maxAgeMs) {
                return null
            }
            DiskUpstreamEntry(
                savedAtMs = savedAtMs,
                manifest = UpstreamManifest(
                    playlistText = row.getString("playlist_text"),
                    masterUrl = row.getString("master_url"),
                    refererHost = row.getString("referer_host"),
                ),
            )
        }.getOrNull()
    }

    fun saveUpstream(channelId: String, manifest: UpstreamManifest) {
        val payload = JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("playlist_text", manifest.playlistText)
            .put("master_url", manifest.masterUrl)
            .put("referer_host", manifest.refererHost)
        prefs.edit()
            .putString(upstreamKey(channelId), payload.toString())
            .putLong(lastSuccessKey(channelId), System.currentTimeMillis())
            .apply()
        trimToMaxEntries()
    }

    fun entryCount(): Int {
        return prefs.all.keys.count { it.startsWith("stream:") || it.startsWith("upstream:") }
    }

    fun lastSuccessMs(channelId: String): Long? {
        val ts = prefs.getLong(lastSuccessKey(channelId), 0L)
        return ts.takeIf { it > 0L }
    }

    fun purgeExpired(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        var removed = 0
        for ((key, value) in prefs.all) {
            if (!key.startsWith("stream:") && !key.startsWith("upstream:")) continue
            val savedAt = runCatching { JSONObject(value as String).getLong("saved_at") }.getOrNull()
                ?: continue
            if (now - savedAt > maxAgeMs) {
                editor.remove(key)
                removed++
            }
        }
        editor.apply()
        if (removed > 0) {
            Log.d(TAG, "Purged $removed expired disk stale entries")
        }
    }

    private fun trimToMaxEntries() {
        val streamKeys = prefs.all.keys.filter { it.startsWith("stream:") }
        val upstreamKeys = prefs.all.keys.filter { it.startsWith("upstream:") }
        val total = streamKeys.size + upstreamKeys.size
        if (total <= GatewayConfig.STALE_DISK_MAX_ENTRIES) return

        val ranked = (streamKeys + upstreamKeys).mapNotNull { key ->
            val raw = prefs.getString(key, null) ?: return@mapNotNull null
            val savedAt = runCatching { JSONObject(raw).getLong("saved_at") }.getOrNull()
            savedAt?.let { key to it }
        }.sortedBy { it.second }

        val editor = prefs.edit()
        val toRemove = total - GatewayConfig.STALE_DISK_MAX_ENTRIES
        ranked.take(toRemove).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
        Log.d(TAG, "Trimmed $toRemove oldest disk stale entries (cap=${GatewayConfig.STALE_DISK_MAX_ENTRIES})")
    }

    private fun streamKey(cacheKey: String): String = "stream:$cacheKey"

    private fun upstreamKey(channelId: String): String = "upstream:$channelId"

    private fun lastSuccessKey(channelId: String): String = "last_ok:$channelId"

    data class DiskStreamEntry(
        val savedAtMs: Long,
        val playlist: String,
    )

    data class DiskUpstreamEntry(
        val savedAtMs: Long,
        val manifest: UpstreamManifest,
    )

    companion object {
        private const val TAG = "StaleGoodCache"
        private const val PREFS_NAME = "stepdaddy_stale_good"
    }
}

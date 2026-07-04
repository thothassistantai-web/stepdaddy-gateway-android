package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.FireMemoryGuard
import com.thothassistant.stepdaddy.gateway.upstream.TvgIdNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Name → iptv-org [tvg-id] index from bundled channels_db_cache.csv.
 * Shared by auto-resolve (Phase 1a) and supplement import (Phase 1b).
 *
 * CSV parse runs on a background thread — synchronous init blocked FUSA cold boot
 * for ~50s and tripped [ServerService] component-init timeouts.
 *
 * Fire Stick skips the 3.6MB CSV entirely (maps expand to tens of MB and trip LMK).
 */
class IptvOrgNameIndex(context: Context) {
    private val appContext = context.applicationContext
    private val fireLite = FireMemoryGuard.skipHeavyCatalogIndexes(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadGate = CompletableDeferred<Unit>()
    @Volatile
    private var byNormName: Map<String, String> = emptyMap()
    @Volatile
    private var normKeys: List<String> = emptyList()
    @Volatile
    private var loaded = false

    init {
        if (fireLite) {
            loaded = true
            loadGate.complete(Unit)
            Log.i(TAG, "iptv-org name index: skipped on Fire Stick (memory)")
        } else {
            scope.launch {
                runCatching { loadCsv() }
                    .onSuccess { (map, keys) ->
                        byNormName = map
                        normKeys = keys
                        loaded = true
                        loadGate.complete(Unit)
                        Log.i(TAG, "iptv-org name index: ${map.size} keys")
                    }
                    .onFailure { exc ->
                        Log.w(TAG, "channels_db_cache.csv name index failed", exc)
                        loadGate.completeExceptionally(exc)
                    }
            }
        }
    }

    suspend fun awaitLoaded(timeoutMs: Long = 120_000L) {
        if (loaded) return
        withTimeout(timeoutMs) { loadGate.await() }
    }

    fun isLoaded(): Boolean = loaded

    fun lookupExact(channelName: String): String? {
        if (!loaded) return null
        val norm = TvgIdNormalizer.normalizeChannelName(channelName)
        if (norm.isEmpty()) return null
        return byNormName[norm]
    }

    fun lookupFuzzy(channelName: String, minScore: Double = FUZZY_MIN_SCORE): String? {
        if (!loaded) return null
        val norm = TvgIdNormalizer.normalizeChannelName(channelName)
        if (norm.isEmpty()) return null
        byNormName[norm]?.let { return it }
        val queryTokens = norm.split(' ').filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty()) return null
        var bestId: String? = null
        var bestScore = minScore
        for (key in normKeys) {
            val keyTokens = key.split(' ').filter { it.length > 1 }.toSet()
            if (keyTokens.isEmpty()) continue
            val intersection = queryTokens.intersect(keyTokens).size
            val union = queryTokens.union(keyTokens).size
            if (union == 0) continue
            val score = intersection.toDouble() / union.toDouble()
            if (score > bestScore) {
                bestScore = score
                bestId = byNormName[key]
            }
        }
        return bestId
    }

    private fun loadCsv(): Pair<Map<String, String>, List<String>> {
        val map = linkedMapOf<String, String>()
        appContext.assets.open("channels_db_cache.csv").bufferedReader().use { reader ->
            reader.readLine()
            reader.forEachLine { line ->
                val row = parseCsvLine(
                    line,
                    listOf("id", "name", "alt_names", "network", "owners", "country", "categories", "is_nsfw"),
                )
                val id = row["id"].orEmpty().trim()
                if (id.isEmpty()) return@forEachLine
                indexName(map, row["name"].orEmpty(), id)
                row["alt_names"].orEmpty().split(';').forEach { alt ->
                    indexName(map, alt, id)
                }
            }
        }
        return map to map.keys.toList()
    }

    private fun indexName(map: MutableMap<String, String>, rawName: String, id: String) {
        val norm = TvgIdNormalizer.normalizeChannelName(rawName)
        if (norm.isNotEmpty() && norm !in map) {
            map[norm] = id
        }
    }

    private fun parseCsvLine(line: String, columns: List<String>): Map<String, String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' -> {
                    if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        fields += current.toString()
        return columns.mapIndexed { i, col -> col to fields.getOrElse(i) { "" } }.toMap()
    }

    companion object {
        private const val TAG = "IptvOrgNameIndex"
        const val FUZZY_MIN_SCORE = 0.72
    }
}

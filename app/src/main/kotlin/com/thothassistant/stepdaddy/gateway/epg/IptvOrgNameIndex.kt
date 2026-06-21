package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.upstream.TvgIdNormalizer
import java.util.Locale

/**
 * Name → iptv-org [tvg-id] index from bundled channels_db_cache.csv.
 * Shared by auto-resolve (Phase 1a) and supplement import (Phase 1b).
 */
class IptvOrgNameIndex(context: Context) {
    private val byNormName: Map<String, String>
    private val normKeys: List<String>

    init {
        val map = linkedMapOf<String, String>()
        runCatching {
            context.assets.open("channels_db_cache.csv").bufferedReader().use { reader ->
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
            Log.i(TAG, "iptv-org name index: ${map.size} keys")
        }.onFailure { exc ->
            Log.w(TAG, "channels_db_cache.csv name index failed", exc)
        }
        byNormName = map
        normKeys = map.keys.toList()
    }

    fun lookupExact(channelName: String): String? {
        val norm = TvgIdNormalizer.normalizeChannelName(channelName)
        if (norm.isEmpty()) return null
        return byNormName[norm]
    }

    fun lookupFuzzy(channelName: String, minScore: Double = FUZZY_MIN_SCORE): String? {
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

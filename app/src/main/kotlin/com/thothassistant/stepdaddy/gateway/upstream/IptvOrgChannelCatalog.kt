package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log

/**
 * iptv-org channel metadata from bundled [channels_db_cache.csv] (tvg-id → categories, country).
 */
fun interface IptvOrgChannelLookup {
    fun lookup(tvgId: String?): IptvOrgChannelCatalog.Row?
}

class IptvOrgChannelCatalog(context: Context) : IptvOrgChannelLookup {
    data class Row(
        val categories: List<String>,
        val country: String,
        val isNsfw: Boolean,
    )

    private val byId: Map<String, Row> = load(context)

    override fun lookup(tvgId: String?): Row? {
        val base = tvgId?.trim()?.substringBefore('@')?.lowercase().orEmpty()
        if (base.isEmpty()) return null
        return byId[base]
    }

    private fun load(context: Context): Map<String, Row> {
        return runCatching {
            val out = linkedMapOf<String, Row>()
            context.assets.open("channels_db_cache.csv").bufferedReader().use { reader ->
                reader.readLine()
                reader.forEachLine { line ->
                    val row = parseCsvLine(
                        line,
                        listOf("id", "name", "alt_names", "network", "owners", "country", "categories", "is_nsfw"),
                    )
                    val id = row["id"].orEmpty().trim().lowercase()
                    if (id.isEmpty()) return@forEachLine
                    val categories = row["categories"].orEmpty()
                        .split(';')
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                    val country = row["country"].orEmpty().trim().uppercase()
                    val isNsfw = row["is_nsfw"].orEmpty().trim().equals("true", ignoreCase = true)
                    out[id] = Row(categories = categories, country = country, isNsfw = isNsfw)
                }
            }
            Log.i(TAG, "Loaded ${out.size} iptv-org catalog rows")
            out
        }.getOrElse { exc ->
            Log.w(TAG, "channels_db_cache.csv load failed", exc)
            emptyMap()
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
        private const val TAG = "IptvOrgChannelCatalog"
    }
}

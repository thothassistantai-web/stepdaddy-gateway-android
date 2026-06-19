package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Channel tags and logos from bundled [meta.json], keyed by channel name (same as Linux step_daddy.py).
 */
class ChannelMetaStore(context: Context) {
    private val entries = mutableMapOf<String, MetaEntry>()

    init {
        runCatching { load(context) }
            .onFailure { exc -> Log.w(TAG, "meta.json load failed", exc) }
    }

    fun tagsFor(channelName: String): List<String> {
        val key = metaKey(channelName)
        return entries[key]?.tags.orEmpty()
    }

    fun logoFor(channelName: String): String? =
        entries[metaKey(channelName)]?.logo

    private fun metaKey(channelName: String): String =
        if (channelName.startsWith("18+")) "18+" else channelName

    private fun load(context: Context) {
        context.assets.open("meta.json").bufferedReader().use { reader ->
            val root = JSONObject(reader.readText())
            root.keys().forEach { key ->
                val row = root.getJSONObject(key)
                val tags = buildList {
                    val array = row.optJSONArray("tags") ?: return@buildList
                    for (index in 0 until array.length()) {
                        val tag = array.optString(index).trim()
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
                entries[key] = MetaEntry(
                    tags = tags,
                    logo = row.optString("logo").takeIf { it.isNotBlank() },
                )
            }
        }
        Log.i(TAG, "Loaded ${entries.size} meta entries")
    }

    private data class MetaEntry(
        val tags: List<String>,
        val logo: String?,
    )

    companion object {
        private const val TAG = "ChannelMetaStore"
    }
}

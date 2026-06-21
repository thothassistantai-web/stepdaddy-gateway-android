package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Runtime category overrides keyed by channel id and/or normalized display name.
 * Persisted to [RUNTIME_OVERRIDES_FILE]; applied in [GroupTitleResolver.resolve].
 */
object CategoryOverrideStore {
    private val byChannelId = mutableMapOf<String, String>()
    private val byNormName = mutableMapOf<String, String>()
    @Volatile
    private var loaded = false

    val validGroups: Set<String> = setOf(
        GroupTitleResolver.LOCAL_CHANNELS,
        GroupTitleResolver.SPORTS,
        GroupTitleResolver.ENTERTAINMENT,
        GroupTitleResolver.MOVIES,
        GroupTitleResolver.NEWS,
        GroupTitleResolver.DOCUMENTARY,
        GroupTitleResolver.MUSIC,
        GroupTitleResolver.KIDS,
        GroupTitleResolver.EXTRA_247,
        GroupTitleResolver.INTERNATIONAL,
        GroupTitleResolver.EN_ESPANOL,
        GroupTitleResolver.ADULT,
    )

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            load(context.applicationContext)
            loaded = true
        }
    }

    fun overrideGroup(channelId: String?, channelName: String): String? {
        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            byChannelId[id]?.let { return it }
        }
        val norm = normalizeKey(channelName)
        if (norm.isNotEmpty()) {
            byNormName[norm]?.let { return it }
        }
        return null
    }

    fun put(context: Context, channelId: String?, channelName: String?, groupTitle: String) {
        ensureLoaded(context)
        val group = groupTitle.trim()
        require(group in validGroups) { "Invalid groupTitle: $group" }
        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { byChannelId[it] = group }
        channelName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            byNormName[normalizeKey(name)] = group
        }
        save(context.applicationContext)
    }

    fun putBatch(context: Context, entries: List<Triple<String?, String?, String>>) {
        ensureLoaded(context)
        entries.forEach { (id, name, group) ->
            val g = group.trim()
            if (g !in validGroups) return@forEach
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { byChannelId[it] = g }
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { byNormName[normalizeKey(it)] = g }
        }
        save(context.applicationContext)
    }

    fun remove(context: Context, channelId: String?, channelName: String?): Boolean {
        ensureLoaded(context)
        var removed = false
        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (byChannelId.remove(it) != null) removed = true
        }
        channelName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (byNormName.remove(normalizeKey(it)) != null) removed = true
        }
        if (removed) save(context.applicationContext)
        return removed
    }

    fun snapshot(): Map<String, String> = buildMap {
        byChannelId.forEach { (id, group) -> put("id:$id", group) }
        byNormName.forEach { (name, group) -> put("name:$name", group) }
    }

    fun clearRuntime(context: Context) {
        ensureLoaded(context)
        byChannelId.clear()
        byNormName.clear()
        save(context.applicationContext)
    }

    fun save(context: Context) {
        val file = runtimeOverridesFile(context)
        file.parentFile?.mkdirs()
        val json = JSONObject()
        byChannelId.forEach { (id, group) -> json.put("id:$id", group) }
        byNormName.forEach { (name, group) -> json.put("name:$name", group) }
        file.writeText(json.toString())
        Log.i(TAG, "Saved ${byChannelId.size} id + ${byNormName.size} name category overrides")
    }

    fun runtimeOverridesFile(context: Context): File =
        File(context.filesDir, RUNTIME_OVERRIDES_FILE)

    private fun load(context: Context) {
        byChannelId.clear()
        byNormName.clear()
        val file = runtimeOverridesFile(context)
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { key ->
                val group = root.optString(key).trim()
                if (group.isEmpty() || group !in validGroups) return@forEach
                when {
                    key.startsWith("id:") -> byChannelId[key.removePrefix("id:")] = group
                    key.startsWith("name:") -> byNormName[key.removePrefix("name:")] = group
                }
            }
        }.onFailure { exc -> Log.w(TAG, "Category overrides load failed", exc) }
    }

    private fun normalizeKey(channelName: String): String =
        channelName.trim().lowercase().replace(Regex("\\s+"), " ")

    private const val TAG = "CategoryOverrideStore"
    const val RUNTIME_OVERRIDES_FILE = "categories/overrides.json"
}

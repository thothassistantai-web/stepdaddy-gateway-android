package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Serializable
private data class MappingAsset(
    val mapping: Map<String, String> = emptyMap(),
    val mapped_count: Int = 0,
)

/**
 * Maps DaddyLive channel ids / display names to playlist [tvg-id] values.
 * Load order: bundled id map → runtime id map → research mappings → bundled name overrides → runtime name overrides.
 */
class EpgChannelMapper(context: Context) {
  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true }
  private val researchStore = DaddyliveEpgResearchStore(appContext)
  private val byChannelId: MutableMap<String, String> = mutableMapOf()
  private val byNormName: MutableMap<String, String> = mutableMapOf()

  init {
    loadBundledIdMap(appContext)
    loadRuntimeIdMap(appContext)
    loadBundledNameOverrides(appContext)
    loadRuntimeNameOverrides(appContext)
  }

  fun tvgIdFor(channelId: String, channelName: String): String? {
    byChannelId[channelId.trim()]?.let { return it }
    researchStore.lookupByChannelId(channelId)?.tvgId?.let { return it }
    researchStore.lookupByName(channelName)?.tvgId?.let { return it }
    return tvgIdForName(channelName)
  }

  /** Name-only lookup (supplements, runtime overrides, research). */
  fun tvgIdForName(channelName: String): String? {
    researchStore.lookupByName(channelName)?.tvgId?.let { return it }
    lookupNameOverride(channelName)?.let { return it }
    for (variant in nameLookupVariants(channelName)) {
      if (variant == channelName) continue
      researchStore.lookupByName(variant)?.tvgId?.let { return it }
      lookupNameOverride(variant)?.let { return it }
    }
    return null
  }

  private fun lookupNameOverride(channelName: String): String? {
    val norm = normalizeName(channelName)
    if (norm.isEmpty()) return null
    return byNormName[norm]
  }

  private fun nameLookupVariants(channelName: String): List<String> {
    val trimmed = channelName.trim()
    if (trimmed.isEmpty()) return emptyList()
    val variants = linkedSetOf(trimmed)
    val stripped = trimmed.replace(
        Regex("\\s+(CDN|Falcon|Mena|\\(MOJ\\))\\s*$", RegexOption.IGNORE_CASE),
        "",
    ).trim()
    if (stripped.isNotEmpty()) variants += stripped
    val noFlag = trimmed.replace(Regex("[🇺🇸🇬🇧🇨🇦📡🎬]"), "").trim()
    if (noFlag.isNotEmpty()) variants += noFlag
    return variants.toList()
  }

  fun mappedCount(): Int = byChannelId.size + byNormName.size

  fun nameOverrideCount(): Int = byNormName.size

  fun allTvgIds(channelIds: Collection<String>, namesById: Map<String, String>): Set<String> {
    val out = linkedSetOf<String>()
    channelIds.forEach { id ->
      val name = namesById[id].orEmpty()
      tvgIdFor(id, name)?.let { out += it }
    }
    return out
  }

  fun putRuntimeNameOverride(channelName: String, tvgId: String) {
    val name = channelName.trim()
    val id = tvgId.trim()
    if (name.isEmpty() || id.isEmpty()) return
    synchronized(byNormName) {
      byNormName[normalizeName(name)] = id
    }
  }

  fun putRuntimeIdOverride(channelId: String, tvgId: String) {
    val key = channelId.trim()
    val value = tvgId.trim()
    if (key.isEmpty() || value.isEmpty()) return
    synchronized(byChannelId) {
      byChannelId[key] = value
    }
  }

  fun saveRuntimeNameOverrides(context: Context) {
    val file = runtimeNameOverridesFile(context)
    file.parentFile?.mkdirs()
    val bundled = loadBundledNameOverrideKeys(context)
    val snapshot = synchronized(byNormName) { byNormName.toMap() }
    val runtimeOnly = snapshot.filter { (norm, tvg) -> bundled[norm] != tvg }
    val jsonOut = JSONObject()
    runtimeOnly.forEach { (norm, tvg) -> jsonOut.put(norm, tvg) }
    file.writeText(jsonOut.toString())
    Log.i(TAG, "Saved ${runtimeOnly.size} runtime EPG name overrides")
  }

  fun saveRuntimeIdMap(context: Context) {
    val file = File(context.filesDir, "epg/channel_epg_map.json")
    file.parentFile?.mkdirs()
    val snapshot = synchronized(byChannelId) { byChannelId.toMap() }
    val asset = MappingAsset(mapping = snapshot, mapped_count = snapshot.size)
    file.writeText(json.encodeToString(asset))
    Log.i(TAG, "Saved ${snapshot.size} runtime EPG id mappings")
  }

  private fun loadBundledIdMap(context: Context) {
    try {
      val text = context.assets.open(EpgConfig.MAPPING_ASSET).bufferedReader().use { it.readText() }
      val asset = json.decodeFromString<MappingAsset>(text)
      asset.mapping.forEach { (id, tvg) -> putIdMapping(id, tvg) }
    } catch (_: Exception) {
      // Asset missing — name overrides may still apply.
    }
  }

  private fun loadRuntimeIdMap(context: Context) {
    val file = File(context.filesDir, "epg/channel_epg_map.json")
    if (!file.isFile) return
    runCatching {
      val asset = json.decodeFromString<MappingAsset>(file.readText())
      asset.mapping.forEach { (id, tvg) -> putIdMapping(id, tvg) }
    }
  }

  private fun loadBundledNameOverrides(context: Context) {
    runCatching {
      val text = context.assets.open(EpgConfig.NAME_OVERRIDES_ASSET).bufferedReader().use { it.readText() }
      val root = JSONObject(text)
      root.keys().forEach { key ->
        val tvg = root.optString(key).trim()
        if (tvg.isNotEmpty()) {
          putNameOverride(key, tvg)
        }
      }
      Log.i(TAG, "Loaded ${root.length()} bundled EPG name overrides")
    }.onFailure {
      Log.w(TAG, "Bundled EPG name overrides missing or invalid")
    }
  }

  private fun loadRuntimeNameOverrides(context: Context) {
    val file = runtimeNameOverridesFile(context)
    if (!file.isFile) return
    runCatching {
      val root = JSONObject(file.readText())
      root.keys().forEach { key ->
        val tvg = root.optString(key).trim()
        if (tvg.isNotEmpty()) {
          byNormName[key.trim()] = tvg
        }
      }
      Log.i(TAG, "Loaded ${root.length()} runtime EPG name overrides")
    }.onFailure { exc ->
      Log.w(TAG, "Runtime EPG name overrides load failed", exc)
    }
  }

  private fun loadBundledNameOverrideKeys(context: Context): Map<String, String> {
    return runCatching {
      val text = context.assets.open(EpgConfig.NAME_OVERRIDES_ASSET).bufferedReader().use { it.readText() }
      val root = JSONObject(text)
      buildMap {
        root.keys().forEach { key ->
          val tvg = root.optString(key).trim()
          if (tvg.isNotEmpty()) put(normalizeName(key), tvg)
        }
      }
    }.getOrDefault(emptyMap())
  }

  private fun putIdMapping(channelId: String, tvgId: String) {
    val key = channelId.trim()
    val value = tvgId.trim()
    if (key.isNotEmpty() && value.isNotEmpty()) {
      byChannelId[key] = value
    }
  }

  private fun putNameOverride(displayName: String, tvgId: String) {
    val norm = normalizeName(displayName)
    if (norm.isNotEmpty()) {
      byNormName[norm] = tvgId.trim()
    }
  }

  companion object {
    private const val TAG = "EpgChannelMapper"
    const val RUNTIME_NAME_OVERRIDES_FILE = "epg/name_overrides.json"

    fun runtimeNameOverridesFile(context: Context): File =
        File(context.filesDir, RUNTIME_NAME_OVERRIDES_FILE)

    fun normalizeName(name: String): String {
      var s = name.lowercase(Locale.US)
      s = s.replace(Regex("\\([^)]*\\)"), " ")
      s = s.replace("+", " plus ").replace("&", " and ")
      s = s.replace(Regex("\\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\\b"), " ")
      s = s.replace(Regex("[^a-z0-9]+"), " ")
      return s.trim().replace(Regex("\\s+"), " ")
    }
  }
}

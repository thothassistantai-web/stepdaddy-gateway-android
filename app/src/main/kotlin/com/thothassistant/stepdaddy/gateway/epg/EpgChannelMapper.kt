package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
private data class MappingAsset(
    val mapping: Map<String, String> = emptyMap(),
    val mapped_count: Int = 0,
)

class EpgChannelMapper(context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val byChannelId: MutableMap<String, String> = mutableMapOf()
  private val byNormName: MutableMap<String, String> = mutableMapOf()

  init {
    loadBundled(context)
    loadFilesDirOverride(context)
  }

  fun tvgIdFor(channelId: String, channelName: String): String? {
    byChannelId[channelId]?.let { return it }
    val norm = normalizeName(channelName)
    byNormName[norm]?.let { return it }
    return null
  }

  fun mappedCount(): Int = byChannelId.size

  fun allTvgIds(channelIds: Collection<String>, namesById: Map<String, String>): Set<String> {
    val out = linkedSetOf<String>()
    channelIds.forEach { id ->
      val name = namesById[id].orEmpty()
      tvgIdFor(id, name)?.let { out += it }
    }
    return out
  }

  private fun loadBundled(context: Context) {
  try {
      val text = context.assets.open(EpgConfig.MAPPING_ASSET).bufferedReader().use { it.readText() }
      val asset = json.decodeFromString<MappingAsset>(text)
      asset.mapping.forEach { (id, tvg) ->
        val key = id.trim()
        val value = tvg.trim()
        if (key.isNotEmpty() && value.isNotEmpty()) {
          byChannelId[key] = value
        }
      }
    } catch (_: Exception) {
      // Asset missing — name fallback only.
    }
  }

  private fun loadFilesDirOverride(context: Context) {
    val file = File(context.filesDir, "epg/channel_epg_map.json")
    if (!file.exists()) return
    try {
      val asset = json.decodeFromString<MappingAsset>(file.readText())
      asset.mapping.forEach { (id, tvg) ->
        val key = id.trim()
        val value = tvg.trim()
        if (key.isNotEmpty() && value.isNotEmpty()) {
          byChannelId[key] = value
        }
      }
    } catch (_: Exception) {
      // Ignore corrupt override file.
    }
  }

  companion object {
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

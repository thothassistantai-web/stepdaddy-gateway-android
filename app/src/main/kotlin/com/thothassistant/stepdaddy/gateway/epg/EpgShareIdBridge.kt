package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Maps playlist [tvg-id] values (often iptv-org style) to epgshare feed channel ids
 * so [LightEpgBuilder] can extract programmes while keeping playlist ids in output XML.
 */
class EpgShareIdBridge(context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val feedIdsByPlaylistId: Map<String, List<String>>

  init {
    feedIdsByPlaylistId = loadBundled(context) + loadRuntime(context)
    Log.i(TAG, "EPG id bridge: ${feedIdsByPlaylistId.size} playlist ids")
  }

  fun expandWantedIds(playlistTvgIds: Set<String>): EpgTvgIdMatcher.IdExpansion =
      expandFromBridge(feedIdsByPlaylistId, playlistTvgIds)

  fun bridgeSize(): Int = feedIdsByPlaylistId.size

  private fun loadBundled(context: Context): Map<String, List<String>> =
      runCatching {
        val text = context.assets.open(EpgConfig.ID_BRIDGE_ASSET).bufferedReader().use { it.readText() }
        parseBridge(json.decodeFromString<BridgeAsset>(text).bridge)
      }.getOrElse { emptyMap() }

  private fun loadRuntime(context: Context): Map<String, List<String>> {
    val file = runtimeBridgeFile(context)
    if (!file.isFile) return emptyMap()
    return runCatching {
      parseBridge(json.decodeFromString<BridgeAsset>(file.readText()).bridge)
    }.getOrElse { emptyMap() }
  }

  private fun parseBridge(raw: Map<String, List<String>>): Map<String, List<String>> =
      raw.mapNotNull { (playlistId, feedIds) ->
        val key = playlistId.trim()
        val ids = feedIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (key.isEmpty() || ids.isEmpty()) null else key to ids
      }.toMap()

  @Serializable
  private data class BridgeAsset(
      val bridge: Map<String, List<String>> = emptyMap(),
      val generated_at: String? = null,
      val bridge_count: Int = 0,
  )

  companion object {
    private const val TAG = "EpgShareIdBridge"
    const val RUNTIME_BRIDGE_FILE = "epg/id_bridge.json"

    fun runtimeBridgeFile(context: Context): File =
        File(context.filesDir, RUNTIME_BRIDGE_FILE)

    internal fun expandFromBridge(
        feedIdsByPlaylistId: Map<String, List<String>>,
        playlistTvgIds: Set<String>,
    ): EpgTvgIdMatcher.IdExpansion {
      val base = EpgTvgIdMatcher.expandWantedIds(playlistTvgIds)
      val lookupIds = linkedSetOf<String>().apply { addAll(base.lookupIds) }
      val remapToPlaylist = linkedMapOf<String, String>().apply { putAll(base.remapToPlaylist) }

      playlistTvgIds.forEach { playlistId ->
        val trimmed = playlistId.trim()
        if (trimmed.isEmpty()) return@forEach
        feedIdsByPlaylistId[trimmed].orEmpty().forEach { feedId ->
          lookupIds += feedId
          remapToPlaylist.putIfAbsent(feedId, trimmed)
          val feedBase = feedId.substringBefore('@')
          if (feedBase.isNotEmpty() && feedBase != feedId) {
            lookupIds += feedBase
            remapToPlaylist.putIfAbsent(feedBase, trimmed)
          }
        }
      }
      return EpgTvgIdMatcher.IdExpansion(lookupIds = lookupIds, remapToPlaylist = remapToPlaylist)
    }
  }
}

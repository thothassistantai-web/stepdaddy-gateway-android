package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bundled channel-id -> display name overrides for upstream mislabels.
 */
class ChannelNameOverrides(context: Context) {
    private val byChannelId: Map<String, String> = run {
        try {
            context.assets.open("channel_name_overrides.json").bufferedReader().use { reader ->
                val root = Json.parseToJsonElement(reader.readText()) as? JsonObject ?: return@run emptyMap()
                root.mapNotNull { (id, value) ->
                    val name = value.jsonPrimitive.content.trim()
                    if (id.isNotBlank() && name.isNotBlank()) id.trim() to name else null
                }.toMap()
            }
        } catch (exc: Exception) {
            Log.w(TAG, "channel_name_overrides.json load failed", exc)
            emptyMap()
        }
    }

    fun nameFor(channelId: String, upstreamName: String): String =
        byChannelId[channelId.trim()] ?: upstreamName

    companion object {
        private const val TAG = "ChannelNameOverrides"
    }
}

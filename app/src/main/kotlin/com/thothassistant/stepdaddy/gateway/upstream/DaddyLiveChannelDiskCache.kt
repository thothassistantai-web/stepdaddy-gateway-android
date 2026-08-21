package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import org.json.JSONObject

/** Reads DaddyLive channel rows from the same SharedPreferences cache as [DaddyLiveClient]. */
object DaddyLiveChannelDiskCache {
    fun read(
        context: Context,
        metaStore: ChannelMetaStore? = null,
        epgChannelMapper: EpgChannelMapper? = null,
    ): List<Channel> {
        val raw = context.applicationContext
            .getSharedPreferences("stepdaddy_channels", Context.MODE_PRIVATE)
            .getString("channels_json", null)
            ?: return emptyList()
        return runCatching {
            val rows = JSONObject(raw).getJSONArray("channels")
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val id = row.optString("id").trim()
                    val name = row.optString("name").trim()
                    if (id.isEmpty() || name.isEmpty()) continue
                    val tags = metaStore?.tagsFor(name).orEmpty()
                    val tvgId = row.optString("tvg_id").takeIf { it.isNotBlank() }
                        ?: epgChannelMapper?.tvgIdFor(id, name)
                    add(
                        Channel(
                            id = id,
                            name = name,
                            tags = tags,
                            tvgId = tvgId,
                            logo = row.optString("logo").takeIf { it.isNotBlank() },
                            embedUrl = row.optString("embed_url").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

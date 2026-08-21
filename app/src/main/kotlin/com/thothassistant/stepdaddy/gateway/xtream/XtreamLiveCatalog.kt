package com.thothassistant.stepdaddy.gateway.xtream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.ChannelNumberResolver
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.FreeTvIptvConfig
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventLifecycle
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodConfig
import com.thothassistant.stepdaddy.gateway.upstream.VodCategoryResolver
import kotlinx.serialization.Serializable
import java.time.Instant

/** Live TV rows for Xtream `get_live_categories` / `get_live_streams`. */
object XtreamLiveCatalog {
    @Serializable
    data class LiveCategory(
        val category_id: String,
        val category_name: String,
        val parent_id: Int = 0,
    )

    @Serializable
    data class LiveStream(
        val num: Int,
        val name: String,
        val stream_type: String = "live",
        val stream_id: Int,
        val stream_icon: String? = null,
        val epg_channel_id: String? = null,
        val category_id: String = "",
        val tv_archive: Int = 0,
        val direct_source: String = "",
        val tv_archive_duration: Int = 0,
    )

    private data class LiveRow(
        val name: String,
        val groupTitle: String,
        val tvgId: String?,
        val logo: String?,
        val streamId: Int,
        val directSource: String,
    )

    fun categories(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<LiveCategory> =
        buildRows(channels, supplements, baseUrl = "", nowMs)
            .map { it.groupTitle }
            .distinct()
            .sortedBy { GroupTitleResolver.groupSortOrder(it) }
            .map { title ->
                LiveCategory(
                    category_id = VodCategoryResolver.categoryId(title),
                    category_name = title,
                )
            }

    fun streams(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        baseUrl: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<LiveStream> =
        buildRows(channels, supplements, baseUrl, nowMs).mapIndexed { index, row ->
            LiveStream(
                num = index + 1,
                name = row.name,
                stream_id = row.streamId,
                stream_icon = row.logo?.takeIf { it.startsWith("http") },
                epg_channel_id = row.tvgId,
                category_id = VodCategoryResolver.categoryId(row.groupTitle),
                direct_source = row.directSource,
            )
        }

    private fun buildRows(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        baseUrl: String,
        nowMs: Long,
    ): List<LiveRow> = GroupTitleResolver.withResolveCache {
        val now = Instant.ofEpochMilli(nowMs)
        val liveSupplements = supplements.filter { supplement ->
            !supplement.id.startsWith(TmdbVodConfig.ID_PREFIX) &&
                !supplement.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) &&
                SpecialEventLifecycle.isDlhdEventPlaylistVisible(supplement, now)
        }
        val (channelNumbers, supplementNumbers) = ChannelNumberResolver.assignPlaylist(channels, liveSupplements)
        val rows = ArrayList<LiveRow>(channels.size + liveSupplements.size)
        val base = baseUrl.trimEnd('/')

        channels.forEach { channel ->
            val chno = channelNumbers[channel.id] ?: return@forEach
            val resolution = GroupTitleResolver.resolve(channel.name, channel.tags, channel.id)
            val numericId = channel.id.toIntOrNull()
            rows += LiveRow(
                name = channel.name,
                groupTitle = resolution.groupTitle,
                tvgId = channel.tvgId,
                logo = channel.logo,
                streamId = numericId ?: chno,
                directSource = if (numericId != null || base.isEmpty()) {
                    ""
                } else {
                    gatewayStreamUrl(base, channel.id)
                },
            )
        }

        liveSupplements.forEach { supplement ->
            val chno = supplementNumbers[supplement.id] ?: return@forEach
            rows += LiveRow(
                name = supplement.name,
                groupTitle = supplementGroupTitle(supplement),
                tvgId = supplement.tvgId,
                logo = supplement.logo,
                streamId = supplement.id.toIntOrNull() ?: chno,
                directSource = if (base.isEmpty()) "" else supplementDirectSource(supplement, base),
            )
        }

        rows.sortWith(
            compareBy(
                { GroupTitleResolver.groupSortOrder(it.groupTitle) },
                { it.name.lowercase() },
            ),
        )
        rows
    }

    private fun supplementGroupTitle(supplement: SupplementChannel): String {
        if (            supplement.id.startsWith("dlhd-guide:") ||
            supplement.id.startsWith("dlhd-event:")
        ) {
            return GroupTitleResolver.SPECIAL_EVENTS
        }
        if (supplement.id.startsWith("iptv:") ||
            supplement.id.startsWith(FreeTvIptvConfig.ID_PREFIX)
        ) {
            return GroupTitleResolver.resolve(supplement.name, supplement.tags, supplement.id).groupTitle
        }
        return supplement.groupTitle.ifBlank { GroupTitleResolver.ENTERTAINMENT }
    }

    private fun gatewayStreamUrl(base: String, channelId: String): String =
        "$base/tivimate-stream/$channelId.m3u8"

    private fun supplementDirectSource(supplement: SupplementChannel, base: String): String =
        when {
            supplement.id.startsWith("dlhd-guide:") -> {
                val slug = supplement.id.removePrefix("dlhd-guide:")
                "$base/dlhd-event-guide/$slug.m3u8"
            }
            supplement.id.startsWith("dlhd-event:") -> {
                val token = supplement.dlhdEventKey ?: supplement.id.removePrefix("dlhd-event:")
                "$base/tivimate-stream/dlhd-event-$token.m3u8"
            }
            supplement.id.startsWith("ntv:") -> {
                val token = supplement.id.removePrefix("ntv:")
                "$base/ntv-stream/$token.m3u8"
            }
            supplement.id.startsWith(DuloCxLiveConfig.ID_PREFIX) -> {
                val uuid = supplement.duloChannelId?.trim().orEmpty()
                    .ifEmpty { supplement.id.removePrefix(DuloCxLiveConfig.ID_PREFIX) }
                "$base/dulo-stream/$uuid.m3u8"
            }
            supplement.streamUrl.startsWith("http") -> supplement.streamUrl
            else -> gatewayStreamUrl(base, supplement.id)
        }
}

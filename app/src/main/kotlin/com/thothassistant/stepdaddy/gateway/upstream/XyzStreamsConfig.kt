package com.thothassistant.stepdaddy.gateway.upstream

/**
 * xyzstreams.st Sling-backed 24/7 US cable / broadcast feeds.
 * @see <a href="https://xyzstreams.st/">xyzstreams.st</a>
 */
object XyzStreamsConfig {
    const val PROVIDER_TAG = "xyzstreams"
    const val REFERER = "https://xyzstreams.st/"
    const val ORIGIN = "https://xyzstreams.st"
    const val GATEWAY_STREAM_BASE = "247v2.xyzstreams.st"
    const val PRO_ID_SLING = "sling"

    /** TV Guide lineup id (NYC / Sling market) used by xyzstreams for EPG. */
    const val TVGUIDE_LINEUP_ID = "9166055989"

    const val TVGUIDE_PROXY_BASE =
        "https://guide.alexyoung65656.workers.dev/https://backend.tvguide.com"

    const val TVGUIDE_DIRECT_BASE = "https://backend.tvguide.com"

    /** EPG window length in minutes (matches xyzstreams homepage). */
    const val EPG_DURATION_MINUTES = 120

    enum class UpstreamKind {
        SLING_247V2,
        FTV_LOCAL,
    }

    data class ChannelRow(
        val streamId: String,
        val displayName: String,
        val tvgId: String,
        val logo: String?,
        /** TV Guide [channel.name] or [channel.networkName] keys for EPG matching. */
        val epgKeys: Set<String>,
        val groupTitle: String,
        val upstreamKind: UpstreamKind = UpstreamKind.SLING_247V2,
        val proId: String = PRO_ID_SLING,
        val ftvPath: String? = null,
    )

    val CATALOG: List<ChannelRow> = XyzStreamsCatalog.CATALOG

    private val epgKeyIndex: Map<String, ChannelRow> = buildMap {
        CATALOG.forEach { row ->
            row.epgKeys.forEach { key -> put(key.uppercase(), row) }
        }
    }

    fun catalogRowForStreamId(streamId: String): ChannelRow? =
        CATALOG.firstOrNull { it.streamId.equals(streamId, ignoreCase = true) }

    fun catalogRowForEpgKey(key: String): ChannelRow? =
        epgKeyIndex[key.trim().uppercase()]

    fun upstreamManifestUrl(row: ChannelRow): String = when (row.upstreamKind) {
        UpstreamKind.FTV_LOCAL -> {
            val path = row.ftvPath?.trim().orEmpty()
            check(path.isNotEmpty()) { "ftv path missing for ${row.streamId}" }
            "https://ftv.xyzstreams.st/$path"
        }
        UpstreamKind.SLING_247V2 ->
            "https://$GATEWAY_STREAM_BASE/?stream_id=${row.streamId}&pro_id=${row.proId}&index.m3u8"
    }

    fun tvguideScheduleUrl(startEpochSec: Long, useProxy: Boolean = true): String {
        val base = if (useProxy) TVGUIDE_PROXY_BASE else TVGUIDE_DIRECT_BASE
        return "$base/tvschedules/tvguide/$TVGUIDE_LINEUP_ID/web" +
            "?start=$startEpochSec&duration=$EPG_DURATION_MINUTES"
    }
}

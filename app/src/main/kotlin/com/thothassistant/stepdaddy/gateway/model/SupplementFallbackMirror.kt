package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.Serializable

/** Alternate upstream for a consolidated live channel row (supplement URL or ntv resolve key). */
@Serializable
data class SupplementFallbackMirror(
    val streamUrl: String = "",
    val label: String = "",
    val referer: String? = null,
    val origin: String? = null,
    /** Play-time resolve key for ntv.cx fallbacks attached to DaddyLive rows. */
    val ntvCdnLiveKey: String? = null,
    /** Play-time resolve id for dulo.cx Live TV fallbacks attached to DaddyLive rows. */
    val duloChannelId: String? = null,
)

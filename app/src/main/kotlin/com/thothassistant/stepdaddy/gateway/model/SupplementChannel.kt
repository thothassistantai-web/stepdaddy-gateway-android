package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class SupplementChannel(
    val id: String,
    val name: String,
    val tvgId: String? = null,
    val logo: String? = null,
    val groupTitle: String,
    val streamUrl: String,
    /** Tags for [GroupTitleResolver] (iptv-org). */
    val tags: List<String> = emptyList(),
    /** Provider label appended to display title, e.g. Pluto, FireTV. */
    val providerTag: String? = null,
    /** TiviMate pipe Referer= when set (sports embed CDN, MoveOnJoy, etc.). */
    val referer: String? = null,
    val origin: String? = null,
    /** TheTvApp (or similar) event page URL — used for league sort in Special Events. */
    val eventSourceUrl: String? = null,
    /** Play-time resolve key for ntv.cx 24/7 (`server|name|regionOrStreamUrl`). */
    val ntvCdnLiveKey: String? = null,
    /** DaddyLive event stream key: `tv|153` or `tv2|admin/ppv-.../1`. */
    val dlhdEventStreamKey: String? = null,
)

package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Structured metadata scraped from DaddyLive schedules and TheTvApp embeds. */
@Serializable
data class EventMetadata(
    val title: String,
    val slug: String,
    /** ISO-style region code (`US`, `UK`, `CA`, `FR`, …). */
    val region: String,
    val league: String,
    /** Human-readable sport (`Basketball`, `Ice Hockey`, `Soccer`, …). */
    val sportType: String,
    val source: EventMetadataSource,
    val category: String? = null,
    val dateKey: String? = null,
    val timeLabel: String? = null,
    val eventSourceUrl: String? = null,
    val streamLabel: String? = null,
    val languageCode: String? = null,
)

@Serializable
enum class EventMetadataSource {
    @SerialName("dlhd_tv")
    DLHD_TV,

    @SerialName("dlhd_tv2")
    DLHD_TV2,

    @SerialName("thetvapp")
    THE_TV_APP,
}

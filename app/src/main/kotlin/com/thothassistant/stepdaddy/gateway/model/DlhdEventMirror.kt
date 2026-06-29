package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.Serializable

/** Internal failover mirror for a DaddyLive schedule event (`tv|id` or `tv2|path`). */
@Serializable
data class DlhdEventMirror(
    val streamKey: String,
    val label: String = "",
    val referer: String? = null,
    val origin: String? = null,
    /** Probe score from last ranking (higher is better). */
    val probeScore: Int = 0,
    /** Last probe outcome; null when not yet probed. */
    val healthy: Boolean? = null,
)

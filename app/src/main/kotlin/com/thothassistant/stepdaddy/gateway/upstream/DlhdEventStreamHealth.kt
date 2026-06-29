package com.thothassistant.stepdaddy.gateway.upstream

/** Per-token playability state for `dlhd-event:*` supplement channels. */
object DlhdEventStreamHealth {
    enum class Status {
        UNKNOWN,
        HEALTHY,
        UNHEALTHY,
        /** Scheduled block ended; stream row kept in playlist during post-stop grace. */
        ENDED,
    }

    data class Entry(
        val status: Status,
        val lastProbeMs: Long = 0L,
        val lastError: String? = null,
    )

    data class ProbeResult(
        val status: Status,
        val error: String? = null,
    ) {
        companion object {
            fun healthy() = ProbeResult(Status.HEALTHY)
            fun unhealthy(reason: String) = ProbeResult(Status.UNHEALTHY, reason)
            fun unknown(reason: String? = null) = ProbeResult(Status.UNKNOWN, reason)
            fun ended(reason: String? = null) = ProbeResult(Status.ENDED, reason)
        }
    }

    data class Summary(
        val activeStreams: Int = 0,
        val probed: Int = 0,
        val healthy: Int = 0,
        val unhealthy: Int = 0,
        val ended: Int = 0,
        val unknown: Int = 0,
        val lastProbeMs: Long? = null,
    )
}

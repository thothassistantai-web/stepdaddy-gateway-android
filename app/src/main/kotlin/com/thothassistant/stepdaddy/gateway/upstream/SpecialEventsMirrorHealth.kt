package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/** Aggregated mirror health for Special Events supplement rows. */
object SpecialEventsMirrorHealth {
    data class EventMirrorStatus(
        val eventKey: String,
        val mirrorsTotal: Int,
        val mirrorsHealthy: Int,
        val activeMirrorIndex: Int,
    )

    data class Summary(
        val eventsWithMirrors: Int = 0,
        val totalMirrors: Int = 0,
        val healthyMirrors: Int = 0,
        val avgMirrorsPerEvent: Float = 0f,
        val events: List<EventMirrorStatus> = emptyList(),
    )

    fun summarize(
        channels: List<SupplementChannel>,
        activeMirrorIndexByEvent: Map<String, Int> = emptyMap(),
        eventHealthByKey: Map<String, DlhdEventStreamHealth.Status> = emptyMap(),
        mirrorProbeStore: DlhdEventMirrorProbeStore? = null,
        maxEventDetails: Int = 32,
    ): Summary {
        val events = channels.filter { it.id.startsWith("dlhd-event:") }
        if (events.isEmpty()) return Summary()

        var totalMirrors = 0
        var healthyMirrors = 0
        val details = mutableListOf<EventMirrorStatus>()

        events.forEach { channel ->
            val mirrors = mirrorsFor(channel)
            if (mirrors.isEmpty()) return@forEach
            val eventKey = channel.dlhdEventKey ?: channel.id.removePrefix("dlhd-event:")
            val eventStatus = eventHealthByKey[eventKey] ?: DlhdEventStreamHealth.Status.UNKNOWN
            val activeIndex = activeMirrorIndexByEvent[eventKey] ?: 0
            val healthy = mirrors.withIndex().count { (mirrorIndex, mirror) ->
                isMirrorHealthy(
                    mirror = mirror,
                    eventKey = eventKey,
                    eventStatus = eventStatus,
                    activeIndex = activeIndex,
                    mirrorIndex = mirrorIndex,
                    mirrorProbeStore = mirrorProbeStore,
                )
            }
            totalMirrors += mirrors.size
            healthyMirrors += healthy
            details += EventMirrorStatus(
                eventKey = eventKey,
                mirrorsTotal = mirrors.size,
                mirrorsHealthy = healthy,
                activeMirrorIndex = activeIndex,
            )
        }

        val withMirrors = details.size
        val avg = if (withMirrors > 0) totalMirrors.toFloat() / withMirrors else 0f
        return Summary(
            eventsWithMirrors = withMirrors,
            totalMirrors = totalMirrors,
            healthyMirrors = healthyMirrors,
            avgMirrorsPerEvent = avg,
            events = details.take(maxEventDetails),
        )
    }

    fun mirrorsFor(channel: SupplementChannel): List<DlhdEventMirror> {
        if (channel.dlhdEventMirrors.isNotEmpty()) return channel.dlhdEventMirrors
        val key = channel.dlhdEventStreamKey?.trim().orEmpty()
        if (key.isEmpty()) return emptyList()
        return listOf(DlhdEventMirror(streamKey = key, label = channel.name))
    }

    internal fun isMirrorHealthy(
        mirror: DlhdEventMirror,
        eventKey: String,
        eventStatus: DlhdEventStreamHealth.Status,
        activeIndex: Int,
        mirrorIndex: Int,
        mirrorProbeStore: DlhdEventMirrorProbeStore?,
    ): Boolean {
        when (mirror.healthy) {
            true -> return true
            false -> return false
            null -> Unit
        }
        mirrorProbeStore?.isHealthy(eventKey, mirror.streamKey)?.let { return it }
        if (eventStatus == DlhdEventStreamHealth.Status.HEALTHY && mirrorIndex == activeIndex) {
            return true
        }
        return false
    }
}

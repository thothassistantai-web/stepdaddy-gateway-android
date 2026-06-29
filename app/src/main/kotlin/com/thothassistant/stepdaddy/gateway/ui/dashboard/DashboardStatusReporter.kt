package com.thothassistant.stepdaddy.gateway.ui.dashboard

import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.LoadProgress
import java.util.Locale

/**
 * Posts dashboard health milestones to [GatewayMessageBus] when load state changes.
 */
internal object DashboardStatusReporter {
    private var lastKey: String? = null

    fun onHealthUpdate(health: HealthResponse) {
        val progress = health.loadProgress ?: return
        val providers = health.providers
        val key = buildString {
            append(progress.channels.phase).append(':').append(progress.channels.percent)
            append('|').append(progress.programs.phase).append(':').append(progress.programs.percent)
            append('|').append(progress.sources.phase).append(':').append(progress.sources.percent)
            append('|').append(progress.status.phase)
            append('|').append(health.epgProgrammeCount)
            append('|').append(providers?.total ?: health.channels)
            append('|').append(health.supplement?.supplementSyncInFlight ?: false)
        }
        if (key == lastKey) return
        lastKey = key

        val lines = buildList {
            add(formatTile("Channels", progress.channels, providers?.total?.toString()))
            add(formatTile("Programs", progress.programs, health.epgProgrammeCount.toString()))
            add(formatTile("Sources", progress.sources, null))
            add(formatTile("Gateway", progress.status, null))
            providers?.let { stats ->
                add(
                    buildString {
                        append("Providers: DL ${stats.daddylive}")
                        if (stats.iptvOrg > 0) append(" · IPTV ${stats.iptvOrg}")
                        if (stats.sports > 0) append(" · Sports ${stats.sports}")
                        if (stats.ntvCx > 0) append(" · NTV ${stats.ntvCx}")
                        if (stats.adultSwim > 0) append(" · AS ${stats.adultSwim}")
                        append(" · total ${stats.total}")
                    },
                )
            }
            health.supplement?.let { sup ->
                if (sup.supplementSyncInFlight) {
                    add("Supplement sync in progress…")
                }
            }
        }
        GatewayMessageBus.post(lines.joinToString("\n"), "STATUS")
    }

    fun reset() {
        lastKey = null
    }

    private fun formatTile(label: String, progress: LoadProgress, readyCount: String?): String {
        val phase = progress.phase.replaceFirstChar { it.titlecase(Locale.US) }
        val detail = progress.detail?.takeIf { it.isNotBlank() }
        return when {
            progress.phase == "ready" && readyCount != null ->
                "$label ready · $readyCount${detail?.let { " · $it" } ?: ""}"
            detail != null ->
                "$label $phase ${progress.percent}% · $detail"
            else ->
                "$label $phase ${progress.percent}%"
        }
    }
}

package com.thothassistant.stepdaddy.gateway.ui.dashboard

import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgMeta
import com.thothassistant.stepdaddy.gateway.model.DashboardLoadProgress
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.LoadProgress
import kotlin.math.max
import kotlin.math.min

internal object DashboardLoadProgressCalculator {
    private const val CHANNEL_TARGET = 4_500
    private const val EPG_ESTIMATE_SECONDS = 720.0

    fun snapshot(
        health: HealthResponse,
        epgManager: EpgManager,
        gatewayOnline: Boolean,
        serviceActive: Boolean,
    ): DashboardLoadProgress = DashboardLoadProgress(
        channels = channelsProgress(health),
        programs = programsProgress(health, epgManager),
        sources = sourcesProgress(health),
        status = statusProgress(health, gatewayOnline, serviceActive),
    )

    fun channelsProgress(health: HealthResponse): LoadProgress {
        val total = health.providers?.total ?: health.channels
        if (total > 0) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "$total channels loaded",
            )
        }
        if (!health.ok) {
            return LoadProgress(phase = "idle", percent = 0, etaSeconds = null, detail = null)
        }
        val partial = health.channels + health.supplementChannels
        val percent = if (partial <= 0) {
            8
        } else {
            min(95, max(10, (partial * 100) / CHANNEL_TARGET))
        }
        val eta = if (partial <= 0) 90L else max(20L, ((CHANNEL_TARGET - partial) / 40).toLong())
        return LoadProgress(
            phase = "loading",
            percent = percent,
            etaSeconds = eta,
            detail = "Loading DaddyLive and supplement catalogs…",
        )
    }

    fun programsProgress(health: HealthResponse, epgManager: EpgManager): LoadProgress {
        if (!health.gatewayEpgEnabled) {
            val count = health.epgSourceCount
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = if (count > 0) "$count external EPG feed(s)" else "External EPG via playlist",
            )
        }
        if (health.epgReady && health.epgProgrammeCount > 0) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "${health.epgProgrammeCount} programmes in guide",
            )
        }
        val meta = epgManager.meta
        if (meta.state == "error") {
            return LoadProgress(
                phase = "error",
                percent = 0,
                etaSeconds = null,
                detail = meta.lastError ?: "EPG build failed",
            )
        }
        if (epgManager.isBuilding() || meta.state == "building" || meta.state == "pending") {
            return epgBuildingProgress(meta, epgManager.buildStartedAtMs)
        }
        if (health.epgProgrammeCount > 0) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "${health.epgProgrammeCount} programmes",
            )
        }
        return LoadProgress(
            phase = "building",
            percent = 5,
            etaSeconds = EPG_ESTIMATE_SECONDS.toLong(),
            detail = "EPG build queued…",
        )
    }

    private fun epgBuildingProgress(meta: EpgMeta, buildStartedAtMs: Long): LoadProgress {
        val elapsedSec = when {
            meta.buildSeconds > 0.0 -> meta.buildSeconds
            buildStartedAtMs > 0L -> (System.currentTimeMillis() - buildStartedAtMs) / 1000.0
            else -> 0.0
        }
        val percent = min(98, max(5, (elapsedSec / EPG_ESTIMATE_SECONDS * 100.0).toInt()))
        val eta = max(0L, (EPG_ESTIMATE_SECONDS - elapsedSec).toLong())
        val detail = when {
            meta.realProgrammeCount > 0 ->
                "Parsed ${meta.realProgrammeCount} programmes, writing guide…"
            meta.mappedTvgCount > 0 ->
                "Mapped ${meta.mappedTvgCount} channel ids…"
            else -> "Downloading and merging EPG feeds…"
        }
        return LoadProgress(
            phase = "building",
            percent = percent,
            etaSeconds = eta,
            detail = detail,
        )
    }

    fun sourcesProgress(health: HealthResponse): LoadProgress {
        val providers = health.providers
        val supplement = health.supplement
        if (providers == null) {
            return LoadProgress(phase = "loading", percent = 5, etaSeconds = 120L, detail = "Waiting for providers…")
        }
        val slots = buildList {
            add("DaddyLive" to providers.daddylive)
            if (supplement?.sidecarEnabled == true) add("MoveOnJoy" to providers.moveOnJoy)
            if (supplement?.sportsEnabled == true) add("Sports" to providers.sports)
            if (supplement?.iptvOrgEnabled == true) add("IPTV-org" to providers.iptvOrg)
            if (supplement?.ntvCxEnabled == true) add("NTV.cx" to providers.ntvCx)
            if (supplement?.adultSwimEnabled == true) add("Adult Swim" to providers.adultSwim)
        }
        if (slots.isEmpty()) {
            return LoadProgress(phase = "ready", percent = 100, etaSeconds = 0L, detail = "DaddyLive only")
        }
        val ready = slots.count { it.second > 0 }
        val total = slots.size
        if (ready >= total && providers.total > 0) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "$ready of $total supplement sources active",
            )
        }
        val percent = min(95, max(8, ready * 100 / total))
        val pending = slots.filter { it.second <= 0 }.joinToString(", ") { it.first }
        return LoadProgress(
            phase = "loading",
            percent = percent,
            etaSeconds = max(30L, (total - ready) * 45L),
            detail = "Syncing: $pending",
        )
    }

    fun statusProgress(
        health: HealthResponse,
        gatewayOnline: Boolean,
        serviceActive: Boolean,
    ): LoadProgress {
        when {
            gatewayOnline -> return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "Gateway serving playlist and streams",
            )
            serviceActive && health.ok -> {
                val total = health.providers?.total ?: health.channels
                val percent = when {
                    health.starting || total == 0 -> 35
                    !health.epgReady && health.gatewayEpgEnabled -> 75
                    else -> 90
                }
                return LoadProgress(
                    phase = "loading",
                    percent = percent,
                    etaSeconds = if (total == 0) 45L else 20L,
                    detail = if (health.starting) "Loading channels…" else "Finishing startup…",
                )
            }
            serviceActive -> return LoadProgress(
                phase = "loading",
                percent = 12,
                etaSeconds = 60L,
                detail = "Foreground service starting…",
            )
            else -> return LoadProgress(
                phase = "offline",
                percent = 0,
                etaSeconds = null,
                detail = "Gateway stopped",
            )
        }
    }
}

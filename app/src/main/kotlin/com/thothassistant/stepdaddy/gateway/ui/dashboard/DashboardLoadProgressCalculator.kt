package com.thothassistant.stepdaddy.gateway.ui.dashboard

import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgMeta
import com.thothassistant.stepdaddy.gateway.model.DashboardLoadProgress
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.LoadProgress
import com.thothassistant.stepdaddy.gateway.model.SupplementStatus
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
        val ready = health.providers?.playlistReady?.takeIf { it > 0 }
            ?: if (!health.starting && total > 0) total else health.channels + health.supplementChannels
        if (total > 0 && ready >= total) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "$ready / $total channels ready to stream",
            )
        }
        if (total > 0 && ready > 0) {
            val percent = min(99, max(10, (ready * 100) / total))
            return LoadProgress(
                phase = "loading",
                percent = percent,
                etaSeconds = max(15L, ((total - ready) / 40).toLong()),
                detail = "$ready / $total channels in playlist",
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
        if (health.epgProgrammeCount > 0) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = "${health.epgProgrammeCount} programmes (refreshing guide)",
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
            return LoadProgress(
                phase = "loading",
                percent = 5,
                etaSeconds = 120L,
                detail = "Waiting for provider stats…",
            )
        }

        val slots = buildList {
            add(sourceSlot("DaddyLive", daddylivePercent(providers.daddylive, health)))
            if (supplement?.sportsEnabled == true) {
                add(sourceSlot("Sports", sportsPercent(providers.sports, supplement)))
            }
            if (supplement?.iptvOrgEnabled == true) {
                add(sourceSlot("IPTV-org", iptvOrgPercent(providers.iptvOrg, supplement)))
            }
            if (supplement?.freeTvEnabled == true) {
                add(sourceSlot("Free-TV", freeTvPercent(providers.freeTv, supplement)))
            }
            if (supplement?.duloCxEnabled == true) {
                add(sourceSlot("Dulo Live", duloCxPercent(providers.duloCx, supplement)))
            }
            if (supplement?.ntvCxEnabled == true) {
                add(sourceSlot("NTV.cx", ntvCxPercent(providers.ntvCx, supplement)))
            }
            if (supplement?.adultSwimEnabled == true) {
                add(sourceSlot("Adult Swim", adultSwimPercent(providers.adultSwim, supplement)))
            }
        }

        if (slots.isEmpty()) {
            return LoadProgress(phase = "ready", percent = 100, etaSeconds = 0L, detail = "DaddyLive only")
        }

        val avgPercent = slots.map { it.percent }.average().toInt().coerceIn(0, 100)
        val allReady = slots.all { it.percent >= 100 }
        val syncInFlight = supplement?.supplementSyncInFlight == true
        if (allReady) {
            return LoadProgress(
                phase = "ready",
                percent = 100,
                etaSeconds = 0L,
                detail = if (syncInFlight) {
                    "${slots.size} sources synced · ${providers.total} channels (refreshing)"
                } else {
                    "${slots.size} sources synced · ${providers.total} channels"
                },
            )
        }

        val pending = slots.filter { it.percent < 100 }.joinToString(", ") { it.label }
        val syncing = slots.filter { it.percent in 1..99 }
        val detail = when {
            syncing.isNotEmpty() -> syncing.joinToString(" · ") { "${it.label} ${it.percent}%" }
            pending.isNotEmpty() -> "Syncing: $pending"
            else -> "Refreshing supplement catalogs…"
        }
        val remaining = slots.count { it.percent < 100 }
        return LoadProgress(
            phase = "loading",
            percent = max(8, avgPercent),
            etaSeconds = max(15L, remaining * 8L),
            detail = detail,
        )
    }

    private data class SourceSlot(val label: String, val percent: Int)

    private fun sourceSlot(label: String, percent: Int) = SourceSlot(label, percent.coerceIn(0, 100))

    private fun daddylivePercent(count: Int, health: HealthResponse): Int = when {
        count > 0 -> 100
        health.channels > 0 -> 100
        health.starting -> 12
        health.ok -> 55
        else -> 5
    }

    private fun sportsPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 -> 100
            supplement.sportsEventsScanned > 0 -> 100
            supplement.supplementSyncInFlight -> 50
            else -> 12
        }

    private fun iptvOrgPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 &&
                (supplement.iptvOrgPlaylistsTotal <= 0 ||
                    supplement.iptvOrgPlaylistsFetched >= supplement.iptvOrgPlaylistsTotal) &&
                !supplement.supplementSyncInFlight -> 100
            count > 0 && !supplement.supplementSyncInFlight -> 100
            supplement.iptvOrgPlaylistsTotal > 0 ->
                min(
                    99,
                    max(
                        5,
                        (supplement.iptvOrgPlaylistsFetched * 100) / supplement.iptvOrgPlaylistsTotal,
                    ),
                )
            supplement.iptvOrgPlaylistsFetched > 0 ->
                min(100, 40 + supplement.iptvOrgPlaylistsFetched * 2)
            count > 0 -> 85
            supplement.supplementSyncInFlight -> 35
            else -> 10
        }

    private fun freeTvPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 -> 100
            supplement.freeTvPlaylistsFetched > 0 ->
                min(100, 40 + supplement.freeTvPlaylistsFetched * 25)
            supplement.supplementSyncInFlight -> 35
            else -> 10
        }

    private fun duloCxPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 -> 100
            supplement.duloCxCatalogFetchOk -> 100
            supplement.supplementSyncInFlight -> 40
            else -> 10
        }

    private fun ntvCxPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 -> 100
            supplement.ntvCxResolveProbeOk -> 100
            supplement.supplementSyncInFlight -> 40
            else -> 10
        }

    private fun adultSwimPercent(count: Int, supplement: SupplementStatus): Int =
        when {
            count > 0 -> 100
            supplement.adultSwimProbed > 0 && supplement.adultSwimProbeOk >= supplement.adultSwimProbed -> 100
            supplement.adultSwimProbed > 0 ->
                min(95, 35 + (supplement.adultSwimProbeOk * 65 / supplement.adultSwimProbed))
            supplement.supplementSyncInFlight -> 35
            else -> 10
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
                detail = if (health.gatewayEpgEnabled && !health.epgReady) {
                    "Channels ready · EPG building in background"
                } else {
                    "Gateway serving playlist and streams"
                },
            )
            serviceActive && health.ok -> {
                val total = health.providers?.total ?: health.channels
                val percent = when {
                    health.starting || total == 0 -> 35
                    else -> 85
                }
                return LoadProgress(
                    phase = "loading",
                    percent = percent,
                    etaSeconds = if (total == 0) 45L else 15L,
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

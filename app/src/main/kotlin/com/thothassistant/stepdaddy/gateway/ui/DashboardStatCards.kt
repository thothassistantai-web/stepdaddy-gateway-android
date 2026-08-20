package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.DashboardLoadProgress
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardLoadProgressCalculator
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardStatCardView
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardStatType
import java.text.NumberFormat
import java.util.Locale

class DashboardStatCards(
    private val activity: AppCompatActivity,
    root: View,
) {
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val channelsCard = DashboardStatCardView(root.findViewById(R.id.statCardChannels)).apply {
        setIcon(R.drawable.ic_channels)
        setLabel(activity.getString(R.string.stat_channels))
    }
    private val programsCard = DashboardStatCardView(root.findViewById(R.id.statCardPrograms)).apply {
        setIcon(R.drawable.ic_programs)
        setLabel(activity.getString(R.string.stat_programs))
    }
    private val statusCard = DashboardStatCardView(root.findViewById(R.id.statCardStatus)).apply {
        setIcon(R.drawable.ic_status_check)
        setLabel(activity.getString(R.string.stat_status))
    }
    private val sourcesCard = DashboardStatCardView(root.findViewById(R.id.statCardSources)).apply {
        setIcon(R.drawable.ic_sources)
        setLabel(activity.getString(R.string.stat_sources))
    }

    fun bind(
        health: HealthResponse?,
        online: Boolean,
        serviceActive: Boolean,
    ) {
        if (health == null) {
            val idle = com.thothassistant.stepdaddy.gateway.model.LoadProgress(phase = "idle")
            channelsCard.bind(activity, idle, "—")
            programsCard.bind(activity, idle, "—")
            statusCard.bind(
                activity,
                com.thothassistant.stepdaddy.gateway.model.LoadProgress(
                    phase = if (serviceActive) "loading" else "offline",
                    percent = if (serviceActive) 10 else 0,
                    etaSeconds = if (serviceActive) 45L else null,
                ),
                if (serviceActive) activity.getString(R.string.status_starting_short) else activity.getString(R.string.status_offline),
            )
            sourcesCard.bind(activity, idle, "—")
            return
        }

        val progress = health.loadProgress ?: run {
            val app = activity.application as GatewayApp
            val epgManager = runCatching { app.epgManager }.getOrNull()
            if (epgManager != null) {
                DashboardLoadProgressCalculator.snapshot(health, epgManager, online, serviceActive)
            } else {
                null
            }
        }

        val total = health.providers?.total ?: health.channels
        val ready = health.providers?.playlistReady?.takeIf { it > 0 }
            ?: if (!health.starting && total > 0) total else health.channels + health.supplementChannels
        val channelsValue = if (total > 0) {
            "${numberFormat.format(ready)} / ${numberFormat.format(total)}"
        } else {
            numberFormat.format(0)
        }
        channelsCard.bind(activity, progress?.channels, channelsValue)
        programsCard.bind(
            activity,
            progress?.programs,
            numberFormat.format(health.epgProgrammeCount),
        )
        statusCard.bind(
            activity,
            progress?.status,
            when {
                online -> activity.getString(R.string.status_online)
                serviceActive -> activity.getString(R.string.status_loading_short)
                else -> activity.getString(R.string.status_offline)
            },
        )
        sourcesCard.bind(
            activity,
            progress?.sources,
            numberFormat.format(countActiveSources(health)),
        )
    }

    fun wireClicks() {
        channelsCard.root.setOnClickListener { openDetail(DashboardStatType.CHANNELS) }
        programsCard.root.setOnClickListener { openDetail(DashboardStatType.PROGRAMS) }
        statusCard.root.setOnClickListener { openDetail(DashboardStatType.STATUS) }
        sourcesCard.root.setOnClickListener { openDetail(DashboardStatType.SOURCES) }
    }

    fun wireFocus() {
        val cards = listOf(channelsCard.root, programsCard.root, statusCard.root, sourcesCard.root)
        channelsCard.root.nextFocusRightId = R.id.statCardPrograms
        programsCard.root.apply {
            nextFocusLeftId = R.id.statCardChannels
            nextFocusRightId = R.id.statCardStatus
        }
        statusCard.root.apply {
            nextFocusLeftId = R.id.statCardPrograms
            nextFocusRightId = R.id.statCardSources
        }
        sourcesCard.root.nextFocusLeftId = R.id.statCardStatus
        cards.forEach { card ->
            card.nextFocusDownId = R.id.buttonToggleServer
            card.isFocusableInTouchMode = true
            card.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    view.performClick()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun openDetail(type: DashboardStatType) {
        activity.startActivity(
            Intent(activity, DashboardStatDetailActivity::class.java)
                .putExtra(DashboardStatDetailActivity.EXTRA_STAT_TYPE, type.name),
        )
    }

    private fun countActiveSources(health: HealthResponse): Int {
        var count = 1
        val supplement = health.supplement
        if (supplement != null) {
            if (supplement.sportsEnabled) count++
            if (supplement.iptvOrgEnabled) count++
            if (supplement.freeTvEnabled) count++
            if (supplement.ntvCxEnabled) count++
            if (supplement.adultSwimEnabled) count++
        } else if (health.supplementEnabled) {
            count++
        }
        return count
    }
}

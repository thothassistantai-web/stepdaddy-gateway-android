package com.thothassistant.stepdaddy.gateway.ui.dashboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.LoadProgress
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashboardStatCardView(root: View) {
    val root: View = root.findViewById(R.id.statCardRoot)
    private val icon: ImageView = root.findViewById(R.id.statCardIcon)
    private val value: TextView = root.findViewById(R.id.statCardValue)
    private val label: TextView = root.findViewById(R.id.statCardLabel)
    private val progressTrack: FrameLayout = root.findViewById(R.id.statCardProgressTrack)
    private val progressFill: View = root.findViewById(R.id.statCardProgressFill)
    private val eta: TextView = root.findViewById(R.id.statCardEta)

    fun setIcon(resId: Int) {
        icon.setImageResource(resId)
    }

    fun setLabel(text: String) {
        label.text = text
    }

    fun bind(context: Context, progress: LoadProgress?, readyValue: String) {
        val phase = progress?.phase ?: "idle"
        val showBar = phase in LOADING_PHASES
        value.text = when {
            showBar && phase == "building" -> context.getString(R.string.stat_value_building)
            showBar -> context.getString(R.string.stat_value_loading)
            phase == "error" -> context.getString(R.string.stat_value_error)
            phase == "offline" -> context.getString(R.string.stat_value_offline)
            else -> readyValue
        }
        val valueColor = when (phase) {
            "ready" -> R.color.status_ok
            "error", "offline" -> R.color.status_error
            "building", "loading" -> R.color.status_warn
            else -> R.color.on_background
        }
        value.setTextColor(ContextCompat.getColor(context, valueColor))
        icon.setColorFilter(ContextCompat.getColor(context, valueColor))

        if (showBar) {
            val percent = (progress?.percent ?: 0).coerceIn(0, 100)
            progressTrack.visibility = View.VISIBLE
            progressTrack.post {
                val trackWidth = progressTrack.width
                if (trackWidth > 0) {
                    val lp = progressFill.layoutParams as FrameLayout.LayoutParams
                    lp.width = (trackWidth * percent / 100f).toInt().coerceAtLeast(if (percent > 0) 4 else 0)
                    progressFill.layoutParams = lp
                }
            }
            val fillColor = if (phase == "building") R.color.status_warn else R.color.status_ok
            progressFill.setBackgroundResource(
                if (phase == "building") R.drawable.bg_progress_fill else R.drawable.bg_progress_fill,
            )
            progressFill.backgroundTintList = ContextCompat.getColorStateList(context, fillColor)
            val etaText = formatEta(context, progress)
            if (etaText != null) {
                eta.visibility = View.VISIBLE
                eta.text = etaText
            } else {
                eta.visibility = View.GONE
            }
        } else {
            progressTrack.visibility = View.GONE
            eta.visibility = View.GONE
        }
    }

    private fun formatEta(context: Context, progress: LoadProgress?): String? {
        val seconds = progress?.etaSeconds ?: return progress?.detail
        if (seconds <= 0L) return progress.detail
        val mins = TimeUnit.SECONDS.toMinutes(seconds)
        val secs = seconds % 60
        val time = if (mins > 0) {
            String.format(Locale.US, "%d:%02d", mins, secs)
        } else {
            String.format(Locale.US, "0:%02d", secs)
        }
        val pct = progress.percent.coerceIn(0, 100)
        return context.getString(R.string.stat_eta_format, pct, time)
    }

    companion object {
        private val LOADING_PHASES = setOf("loading", "building")
    }
}

enum class DashboardStatType {
    CHANNELS,
    PROGRAMS,
    STATUS,
    SOURCES,
}

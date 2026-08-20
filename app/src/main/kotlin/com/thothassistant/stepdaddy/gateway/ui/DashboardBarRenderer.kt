package com.thothassistant.stepdaddy.gateway.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.CategoryCount
import com.thothassistant.stepdaddy.gateway.model.ProviderStats
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

object DashboardBarRenderer {
    private val providerColors = intArrayOf(
        R.color.bar_purple,
        R.color.bar_blue,
        R.color.bar_teal,
        R.color.bar_orange,
        R.color.bar_pink,
    )

    private val categoryColors = intArrayOf(
        R.color.bar_teal,
        R.color.bar_blue,
        R.color.bar_purple,
        R.color.bar_orange,
        R.color.bar_pink,
        R.color.primary,
        R.color.status_warn,
        R.color.status_ok,
    )

    fun renderProviders(
        context: Context,
        container: LinearLayout,
        providers: ProviderStats?,
    ) {
        container.removeAllViews()
        if (providers == null) {
            addPlaceholder(context, container, context.getString(R.string.dashboard_providers_empty))
            return
        }
        val rows = listOf(
            context.getString(R.string.provider_daddylive) to providers.daddylive,
            context.getString(R.string.provider_iptv_org) to providers.iptvOrg,
            context.getString(R.string.provider_free_tv) to providers.freeTv,
            context.getString(R.string.provider_ntv_cx) to providers.ntvCx,
            context.getString(R.string.provider_adult_swim) to providers.adultSwim,
            context.getString(R.string.provider_sports) to providers.sports,
            context.getString(R.string.provider_adult) to providers.adult,
        )
        val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        val inflater = LayoutInflater.from(context)
        rows.forEachIndexed { index, (label, count) ->
            val row = inflater.inflate(R.layout.view_bar_row, container, false)
            bindBarRow(
                context = context,
                row = row,
                label = label,
                count = count,
                max = max,
                colorRes = providerColors[index % providerColors.size],
                showPercent = false,
            )
            container.addView(row)
        }
    }

    fun renderCategories(
        context: Context,
        container: LinearLayout,
        categories: List<CategoryCount>,
    ) {
        container.removeAllViews()
        if (categories.isEmpty()) {
            addPlaceholder(context, container, context.getString(R.string.dashboard_categories_empty))
            return
        }
        val total = categories.sumOf { it.count }.coerceAtLeast(1)
        val inflater = LayoutInflater.from(context)
        categories.take(8).forEachIndexed { index, category ->
            val row = inflater.inflate(R.layout.view_bar_row, container, false)
            bindBarRow(
                context = context,
                row = row,
                label = category.groupTitle,
                count = category.count,
                max = total,
                colorRes = categoryColors[index % categoryColors.size],
                showPercent = true,
                total = total,
            )
            container.addView(row)
        }
    }

    private fun bindBarRow(
        context: Context,
        row: View,
        label: String,
        count: Int,
        max: Int,
        colorRes: Int,
        showPercent: Boolean,
        total: Int = max,
    ) {
        val formatter = NumberFormat.getIntegerInstance(Locale.US)
        row.findViewById<TextView>(R.id.textBarLabel).text = label
        val valueView = row.findViewById<TextView>(R.id.textBarValue)
        valueView.text = if (showPercent) {
            val pct = (count * 100.0 / total).roundToInt()
            context.getString(R.string.dashboard_category_value, formatter.format(count), pct)
        } else {
            formatter.format(count)
        }
        val fill = row.findViewById<View>(R.id.viewBarFill)
        val track = row.findViewById<View>(R.id.viewBarTrack)
        track.post {
            val trackWidth = track.width
            if (trackWidth <= 0) return@post
            val fillWidth = (trackWidth * count.toFloat() / max).roundToInt().coerceAtLeast(if (count > 0) 4 else 0)
            val params = fill.layoutParams
            params.width = fillWidth
            fill.layoutParams = params
            fill.background = GradientDrawable().apply {
                cornerRadius = 4f * context.resources.displayMetrics.density
                setColor(ContextCompat.getColor(context, colorRes))
            }
        }
    }

    private fun addPlaceholder(context: Context, container: LinearLayout, text: String) {
        val view = TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.on_background_muted))
            textSize = 12f
        }
        container.addView(view)
    }
}

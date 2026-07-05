package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import com.thothassistant.stepdaddy.gateway.FireTvDevice
import com.thothassistant.stepdaddy.gateway.LowRamTvDevice

/** Device-RAM tier caps for VOD movie and episode catalogs. */
object VodCatalogLimits {
    const val FIRE_CAP = 150
    const val ONN_CAP = 250
    const val FULL_CAP = 300

    fun movieCap(context: Context): Int = tierCap(context)

    fun seriesCap(context: Context): Int = tierCap(context)

    fun cinemetaEnrichCap(context: Context): Int = tierCap(context)

    /** vsembed list JSON pages (~50 titles/page). */
    fun vsembedMoviePages(context: Context): Int = when {
        FireTvDevice.isFireTv(context) -> 3
        LowRamTvDevice.isOnnStick(context) -> 5
        else -> 6
    }

    fun vsembedSeriesPages(context: Context): Int = vsembedMoviePages(context)

    private fun tierCap(context: Context): Int = when {
        FireTvDevice.isFireTv(context) -> FIRE_CAP
        LowRamTvDevice.isOnnStick(context) -> ONN_CAP
        else -> FULL_CAP
    }
}

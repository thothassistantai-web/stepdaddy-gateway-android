package com.thothassistant.stepdaddy.gateway

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Low-RAM Android TV stick detection for shared memory-lite / LMK-survival paths.
 *
 * Covers Fire Stick (via [FireTvDevice]), Onn / Walmart sticks, and any leanback TV
 * reporting low RAM or under [LOW_RAM_BYTES]. Phones and tablets are never matched.
 */
object LowRamTvDevice {
    /** ~1.5 GiB — Onn Full HD (~1.4 GiB) and Fire Stick (~0.9 GiB) both fall under this. */
    private const val LOW_RAM_BYTES = 1536L * 1024L * 1024L

    @Volatile
    private var cached: Boolean? = null

    fun needsMemoryLite(context: Context): Boolean {
        cached?.let { return it }
        val result = compute(context.applicationContext)
        cached = result
        return result
    }

    private fun compute(context: Context): Boolean {
        if (FireTvDevice.isFireTv(context)) return true
        if (!isAndroidTv(context)) return false
        if (isKnownLowRamStick()) return true
        return isLowRamHardware(context)
    }

    private fun isAndroidTv(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        @Suppress("DEPRECATION")
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }

    private fun isKnownLowRamStick(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val device = Build.DEVICE.orEmpty()
        // Onn / Walmart streaming sticks (e.g. onn_2k_gtv / XNA).
        if (containsIgnoreCase(manufacturer, "onn") || containsIgnoreCase(brand, "onn")) return true
        if (containsIgnoreCase(model, "onn") || containsIgnoreCase(product, "onn")) return true
        if (device.equals("XNA", ignoreCase = true)) return true
        if (containsIgnoreCase(manufacturer, "walmart") || containsIgnoreCase(brand, "walmart")) {
            return true
        }
        return false
    }

    private fun isLowRamHardware(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem in 1 until LOW_RAM_BYTES
    }

    private fun containsIgnoreCase(value: String, needle: String): Boolean =
        value.contains(needle, ignoreCase = true)
}

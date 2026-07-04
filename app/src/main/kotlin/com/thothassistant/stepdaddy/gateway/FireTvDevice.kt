package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Amazon Fire TV / Fire Stick detection for Fire-OS-specific quirks
 * (boot delay, Amazon network wait, notification channel importance).
 * Shared low-RAM / LMK survival lives in [LowRamTvDevice] / [FireMemoryGuard].
 */
object FireTvDevice {
    fun isFireTv(context: Context? = null): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val model = Build.MODEL.orEmpty()
        if (manufacturer.equals("Amazon", ignoreCase = true)) return true
        if (brand.equals("Amazon", ignoreCase = true)) return true
        if (model.startsWith("AFT", ignoreCase = true)) return true
        val pm = context?.packageManager ?: return false
        return pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            pm.hasSystemFeature("com.amazon.software.fireos") ||
            (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                manufacturer.contains("amazon", ignoreCase = true))
    }
}

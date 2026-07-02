package com.thothassistant.stepdaddy.gateway.ui.player

import android.content.Context
import android.content.pm.PackageManager

object PlayerDeviceProfile {
    fun isTvDevice(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isPhoneLayout(context: Context): Boolean =
        !isTvDevice(context) && context.resources.configuration.smallestScreenWidthDp < 600
}

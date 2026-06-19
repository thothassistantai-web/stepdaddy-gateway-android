package com.nova.stepdaddylivehd.gateway.ui.player

import android.content.Context
import android.content.pm.PackageManager

object PlayerDeviceProfile {
    fun isTvDevice(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.content.Intent
import android.util.Log

object TiviMateLauncher {
    fun launch(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TiviMateWatch.PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "TiviMate not installed (${TiviMateWatch.PACKAGE})")
            return false
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched TiviMate")
            true
        }.getOrElse { exc ->
            Log.w(TAG, "TiviMate launch failed: ${exc.message}")
            false
        }
    }

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(TiviMateWatch.PACKAGE) != null

    private const val TAG = "TiviMateLauncher"
}

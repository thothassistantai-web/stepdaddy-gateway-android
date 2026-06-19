package com.thothassistant.stepdaddy.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            Log.i(TAG, "Package replaced; startOnBoot disabled")
            return
        }
        if (GatewayStartHelper.isGatewayHealthy(appContext)) {
            Log.i(TAG, "Package replaced; gateway already healthy, skipping restart churn")
            return
        }
        val result = GatewayStartHelper.startIfNeeded(appContext, "PackageReplaced", allowReschedule = false)
        Log.i(TAG, "Package replaced start result: $result")
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }
}

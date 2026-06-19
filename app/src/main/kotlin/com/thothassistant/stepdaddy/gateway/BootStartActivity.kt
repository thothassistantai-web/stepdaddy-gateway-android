package com.thothassistant.stepdaddy.gateway

import android.os.Bundle
import android.util.Log
import android.app.Activity
/**
 * Transparent trampoline activity — starting an activity puts the app in the foreground,
 * which allows [ServerService] to be promoted on Android 12+ where background FGS starts
 * are blocked (common on delayed BOOT_COMPLETED TV sticks).
 */
class BootStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "BootStartActivity"
        Log.i(TAG, "Trampoline starting gateway ($source)")
        val environment = (application as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            Log.i(TAG, "startOnBoot disabled; finishing")
            finish()
            return
        }
        val result = GatewayStartHelper.startIfNeeded(this, "Trampoline:$source", allowReschedule = false)
        Log.i(TAG, "Trampoline start result: $result")
        finish()
    }

    companion object {
        private const val TAG = "BootStartActivity"
        const val EXTRA_SOURCE = "source"
    }
}

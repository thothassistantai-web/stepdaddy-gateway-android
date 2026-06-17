package com.nova.stepdaddylivehd.gateway

import android.os.Bundle
import android.util.Log
import android.app.Activity
import androidx.core.content.ContextCompat

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
        if (ServerService.isServiceActive) {
            Log.i(TAG, "Server already active; finishing")
            finish()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, ServerService::class.java),
            )
            Log.i(TAG, "Foreground service started from trampoline ($source)")
        }.onFailure { exc ->
            Log.e(TAG, "Trampoline FGS start failed ($source)", exc)
        }
        finish()
    }

    companion object {
        private const val TAG = "BootStartActivity"
        const val EXTRA_SOURCE = "source"
    }
}

package com.nova.stepdaddylivehd.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

class BootAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GatewayStartHelper.ACTION_BOOT_ALARM) return
        val alarmIndex = intent.getIntExtra(GatewayStartHelper.EXTRA_ALARM_INDEX, 0)
        val pendingResult = goAsync()
        val wakeLock = acquireWakeLock(context)
        alarmExecutor.execute {
            try {
                val result = GatewayStartHelper.startIfNeeded(context, "Alarm#$alarmIndex")
                Log.i(TAG, "Alarm#$alarmIndex result: $result")
                if (GatewayStartHelper.isGatewayHealthy(context)) {
                    GatewayStartHelper.cancelBootFallbacks(context)
                }
            } finally {
                releaseWakeLock(wakeLock)
                pendingResult.finish()
            }
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        }.getOrNull()

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    companion object {
        private const val TAG = "BootAlarmReceiver"
        private const val WAKE_LOCK_TAG = "StepDaddy::BootAlarm"
        private const val WAKE_LOCK_MS = 60_000L
        private val alarmExecutor = Executors.newSingleThreadExecutor()
    }
}

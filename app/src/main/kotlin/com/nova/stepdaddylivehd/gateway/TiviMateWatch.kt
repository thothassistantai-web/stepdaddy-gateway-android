package com.nova.stepdaddylivehd.gateway

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Detects whether TiviMate is likely in use so redundancy kicks can prioritize
 * gateway recovery before playlist/stream requests fail.
 *
 * Uses running-process scan (no extra permission) and optional UsageStats when
 * the user grants "Usage access" in system settings.
 */
object TiviMateWatch {
    const val PACKAGE = "ar.tvplayer.tv"

    fun isTiviMateLikelyActive(context: Context): Boolean {
        if (isProcessRunning(context)) {
            return true
        }
        if (hasUsageStatsPermission(context)) {
            return isRecentForegroundViaUsageStats(context)
        }
        return false
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - TimeUnit.HOURS.toMillis(1),
            now,
        )
        return stats?.isNotEmpty() == true
    }

    private fun isProcessRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false
        return processes.any { process ->
            process.processName == PACKAGE || process.processName.startsWith("$PACKAGE:")
        }
    }

    private fun isRecentForegroundViaUsageStats(context: Context): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val now = System.currentTimeMillis()
        val windowMs = TimeUnit.MINUTES.toMillis(5)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val events = usageStatsManager.queryEvents(now - windowMs, now)
            val event = UsageEvents.Event()
            var lastForegroundMs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != PACKAGE) continue
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    lastForegroundMs = event.timeStamp
                }
            }
            if (lastForegroundMs > 0L) {
                return true
            }
        }
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - windowMs,
            now,
        ) ?: return false
        return stats.any { stat ->
            stat.packageName == PACKAGE && stat.lastTimeUsed >= now - windowMs
        }
    }

    private const val TAG = "TiviMateWatch"
}

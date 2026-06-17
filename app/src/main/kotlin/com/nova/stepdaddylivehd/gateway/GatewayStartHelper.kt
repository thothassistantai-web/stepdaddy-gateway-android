package com.nova.stepdaddylivehd.gateway

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GatewayStartHelper {
    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        SKIPPED_DISABLED,
        LAUNCHED_TRAMPOLINE,
        SCHEDULED_FALLBACK,
    }

    private val fallbacksScheduled = AtomicBoolean(false)
    private val bootExecutor = Executors.newSingleThreadExecutor()

    fun startIfNeeded(context: Context, source: String, allowReschedule: Boolean = false): StartResult {
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            Log.i(TAG, "startOnBoot disabled, skipping ($source)")
            return StartResult.SKIPPED_DISABLED
        }
        if (ServerService.isServiceActive) {
            Log.i(TAG, "Server already active ($source)")
            cancelBootFallbacks(appContext)
            return StartResult.ALREADY_RUNNING
        }

        tryStartForegroundService(appContext, source)?.let {
            if (it == StartResult.STARTED) {
                cancelBootFallbacks(appContext)
            }
            return it
        }
        tryStartBackgroundService(appContext, source)

        if (!ServerService.isServiceActive) {
            launchBootStartActivity(appContext, source)
            scheduleBootFallbacksAsync(appContext)
            return StartResult.LAUNCHED_TRAMPOLINE
        }

        if (allowReschedule) {
            scheduleBootFallbacksOnce(appContext)
        } else {
            scheduleBootFallbacksAsync(appContext)
        }
        return StartResult.SCHEDULED_FALLBACK
    }

    fun scheduleBootFallbacks(context: Context) {
        scheduleBootFallbacksOnce(context.applicationContext)
    }

    fun scheduleBootFallbacksAsync(context: Context) {
        bootExecutor.execute { scheduleBootFallbacksOnce(context.applicationContext) }
    }

    private fun scheduleBootFallbacksOnce(context: Context) {
        if (!fallbacksScheduled.compareAndSet(false, true)) {
            Log.i(TAG, "Boot fallbacks already scheduled; skipping duplicate")
            return
        }
        scheduleWorkManager(context)
        scheduleAlarms(context)
    }

    private fun tryStartForegroundService(context: Context, source: String): StartResult? {
        return try {
            val serviceIntent = Intent(context, ServerService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "Foreground service started ($source)")
            StartResult.STARTED
        } catch (exc: Exception) {
            Log.w(TAG, "Direct FGS start failed ($source): ${exc.message}")
            if (isFgsStartBlocked(exc)) {
                launchBootStartActivity(context, source)
                return StartResult.LAUNCHED_TRAMPOLINE
            }
            null
        }
    }

    private fun isFgsStartBlocked(exc: Throwable): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && exc is ForegroundServiceStartNotAllowedException) {
            return true
        }
        val cause = exc.cause
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            cause is ForegroundServiceStartNotAllowedException
        ) {
            return true
        }
        return exc.message?.contains("ForegroundServiceStartNotAllowed", ignoreCase = true) == true
    }

    private fun tryStartBackgroundService(context: Context, source: String) {
        runCatching {
            context.startService(Intent(context, ServerService::class.java))
            Log.i(TAG, "Background service start requested ($source)")
        }.onFailure { serviceExc ->
            Log.w(TAG, "Background service start failed ($source): ${serviceExc.message}")
        }
    }

    private fun launchBootStartActivity(context: Context, source: String) {
        val activityIntent = Intent(context, BootStartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BootStartActivity.EXTRA_SOURCE, source)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_CODE_TRAMPOLINE,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                pendingIntent.send(
                    context,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options.toBundle(),
                )
            } else {
                context.startActivity(activityIntent)
            }
            Log.i(TAG, "Launched boot trampoline activity ($source)")
        }.onFailure { exc ->
            Log.e(TAG, "Failed to launch boot trampoline ($source)", exc)
        }
    }

    private fun scheduleWorkManager(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val expedited = OneTimeWorkRequestBuilder<BootStartWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_TAG_BOOT_START)
            .build()
        workManager.enqueueUniqueWork(
            "$UNIQUE_BOOT_START_WORK-expedited-30s",
            ExistingWorkPolicy.KEEP,
            expedited,
        )
        val delaysMinutes = listOf(1L, 2L, 3L)
        delaysMinutes.forEach { delay ->
            val request = OneTimeWorkRequestBuilder<BootStartWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .addTag(WORK_TAG_BOOT_START)
                .build()
            workManager.enqueueUniqueWork(
                "$UNIQUE_BOOT_START_WORK-$delay",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        Log.i(TAG, "Scheduled expedited WorkManager retry at 30s + ${delaysMinutes.size} minute retries")
    }

    private fun scheduleAlarms(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        ALARM_DELAYS_MS.forEachIndexed { index, delayMs ->
            val intent = Intent(context, BootAlarmReceiver::class.java).apply {
                action = ACTION_BOOT_ALARM
                putExtra(EXTRA_ALARM_INDEX, index)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ALARM_BASE + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            }.onFailure { exc ->
                Log.w(TAG, "Exact alarm $index failed, using inexact: ${exc.message}")
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }
        Log.i(TAG, "Scheduled ${ALARM_DELAYS_MS.size} alarm boot retries")
    }

    private const val TAG = "GatewayStartHelper"
    const val ACTION_BOOT_ALARM = "com.nova.stepdaddylivehd.gateway.action.BOOT_ALARM"
    const val EXTRA_ALARM_INDEX = "alarm_index"
    private const val UNIQUE_BOOT_START_WORK = "boot_start_gateway"
    private const val WORK_TAG_BOOT_START = "boot_start"
    private const val REQUEST_CODE_ALARM_BASE = 30_000
    private const val REQUEST_CODE_TRAMPOLINE = 30_100
    private val ALARM_DELAYS_MS = longArrayOf(8_000, 20_000, 40_000, 80_000)

    fun resetFallbacksScheduled() {
        fallbacksScheduled.set(false)
    }

    fun cancelBootFallbacks(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelAllWorkByTag(WORK_TAG_BOOT_START)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        ALARM_DELAYS_MS.indices.forEach { index ->
            val intent = Intent(appContext, BootAlarmReceiver::class.java).apply {
                action = ACTION_BOOT_ALARM
                putExtra(EXTRA_ALARM_INDEX, index)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE_ALARM_BASE + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
        }
        Log.i(TAG, "Cancelled pending boot fallbacks")
    }
}

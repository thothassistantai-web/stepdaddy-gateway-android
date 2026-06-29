package com.thothassistant.stepdaddy.gateway

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single coordinated entry for every auto-start path (boot, wake, periodic, package
 * replace, application process start). Each caller passes a [source] tag for logcat.
 */
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

    /** FGS alive AND loopback catalog is serving channels (not merely HTTP up). */
    fun isGatewayHealthy(context: Context): Boolean {
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!ServerService.isServiceActive) {
            if (environment.serverRunning) {
                environment.clearBootStaleState()
            }
            GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.COLD)
            return false
        }
        val snapshot = GatewayHealth.probeReadiness(appContext)
        if (snapshot.ready) {
            environment.serverRunning = true
            GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.READY)
            return true
        }
        if (environment.serverRunning) {
            environment.clearBootStaleState()
        }
        GatewayHealth.setReadinessPhase(
            if (snapshot.healthOk) {
                GatewayHealth.ReadinessPhase.WAITING_CHANNELS
            } else {
                GatewayHealth.ReadinessPhase.WAKING
            },
        )
        return false
    }

    /**
     * Wake FGS (and trampoline activity if needed), then poll until /health?lite=1 reports
     * channels>0 and /tivimate-setup returns a playlist URL — stable across two probes.
     */
    suspend fun ensureGatewayReady(
        context: Context,
        timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
        pollMs: Long = DEFAULT_READY_POLL_MS,
    ): Boolean {
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.WAKING)
        Log.i(TAG, "ensureGatewayReady: waking gateway (timeout=${timeoutMs}ms)")

        if (!ServerService.isServiceActive) {
            startIfNeeded(appContext, "ensureGatewayReady")
        } else if (!environment.serverRunning) {
            nudgeRunningService(appContext)
        }

        var stableHits = 0
        var lastChannelCount = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!ServerService.isServiceActive) {
                startIfNeeded(appContext, "ensureGatewayReady-retry")
            } else if (!GatewayHealth.probeReadiness(appContext).healthOk) {
                nudgeRunningService(appContext)
                launchBootStartActivity(appContext, "ensureGatewayReady")
            }

            val snapshot = GatewayHealth.probeReadiness(appContext)
            if (snapshot.ready) {
                if (snapshot.channelCount == lastChannelCount) {
                    stableHits++
                } else {
                    stableHits = 1
                    lastChannelCount = snapshot.channelCount
                }
                if (stableHits >= STABLE_READY_PROBES) {
                    environment.serverRunning = true
                    GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.READY)
                    onGatewayHealthy(appContext)
                    val elapsed = timeoutMs - (deadline - SystemClock.elapsedRealtime())
                    Log.i(
                        TAG,
                        "ensureGatewayReady: ready in ${elapsed.coerceAtLeast(0)}ms " +
                            "(channels=${snapshot.channelCount})",
                    )
                    return true
                }
            } else {
                stableHits = 0
                lastChannelCount = -1
                GatewayHealth.setReadinessPhase(
                    if (snapshot.healthOk) {
                        GatewayHealth.ReadinessPhase.WAITING_CHANNELS
                    } else {
                        GatewayHealth.ReadinessPhase.WAKING
                    },
                )
            }
            delay(pollMs)
        }

        GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.TIMEOUT)
        Log.w(TAG, "ensureGatewayReady: timed out after ${timeoutMs}ms")
        return false
    }

    fun startIfNeeded(context: Context, source: String, allowReschedule: Boolean = false): StartResult {
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            Log.i(TAG, "startOnBoot disabled, skipping ($source)")
            cancelBootFallbacks(appContext)
            cancelPeriodicEnsureAlive(appContext)
            return StartResult.SKIPPED_DISABLED
        }
        if (isGatewayHealthy(appContext)) {
            Log.i(TAG, "Gateway already healthy ($source)")
            onGatewayHealthy(appContext)
            return StartResult.ALREADY_RUNNING
        }
        if (ServerService.isServiceActive && !environment.serverRunning) {
            Log.w(TAG, "Service active but HTTP down; nudging startGateway ($source)")
            nudgeRunningService(appContext)
        }

        val fgsResult = tryStartForegroundService(appContext, source)
        when (fgsResult) {
            StartResult.STARTED -> {
                return StartResult.STARTED
            }
            StartResult.LAUNCHED_TRAMPOLINE -> {
                scheduleBootFallbacksAsync(appContext)
                return StartResult.LAUNCHED_TRAMPOLINE
            }
            else -> Unit
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

    /** Called when FGS is up and HTTP /health is OK — drop boot retries, enable WM keep-alive. */
    private fun onGatewayHealthy(context: Context) {
        cancelBootFallbacks(context)
        schedulePeriodicEnsureAlive(context)
        scheduleWorkManagerBootCatchup(context)
    }

    /** Deferred WM retries — safe after the boot ANR window has passed. */
    private fun scheduleWorkManagerBootCatchup(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<BootStartWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .addTag(WORK_TAG_BOOT_START)
            .build()
        workManager.enqueueUniqueWork(
            "$UNIQUE_BOOT_START_WORK-catchup-2m",
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "Scheduled deferred WorkManager catchup at 2m")
    }

    private fun nudgeRunningService(context: Context) {
        runCatching {
            context.startService(
                Intent(context, ServerService::class.java).apply {
                    action = ServerService.ACTION_ENSURE_GATEWAY
                },
            )
        }.onFailure { exc ->
            Log.w(TAG, "Failed to nudge running service: ${exc.message}")
        }
    }

    fun scheduleBootFallbacks(context: Context) {
        scheduleBootFallbacksOnce(context.applicationContext)
    }

    fun scheduleBootFallbacksAsync(context: Context) {
        bootExecutor.execute { scheduleBootFallbacksOnce(context.applicationContext) }
    }

    /**
     * Alarm-only retries during boot — WorkManager binding during BOOT_COMPLETED
     * ANRs on FUSA sticks (SystemJobService bind timeout). WM is scheduled once healthy.
     */
    private fun scheduleBootFallbacksOnce(context: Context) {
        if (!fallbacksScheduled.compareAndSet(false, true)) {
            Log.i(TAG, "Boot fallbacks already scheduled; skipping duplicate")
            return
        }
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

    /**
     * Transparent activity trampoline — Android 12+ blocks background FGS starts;
     * a visible (even zero-UI) activity satisfies the foreground requirement.
     */
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

    /** Exact elapsed-realtime alarms — survive Doze better than inexact WM on some TV sticks. */
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

    /** Long-horizon keep-alive when start-on-boot is enabled (TiviMate redundancy). */
    fun schedulePeriodicEnsureAlive(context: Context) {
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            cancelPeriodicEnsureAlive(appContext)
            return
        }
        val periodMinutes = if (environment.tivimateWatchEnabled) {
            PERIODIC_ENSURE_ALIVE_TIVIMATE_MINUTES
        } else {
            PERIODIC_ENSURE_ALIVE_MINUTES
        }
        val request = PeriodicWorkRequestBuilder<GatewayEnsureAliveWorker>(
            periodMinutes,
            TimeUnit.MINUTES,
        )
            .addTag(WORK_TAG_ENSURE_ALIVE)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_ENSURE_ALIVE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "Scheduled periodic ensure-alive every ${periodMinutes}m")
    }

    fun cancelPeriodicEnsureAlive(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_ENSURE_ALIVE_WORK)
        Log.i(TAG, "Cancelled periodic ensure-alive")
    }

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
        fallbacksScheduled.set(false)
        Log.i(TAG, "Cancelled pending boot fallbacks")
    }

    private const val TAG = "GatewayStartHelper"
    const val ACTION_BOOT_ALARM = "com.thothassistant.stepdaddy.gateway.action.BOOT_ALARM"
    const val EXTRA_ALARM_INDEX = "alarm_index"
    private const val UNIQUE_BOOT_START_WORK = "boot_start_gateway"
    private const val UNIQUE_ENSURE_ALIVE_WORK = "gateway_ensure_alive"
    private const val WORK_TAG_BOOT_START = "boot_start"
    private const val WORK_TAG_ENSURE_ALIVE = "ensure_alive"
    private const val REQUEST_CODE_ALARM_BASE = 30_000
    private const val REQUEST_CODE_TRAMPOLINE = 30_100
    private const val PERIODIC_ENSURE_ALIVE_MINUTES = 20L
    private const val PERIODIC_ENSURE_ALIVE_TIVIMATE_MINUTES = 5L
    private const val DEFAULT_READY_TIMEOUT_MS = 120_000L
    private const val DEFAULT_READY_POLL_MS = 2_000L
    private const val STABLE_READY_PROBES = 2
    private val ALARM_DELAYS_MS = longArrayOf(
        3_000, 8_000, 15_000, 30_000, 60_000, 120_000, 240_000,
    )
}

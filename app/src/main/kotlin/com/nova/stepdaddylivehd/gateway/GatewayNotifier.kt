package com.nova.stepdaddylivehd.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nova.stepdaddylivehd.gateway.ui.MainActivity

/**
 * Centralizes the low-key FGS notification (id 3000) and high-priority cross-app alerts (3001+).
 * Alerts use [CHANNEL_ALERTS] so heads-up banners fire separately from the ongoing service notification.
 */
object GatewayNotifier {
    const val NOTIFICATION_ID_ONGOING = 3000
    const val NOTIFICATION_ID_ALERT_STARTED = 3001
    const val NOTIFICATION_ID_ALERT_ERROR = 3002

    private const val CHANNEL_ONGOING = "stepdaddy_gateway"
    private const val CHANNEL_ALERTS = "stepdaddy_alerts"
    private const val CHANNEL_ERRORS = "stepdaddy_gateway_errors"

    private const val GROUP_ONGOING = "stepdaddy_service"
    private const val GROUP_ALERTS = "stepdaddy_alerts"
    /** Long enough to find in the TV notification shade after returning from another app. */
    private const val ALERT_DISMISS_MS = 60_000L

    enum class GatewayState {
        STARTING,
        RUNNING,
        ERROR,
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notification_alerts_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_alerts_channel_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ERRORS,
                context.getString(R.string.notification_error_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_error_channel_desc)
            },
        )
    }

    fun buildOngoingNotification(
        context: Context,
        state: GatewayState,
        loopbackBase: String,
        channelCount: Int = 0,
        errorMessage: String? = null,
    ): Notification {
        val launchIntent = openAppPendingIntent(context)
        val stopIntent = Intent(context, ServerService::class.java).apply { action = ServerService.ACTION_STOP }
        val stopPending = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            GatewayState.STARTING -> context.getString(R.string.notification_starting)
            GatewayState.RUNNING -> {
                if (channelCount > 0) {
                    context.getString(R.string.notification_running_channels, channelCount)
                } else {
                    context.getString(R.string.notification_running, loopbackBase)
                }
            }
            GatewayState.ERROR -> context.getString(R.string.notification_error, errorMessage ?: "")
        }
        val channelId = if (state == GatewayState.ERROR) CHANNEL_ERRORS else CHANNEL_ONGOING
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launchIntent)
            .setOngoing(state != GatewayState.ERROR)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_ONGOING)
            .setGroupSummary(false)
            .apply {
                if (state != GatewayState.ERROR) {
                    addAction(0, context.getString(R.string.action_stop), stopPending)
                }
            }
            .build()
    }

    fun showServerStartedAlert(context: Context, channelCount: Int) {
        if (!canPost(context)) {
            Log.w(TAG, "Skipping started alert — notification permission missing")
            return
        }
        val appContext = context.applicationContext
        val body = GatewayOverlay.readyBodyText(appContext, channelCount)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.alert_server_running_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent(appContext))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_DISMISS_MS)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setOnlyAlertOnce(false)
            .addAction(0, appContext.getString(R.string.action_open), openAppPendingIntent(appContext))
        if (shouldUseFullScreenStartedAlert(appContext)) {
            builder.setFullScreenIntent(serverReadyPendingIntent(appContext, channelCount), true)
        }
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_ALERT_STARTED, builder.build())
        Log.i(TAG, "Posted started alert (id=$NOTIFICATION_ID_ALERT_STARTED, channels=$channelCount)")
    }

    fun showServerFailedAlert(context: Context, errorMessage: String) {
        if (!canPost(context)) return
        val appContext = context.applicationContext
        val body = appContext.getString(R.string.alert_server_failed_text, errorMessage)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.alert_server_failed_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent(appContext))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .addAction(0, appContext.getString(R.string.action_open), openAppPendingIntent(appContext))
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_ALERT_ERROR, notification)
    }

    fun cancelAlerts(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID_ALERT_STARTED)
        manager.cancel(NOTIFICATION_ID_ALERT_ERROR)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serverReadyPendingIntent(context: Context, channelCount: Int): PendingIntent {
        val intent = Intent(context, ServerReadyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ServerReadyActivity.EXTRA_CHANNEL_COUNT, channelCount)
        }
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Full-screen alert when overlay cannot draw; overlay owns the visual when it can. */
    fun shouldUseFullScreenStartedAlert(context: Context): Boolean {
        val appContext = context.applicationContext
        return canPost(appContext) &&
            !MainActivity.isInForeground &&
            canUseFullScreenIntent(appContext) &&
            !GatewayOverlay.canDraw(appContext)
    }

    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.canUseFullScreenIntent()
    }

    private fun canPost(context: Context): Boolean = PermissionHelper.hasNotificationPermission(context)

    private const val TAG = "GatewayNotifier"
}

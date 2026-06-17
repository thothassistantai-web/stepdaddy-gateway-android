package com.nova.stepdaddylivehd.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * TV sticks often wake from sleep directly into TiviMate without passing through
 * BOOT_COMPLETED. SCREEN_ON / USER_PRESENT nudge the gateway up before playlist
 * refresh or channel zaps hit a dead localhost server.
 */
class ScreenWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) {
            return
        }
        val appContext = context.applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            return
        }
        val source = when {
            environment.tivimateWatchEnabled && TiviMateWatch.isTiviMateLikelyActive(appContext) ->
                if (action == Intent.ACTION_SCREEN_ON) "screen_on+tivimate" else "user_present+tivimate"
            action == Intent.ACTION_SCREEN_ON -> "screen_on"
            else -> "user_present"
        }
        Log.i(TAG, "Wake event ($action); ensuring gateway")
        val result = GatewayStartHelper.startIfNeeded(appContext, source, allowReschedule = false)
        Log.i(TAG, "$source start result: $result")
    }

    companion object {
        private const val TAG = "ScreenWakeReceiver"
    }
}

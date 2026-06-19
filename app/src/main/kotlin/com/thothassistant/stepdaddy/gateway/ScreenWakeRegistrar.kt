package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Registers [ScreenWakeReceiver] at runtime. Manifest-declared SCREEN_ON receivers
 * are not delivered to third-party apps on API 26+; dynamic registration works
 * while the hosting process is alive (ServerService keeps the process up).
 */
object ScreenWakeRegistrar {
    @Volatile
    private var registered = false
    private val receiver = ScreenWakeReceiver()

    fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val filter = IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            val appContext = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
            Log.i(TAG, "Registered dynamic screen/wake receiver")
        }
    }

    private const val TAG = "ScreenWakeRegistrar"
}

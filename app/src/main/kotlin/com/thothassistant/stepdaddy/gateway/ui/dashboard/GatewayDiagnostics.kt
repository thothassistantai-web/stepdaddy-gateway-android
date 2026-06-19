package com.thothassistant.stepdaddy.gateway.ui.dashboard

import android.util.Log

object GatewayDiagnostics {
    fun info(tag: String, message: String) {
        Log.i(tag, message)
        GatewayMessageBus.post("[$tag] $message")
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        GatewayLogRing.append("WARN", tag, message)
        GatewayMessageBus.post("[$tag] $message", "WARN")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        GatewayLogRing.append("ERROR", tag, message)
        GatewayMessageBus.post("[$tag] $message", "ERROR")
    }
}

package com.thothassistant.stepdaddy.gateway.streamvault

import android.content.Context
import android.content.SharedPreferences
import com.thothassistant.stepdaddy.gateway.BuildConfig

internal interface StreamVaultPluginSettings {
    var enabled: Boolean
    var gatewayBaseUrl: String
    var lanMode: Boolean
}

internal class StreamVaultPluginPrefs(context: Context) : StreamVaultPluginSettings {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    override var gatewayBaseUrl: String
        get() = prefs.getString(KEY_GATEWAY_BASE, BuildConfig.DEFAULT_API_URL).orEmpty()
            .trim()
            .trimEnd('/')
            .ifBlank { BuildConfig.DEFAULT_API_URL.trimEnd('/') }
        set(value) {
            prefs.edit()
                .putString(KEY_GATEWAY_BASE, value.trim().trimEnd('/'))
                .apply()
        }

    override var lanMode: Boolean
        get() = prefs.getBoolean(KEY_LAN_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LAN_MODE, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "streamvault_plugin"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GATEWAY_BASE = "gateway_base_url"
        private const val KEY_LAN_MODE = "lan_mode"
    }
}

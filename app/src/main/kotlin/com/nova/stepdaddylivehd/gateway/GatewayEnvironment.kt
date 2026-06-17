package com.nova.stepdaddylivehd.gateway

import android.content.Context
import android.content.SharedPreferences

class GatewayEnvironment(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, BuildConfig.DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, BuildConfig.DEFAULT_API_URL) ?: BuildConfig.DEFAULT_API_URL
        set(value) {
            prefs.edit().putString(KEY_API_URL, value.trimEnd('/')).apply()
        }

    var dlhdBaseUrl: String
        get() = prefs.getString(KEY_DLHD_BASE_URL, BuildConfig.DEFAULT_DLHD_BASE_URL)
            ?: BuildConfig.DEFAULT_DLHD_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_DLHD_BASE_URL, value.trimEnd('/')).apply()
        }

    var mirrorUrls: List<String>
        get() {
            val raw = prefs.getString(KEY_MIRROR_URLS, DEFAULT_MIRRORS_CSV) ?: DEFAULT_MIRRORS_CSV
            return raw.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        }
        set(value) {
            prefs.edit().putString(KEY_MIRROR_URLS, value.joinToString(",")).apply()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()
        }

    var serverRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVER_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SERVER_RUNNING, value).apply()
        }

    /** Survives process death within the same boot session. Cleared on BOOT_COMPLETED. */
    var readyBannerShownThisBoot: Boolean
        get() = prefs.getBoolean(KEY_READY_BANNER_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_READY_BANNER_SHOWN, value).apply()
        }

    fun clearReadyBannerForNewBoot() {
        prefs.edit().putBoolean(KEY_READY_BANNER_SHOWN, false).apply()
    }

    /** True when the service restarted within [CRASH_RECOVERY_BANNER_SKIP_MS] of a prior start. */
    fun isRecentCrashRecovery(): Boolean {
        val lastStart = prefs.getLong(KEY_LAST_SERVICE_START_MS, 0L)
        if (lastStart <= 0L) return false
        return System.currentTimeMillis() - lastStart < CRASH_RECOVERY_BANNER_SKIP_MS
    }

    fun recordServiceStart() {
        prefs.edit().putLong(KEY_LAST_SERVICE_START_MS, System.currentTimeMillis()).apply()
    }

    fun loopbackBase(): String = "http://127.0.0.1:$port"

    companion object {
        private const val PREFS_NAME = "stepdaddy_gateway"
        private const val KEY_PORT = "port"
        private const val KEY_API_URL = "api_url"
        private const val KEY_DLHD_BASE_URL = "dlhd_base_url"
        private const val KEY_MIRROR_URLS = "mirror_urls"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_SERVER_RUNNING = "server_running"
        private const val KEY_READY_BANNER_SHOWN = "ready_banner_shown"
        private const val KEY_LAST_SERVICE_START_MS = "last_service_start_ms"
        private const val CRASH_RECOVERY_BANNER_SKIP_MS = 10 * 60 * 1000L
        private const val DEFAULT_MIRRORS_CSV =
            "https://daddylive.org,https://daddylive.li,https://daddylive.eu"
    }
}

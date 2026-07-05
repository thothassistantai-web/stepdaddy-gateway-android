package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayDiagnostics
import java.net.BindException

/**
 * Prevents release + debug gateway packages from fighting over port 3000.
 *
 * Debug builds stop the release service so local testing can overwrite release.
 * Release builds stop the debug service and claim the configured port.
 */
object GatewayPackageGuard {
    const val RELEASE_PACKAGE = "com.thothassistant.stepdaddy.gateway"
    const val DEBUG_PACKAGE = "com.thothassistant.stepdaddy.gateway.debug"

    private const val TAG = "GatewayPackageGuard"
    private const val PREFS = "gateway_package_guard"
    private const val KEY_SIBLING_WARNED = "sibling_warned"

    fun resolveSiblingConflict(context: Context) {
        val appContext = context.applicationContext
        val selfPackage = appContext.packageName
        val isDebug = BuildConfig.DEBUG
        val releaseInstalled = isPackageInstalled(appContext, RELEASE_PACKAGE)
        val debugInstalled = isPackageInstalled(appContext, DEBUG_PACKAGE)
        if (!releaseInstalled || !debugInstalled) return

        if (isDebug) {
            stopSiblingGateway(appContext, RELEASE_PACKAGE)
            logOnce(
                appContext,
                "Debug build detected release gateway ($RELEASE_PACKAGE); stopped release service " +
                    "to avoid port ${BuildConfig.DEFAULT_PORT} conflict. " +
                    "For clean testing: adb uninstall $RELEASE_PACKAGE",
            )
        } else if (selfPackage == RELEASE_PACKAGE) {
            stopSiblingGateway(appContext, DEBUG_PACKAGE)
            logOnce(
                appContext,
                "Release build detected debug gateway ($DEBUG_PACKAGE); stopped debug service " +
                    "to claim port ${BuildConfig.DEFAULT_PORT}",
            )
        }
    }

    fun portConflictHint(context: Context, port: Int): String {
        val siblings = listOf(RELEASE_PACKAGE, DEBUG_PACKAGE)
            .filter { it != context.packageName && isPackageInstalled(context, it) }
        return if (siblings.isEmpty()) {
            "Port $port may be in use by another app."
        } else {
            "Port $port conflict — sibling gateway installed (${siblings.joinToString()}). " +
                "Uninstall the other variant or stop its ServerService."
        }
    }

    fun isPortBindFailure(exc: Throwable): Boolean =
        exc is BindException || exc.cause is BindException

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun stopSiblingGateway(context: Context, packageName: String) {
        try {
            val stop = Intent(ServerService.ACTION_STOP).apply {
                setClassName(packageName, ServerService::class.java.name)
            }
            context.startService(stop)
        } catch (exc: Exception) {
            Log.w(TAG, "Could not stop sibling gateway $packageName", exc)
        }
    }

    private fun logOnce(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SIBLING_WARNED, false)) {
            Log.i(TAG, message)
            return
        }
        prefs.edit().putBoolean(KEY_SIBLING_WARNED, true).apply()
        Log.w(TAG, message)
        GatewayDiagnostics.warn(TAG, message)
    }
}

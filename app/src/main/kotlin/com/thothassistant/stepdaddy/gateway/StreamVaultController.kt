package com.thothassistant.stepdaddy.gateway

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** StreamVault IPTV player detection and launch helpers. */
object StreamVaultController {
    const val PACKAGE = "com.streamvault.app"
    const val PACKAGE_DEBUG = "$PACKAGE.debug"
    const val PACKAGE_BETA = "$PACKAGE.beta"
    const val MAIN_ACTIVITY_CLASS = "com.streamvault.app.MainActivity"

    data class PlayerInfo(
        val installed: Boolean,
        val packageName: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
    )

    fun probe(context: Context): PlayerInfo {
        val pkg = playerPackage(context) ?: return PlayerInfo(installed = false)
        val packageInfo = runCatching { loadPackageInfo(context, pkg) }.getOrNull()
            ?: return PlayerInfo(installed = false)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return PlayerInfo(
            installed = true,
            packageName = pkg,
            versionName = packageInfo.versionName,
            versionCode = versionCode,
        )
    }

    fun isInstalled(context: Context): Boolean = playerPackage(context) != null

    fun playerPackage(context: Context): String? {
        if (isPackageInstalled(context, PACKAGE)) return PACKAGE
        if (isPackageInstalled(context, PACKAGE_DEBUG)) return PACKAGE_DEBUG
        if (isPackageInstalled(context, PACKAGE_BETA)) return PACKAGE_BETA
        return null
    }

    fun launchComponent(context: Context): String {
        val pkg = playerPackage(context) ?: PACKAGE
        return "$pkg/$MAIN_ACTIVITY_CLASS"
    }

    fun launch(context: Context): Boolean {
        val targetPackage = playerPackage(context)
        if (targetPackage == null) {
            Log.w(TAG, "StreamVault not installed")
            return false
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(targetPackage, MAIN_ACTIVITY_CLASS)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "Launched StreamVault via $targetPackage")
            true
        }.getOrElse { exc ->
            Log.w(TAG, "StreamVault launch failed: ${exc.message}")
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0).any { it.packageName == packageName }
        }
    }

    private fun loadPackageInfo(context: Context, packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }

    private const val TAG = "StreamVaultController"
}

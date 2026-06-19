package com.thothassistant.stepdaddy.gateway

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val REQUEST_POST_NOTIFICATIONS = 1001
    const val REQUEST_BATTERY_OPTIMIZATION = 1002
    const val REQUEST_SCHEDULE_EXACT_ALARM = 1003
    const val REQUEST_OVERLAY = 1004

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasNotificationPermission(context: Context): Boolean {
        if (!needsNotificationPermission()) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(activity: Activity) {
        if (!needsNotificationPermission() || hasNotificationPermission(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        if (canDrawOverlays(activity)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { activity.startActivityForResult(intent, REQUEST_OVERLAY) }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun requestExactAlarmPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms(activity)) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivityForResult(intent, REQUEST_SCHEDULE_EXACT_ALARM) }
    }

    fun requestBatteryOptimizationExemption(activity: Activity) {
        if (isBatteryOptimizationIgnored(activity)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching {
            activity.startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION)
        }.onFailure {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { activity.startActivity(fallback) }
        }
    }

    fun adbGrantCommands(packageName: String): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("adb shell pm grant $packageName android.permission.POST_NOTIFICATIONS")
        }
        add("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("adb shell appops set $packageName SCHEDULE_EXACT_ALARM allow")
        }
    }
}

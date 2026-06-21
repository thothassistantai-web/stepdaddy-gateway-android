package com.thothassistant.stepdaddy.gateway.update

import android.content.Context
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import com.thothassistant.stepdaddy.gateway.upstream.getText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateManager(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val installManager: ApkInstallManager = ApkInstallManager(context),
    private val repository: AppUpdateRepository = AppUpdateRepository(environment),
) {
    private val downloadMutex = Mutex()
    @Volatile
    private var stalePendingDiscarded = false
    private var pendingApkPath: String?
        get() = environment.pendingUpdateApkPath.takeIf { it.isNotEmpty() }
        set(value) {
            environment.pendingUpdateApkPath = value.orEmpty()
        }

    val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    fun isUpdateAvailable(info: AppUpdateInfo): Boolean =
        info.manifest.versionCode > currentVersionCode

    fun isMandatory(info: AppUpdateInfo): Boolean {
        val manifest = info.manifest
        if (manifest.mandatory) return true
        val minVersion = manifest.minVersionCode ?: return false
        return currentVersionCode < minVersion
    }

    fun shouldPrompt(info: AppUpdateInfo): Boolean {
        if (!isUpdateAvailable(info)) return false
        if (isMandatory(info)) return true
        return info.manifest.versionCode > environment.dismissedUpdateVersionCode
    }

    fun dismissUpdate(info: AppUpdateInfo) {
        environment.dismissedUpdateVersionCode = info.manifest.versionCode
    }

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> =
        runCatching { repository.fetchUpdate()?.takeIf { isUpdateAvailable(it) } }

    suspend fun downloadUpdate(
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<java.io.File> = downloadMutex.withLock {
        runCatching {
            val entry = InstallAppEntry(
                id = "self-update",
                name = "StepDaddy Gateway",
                apkUrl = info.manifest.apkUrl,
                source = info.sourceLabel,
                packageName = BuildConfig.APPLICATION_ID,
                version = info.manifest.versionName,
            )
            val apkFile = installManager.downloadApk(entry, onProgress)
            val packageInfo = installManager.resolvePackageInfo(apkFile)
            val expectedPackage = BuildConfig.APPLICATION_ID
            if (packageInfo?.packageName != expectedPackage) {
                apkFile.delete()
                error(
                    "Invalid update package (${packageInfo?.packageName ?: "unknown"}); " +
                        "expected $expectedPackage",
                )
            }
            val apkVersionCode = packageInfo.longVersionCode.toInt()
            if (apkVersionCode <= currentVersionCode) {
                apkFile.delete()
                error(
                    "Downloaded package ($apkVersionCode) is not newer than installed ($currentVersionCode)",
                )
            }
            pendingApkPath = apkFile.absolutePath
            apkFile
        }
    }

    fun launchInstall(apkFile: java.io.File): Boolean = installManager.launchInstall(apkFile)

    fun launchPendingInstall(): Boolean {
        val file = pendingApkFile() ?: return false
        return launchInstall(file)
    }

    fun pendingApkFile(): java.io.File? {
        val path = pendingApkPath ?: return null
        val file = java.io.File(path)
        if (!file.isFile || file.length() <= 0L) {
            clearPendingApk()
            return null
        }
        val apkVersionCode = installManager.resolvePackageInfo(file)?.longVersionCode?.toInt()
        if (apkVersionCode == null || apkVersionCode <= currentVersionCode) {
            clearStalePendingApk(file)
            return null
        }
        return file
    }

    /** True once after a cached APK was dropped because it was not newer than the installed build. */
    fun consumeStalePendingDiscarded(): Boolean {
        val discarded = stalePendingDiscarded
        stalePendingDiscarded = false
        return discarded
    }

    private fun clearPendingApk() {
        pendingApkPath = null
    }

    private fun clearStalePendingApk(file: java.io.File) {
        pendingApkPath = null
        stalePendingDiscarded = true
        runCatching { file.delete() }
    }

    fun canInstallPackages(): Boolean = installManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() = installManager.openInstallPermissionSettings()
}

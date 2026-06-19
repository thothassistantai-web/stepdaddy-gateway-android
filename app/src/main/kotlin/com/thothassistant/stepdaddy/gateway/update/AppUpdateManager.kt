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
            pendingApkPath = apkFile.absolutePath
            apkFile
        }
    }

    fun launchInstall(apkFile: java.io.File): Boolean = installManager.launchInstall(apkFile)

    fun launchPendingInstall(): Boolean {
        val path = pendingApkPath ?: return false
        val file = java.io.File(path)
        if (!file.exists()) {
            pendingApkPath = null
            return false
        }
        return launchInstall(file)
    }

    fun canInstallPackages(): Boolean = installManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() = installManager.openInstallPermissionSettings()
}

package com.thothassistant.stepdaddy.gateway.update

import android.content.Context
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    fun isDebugBuild(): Boolean = BuildConfig.APPLICATION_ID.endsWith(".debug")

    fun isUpdateAvailable(info: AppUpdateInfo): Boolean =
        info.manifest.versionCode > currentVersionCode

    fun isMandatory(info: AppUpdateInfo): Boolean =
        UpdatePolicy.isMandatory(info.manifest, currentVersionCode)

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

    /** Latest manifest regardless of version (used for release graduation). */
    suspend fun fetchLatestManifest(): Result<AppUpdateInfo?> =
        runCatching { repository.fetchUpdate() }

    suspend fun downloadUpdate(
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<java.io.File> = downloadMutex.withLock {
        runCatching {
            val isDebug = isDebugBuild()
            val apkUrl = info.manifest.resolvedApkUrl(isDebug)
            require(apkUrl.isNotBlank()) { "Update APK URL is empty" }
            val entry = InstallAppEntry(
                id = "self-update",
                name = "StepDaddy Gateway",
                apkUrl = apkUrl,
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
            if (installManager.hasSigningCertMismatch(apkFile, expectedPackage)) {
                apkFile.delete()
                error(SIGNATURE_MISMATCH_MESSAGE)
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

    /**
     * Download the **release** APK for installing alongside / after uninstalling the debug package.
     * Debug and release use different applicationIds — PackageManager cannot silently convert one
     * into the other.
     */
    suspend fun downloadReleaseForGraduation(
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<java.io.File> = downloadMutex.withLock {
        runCatching {
            require(isDebugBuild()) {
                "Graduate to Release is only available on the debug package"
            }
            val apkUrl = info.manifest.releaseApkUrl()
            require(apkUrl.isNotBlank()) { "Release APK URL is empty in update manifest" }
            val entry = InstallAppEntry(
                id = "graduate-release",
                name = "StepDaddy Gateway Release",
                apkUrl = apkUrl,
                source = info.sourceLabel,
                packageName = RELEASE_PACKAGE,
                version = info.manifest.versionName,
            )
            val apkFile = installManager.downloadApk(entry, onProgress)
            val packageInfo = installManager.resolvePackageInfo(apkFile)
            if (packageInfo?.packageName != RELEASE_PACKAGE) {
                apkFile.delete()
                error(
                    "Invalid release package (${packageInfo?.packageName ?: "unknown"}); " +
                        "expected $RELEASE_PACKAGE",
                )
            }
            // Different package than debug — signing mismatch vs installed debug is expected N/A.
            // If release is already installed with a different cert, warn before install intent.
            if (installManager.hasSigningCertMismatch(apkFile, RELEASE_PACKAGE)) {
                apkFile.delete()
                error(SIGNATURE_MISMATCH_MESSAGE)
            }
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
        val packageInfo = installManager.resolvePackageInfo(file)
        val apkVersionCode = packageInfo?.longVersionCode?.toInt()
        if (apkVersionCode == null || apkVersionCode <= currentVersionCode) {
            clearStalePendingApk(file)
            return null
        }
        if (packageInfo.packageName != BuildConfig.APPLICATION_ID) {
            clearStalePendingApk(file)
            return null
        }
        if (installManager.hasSigningCertMismatch(file, BuildConfig.APPLICATION_ID)) {
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

    companion object {
        const val RELEASE_PACKAGE = "com.thothassistant.stepdaddy.gateway"
        const val DEBUG_PACKAGE = "com.thothassistant.stepdaddy.gateway.debug"

        /**
         * Honest Android constraint: same package + different signer cannot update in-place.
         * Old release key is lost — stranded installs must uninstall first.
         */
        const val SIGNATURE_MISMATCH_MESSAGE =
            "Signing certificate mismatch. Android cannot update this package with a different " +
                "signing key. Uninstall the existing StepDaddy Gateway release app, then install " +
                "the new signed APK (or switch to the debug package for continued OTA)."
    }
}

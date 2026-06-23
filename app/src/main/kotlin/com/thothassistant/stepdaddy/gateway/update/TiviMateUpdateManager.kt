package com.thothassistant.stepdaddy.gateway.update

import android.content.Context
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.TiviMateInstalledVariant
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import com.thothassistant.stepdaddy.gateway.install.InstallAppsCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TiviMateUpdateManager(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val installManager: ApkInstallManager = ApkInstallManager(context),
    private val repository: TiviMateUpdateRepository = TiviMateUpdateRepository(),
) {
    private val downloadMutex = Mutex()

    private var pendingApkPath: String?
        get() = environment.pendingTiviMateUpdateApkPath.takeIf { it.isNotEmpty() }
        set(value) {
            environment.pendingTiviMateUpdateApkPath = value.orEmpty()
        }

    suspend fun probeInstalled() = withContext(Dispatchers.IO) {
        TiviMateController.detectInstalledVariant(context)
    }

    suspend fun checkForUpdate(): Result<TiviMateUpdateCheckResult> = runCatching {
        val probe = probeInstalled()
        val latest = repository.fetchLatestUpdate()
            ?: error("Could not resolve latest TiviMate Daddy release")
        val installedCode = probe.patchVersion?.let(PatchVersionComparator::versionCodeFromPatchName)
        val updateAvailable = when (probe.variant) {
            TiviMateInstalledVariant.NOT_INSTALLED -> true
            TiviMateInstalledVariant.STEP_DADDY -> PatchVersionComparator.isUpdateAvailable(
                installedPatchVersion = probe.patchVersion,
                installedVersionCode = installedCode,
                latestPatchVersion = latest.manifest.versionName,
                latestVersionCode = latest.manifest.versionCode,
            )
            TiviMateInstalledVariant.PLAIN_MOD,
            TiviMateInstalledVariant.UNKNOWN,
            -> true
        }
        TiviMateUpdateCheckResult(
            probe = probe,
            latest = latest,
            updateAvailable = updateAvailable,
        )
    }

    fun isUpdateAvailable(result: TiviMateUpdateCheckResult): Boolean = result.updateAvailable

    suspend fun downloadUpdate(
        info: TiviMateUpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<java.io.File> = downloadMutex.withLock {
        runCatching {
            val entry = InstallAppEntry(
                id = InstallAppsCatalogRepository.STEPDADDY_TIVIMATE_CATALOG_ID,
                name = "TiviMate Daddy",
                apkUrl = info.manifest.apkUrl,
                source = InstallAppsCatalogRepository.SOURCE_STEPDADDY,
                packageName = TiviMateController.PACKAGE,
                version = info.manifest.versionName,
            )
            val apkFile = installManager.downloadApk(entry, onProgress)
            val packageInfo = installManager.resolvePackageInfo(apkFile)
            if (packageInfo?.packageName != TiviMateController.PACKAGE) {
                apkFile.delete()
                error(
                    "Invalid package (${packageInfo?.packageName ?: "unknown"}); " +
                        "expected ${TiviMateController.PACKAGE}",
                )
            }
            pendingApkPath = apkFile.absolutePath
            apkFile
        }
    }

    fun pendingApkFile(): java.io.File? {
        val path = pendingApkPath ?: return null
        val file = java.io.File(path)
        if (!file.isFile || file.length() <= 0L) {
            pendingApkPath = null
            return null
        }
        return file
    }

    fun launchInstall(apkFile: java.io.File): Boolean = installManager.launchInstall(apkFile)

    fun canInstallPackages(): Boolean = installManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() = installManager.openInstallPermissionSettings()
}

data class TiviMateUpdateCheckResult(
    val probe: com.thothassistant.stepdaddy.gateway.TiviMateVariantProbe,
    val latest: TiviMateUpdateInfo,
    val updateAvailable: Boolean,
)

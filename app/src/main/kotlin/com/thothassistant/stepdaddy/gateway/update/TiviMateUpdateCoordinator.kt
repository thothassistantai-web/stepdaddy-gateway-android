package com.thothassistant.stepdaddy.gateway.update

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.TiviMateInstalledVariant
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TiviMateUpdateCoordinator(
    context: Context,
    private val environment: GatewayEnvironment,
) {
    private val appContext = context.applicationContext
    private val manager = TiviMateUpdateManager(appContext, environment)
    private val downloadMutex = Mutex()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    @Volatile
    private var lastCheckResult: TiviMateUpdateCheckResult? = null

    private val listeners = CopyOnWriteArrayList<(TiviMateUpdateCheckResult?) -> Unit>()

    fun addListener(listener: (TiviMateUpdateCheckResult?) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener(lastCheckResult)
    }

    fun removeListener(listener: (TiviMateUpdateCheckResult?) -> Unit) {
        listeners.remove(listener)
    }

    fun currentResult(): TiviMateUpdateCheckResult? = lastCheckResult

    fun manager(): TiviMateUpdateManager = manager

    fun refreshInstalled(host: AppCompatActivity, onReady: (TiviMateUpdateCheckResult?) -> Unit) {
        host.lifecycleScope.launch {
            val probe = withContext(Dispatchers.IO) { manager.probeInstalled() }
            val latest = lastCheckResult?.latest
            val result = if (latest != null) {
                buildResult(probe, latest)
            } else {
                null
            }
            if (result != null) {
                notifyListeners(result)
            }
            onReady(result)
        }
    }

    fun checkForUpdate(
        host: AppCompatActivity,
        manual: Boolean,
        onComplete: ((Result<TiviMateUpdateCheckResult?>) -> Unit)? = null,
    ) {
        if (checkJob?.isActive == true) {
            if (manual) toast(host, host.getString(R.string.tivimate_update_checking))
            return
        }
        checkJob = host.lifecycleScope.launch {
            if (manual) toast(host, host.getString(R.string.tivimate_update_checking))
            val result = withContext(Dispatchers.IO) { manager.checkForUpdate() }
            result.onSuccess { check ->
                notifyListeners(check)
                if (!manual) {
                    onComplete?.invoke(Result.success(check))
                    return@onSuccess
                }
                when {
                    !check.updateAvailable ->
                        toast(host, host.getString(R.string.tivimate_update_none))
                    check.probe.variant == TiviMateInstalledVariant.PLAIN_MOD ->
                        toast(host, host.getString(R.string.tivimate_update_plain_mod_hint))
                    check.probe.variant == TiviMateInstalledVariant.UNKNOWN ->
                        toast(host, host.getString(R.string.tivimate_update_unknown_variant_hint))
                }
                onComplete?.invoke(Result.success(check))
            }.onFailure { exc ->
                if (manual) {
                    toast(host, host.getString(R.string.tivimate_update_check_failed, exc.message ?: "error"))
                }
                onComplete?.invoke(Result.failure(exc))
            }
        }
    }

    fun downloadAndInstall(host: AppCompatActivity, info: TiviMateUpdateInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = host.lifecycleScope.launch {
            toast(host, host.getString(R.string.tivimate_update_downloading))
            val result = downloadMutex.withLock {
                withContext(Dispatchers.IO) { manager.downloadUpdate(info) { } }
            }
            result.onSuccess { apk ->
                showInstallReadyDialog(host, info, apk)
            }.onFailure { exc ->
                toast(host, host.getString(R.string.tivimate_update_download_failed, exc.message ?: "error"))
            }
        }
    }

    fun promptUpdateIfNeeded(host: AppCompatActivity, check: TiviMateUpdateCheckResult) {
        if (!check.updateAvailable) return
        when (check.probe.variant) {
            TiviMateInstalledVariant.PLAIN_MOD -> {
                showVariantWarningDialog(
                    host,
                    host.getString(R.string.tivimate_update_plain_mod_title),
                    host.getString(R.string.tivimate_update_plain_mod_message),
                    check,
                )
            }
            TiviMateInstalledVariant.UNKNOWN -> {
                showVariantWarningDialog(
                    host,
                    host.getString(R.string.tivimate_update_unknown_variant_title),
                    host.getString(R.string.tivimate_update_unknown_variant_message),
                    check,
                )
            }
            TiviMateInstalledVariant.NOT_INSTALLED -> {
                showInstallDialog(host, check)
            }
            TiviMateInstalledVariant.STEP_DADDY -> {
                showUpdateDialog(host, check)
            }
        }
    }

    private fun showUpdateDialog(host: AppCompatActivity, check: TiviMateUpdateCheckResult) {
        val info = check.latest
        val manifest = info.manifest
        val message = buildString {
            append(
                host.getString(
                    R.string.tivimate_update_dialog_message,
                    manifest.versionName,
                    manifest.versionCode,
                ),
            )
            manifest.releaseNotes?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                append("\n\n")
                append(notes)
            }
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.tivimate_update_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.tivimate_update_action_download) { _, _ ->
                downloadAndInstall(host, info)
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun showInstallDialog(host: AppCompatActivity, check: TiviMateUpdateCheckResult) {
        val info = check.latest
        AlertDialog.Builder(host)
            .setTitle(R.string.tivimate_update_install_title)
            .setMessage(
                host.getString(
                    R.string.tivimate_update_install_message,
                    info.manifest.versionName,
                ),
            )
            .setPositiveButton(R.string.tivimate_update_action_download) { _, _ ->
                downloadAndInstall(host, info)
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun showVariantWarningDialog(
        host: AppCompatActivity,
        title: String,
        message: String,
        check: TiviMateUpdateCheckResult,
    ) {
        AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.tivimate_update_action_download) { _, _ ->
                downloadAndInstall(host, check.latest)
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun showInstallReadyDialog(
        host: AppCompatActivity,
        info: TiviMateUpdateInfo,
        apkFile: File,
    ) {
        AlertDialog.Builder(host)
            .setTitle(R.string.tivimate_update_install_ready_title)
            .setMessage(
                host.getString(
                    R.string.tivimate_update_install_ready_message,
                    info.manifest.versionName,
                ),
            )
            .setPositiveButton(R.string.update_action_install) { _, _ ->
                if (!manager.canInstallPackages()) {
                    toast(host, host.getString(R.string.install_apps_unknown_sources_hint))
                    manager.openInstallPermissionSettings()
                    return@setPositiveButton
                }
                if (manager.launchInstall(apkFile)) {
                    toast(host, host.getString(R.string.update_download_ready))
                } else {
                    toast(host, host.getString(R.string.install_apps_launch_failed))
                }
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun buildResult(
        probe: com.thothassistant.stepdaddy.gateway.TiviMateVariantProbe,
        latest: TiviMateUpdateInfo,
    ): TiviMateUpdateCheckResult {
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
        return TiviMateUpdateCheckResult(probe, latest, updateAvailable)
    }

    private fun notifyListeners(result: TiviMateUpdateCheckResult) {
        lastCheckResult = result
        listeners.forEach { listener ->
            runCatching { listener(result) }
        }
    }

    private fun toast(host: AppCompatActivity, message: String) {
        if (!host.isFinishing && !host.isDestroyed) {
            Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
        }
    }
}

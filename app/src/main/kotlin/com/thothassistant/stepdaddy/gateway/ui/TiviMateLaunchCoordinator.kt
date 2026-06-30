package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.TiviMateInstalledVariant
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.TiviMateLauncher
import com.thothassistant.stepdaddy.gateway.TiviMatePlaylistStateHelper
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import com.thothassistant.stepdaddy.gateway.install.InstallAppsCatalogRepository
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayMessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared launch / install flow for the playlist-card Launch button and QR dialog.
 */
class TiviMateLaunchCoordinator(
    private val activity: AppCompatActivity,
    private val environment: GatewayEnvironment,
    private val catalogRepository: InstallAppsCatalogRepository,
    private val installManager: ApkInstallManager,
    private val scope: CoroutineScope,
    private val ensureInstallAllowed: () -> Boolean,
    private val onBusyChanged: (Boolean) -> Unit = {},
) {
    private val installMutex = Mutex()
    private var installJob: Job? = null

    fun launchOrPromptInstall() {
        if (TiviMateLauncher.isInstalled(activity)) {
            launchInstalled()
            return
        }
        TiviMateInstallPickerDialog.show(activity, scope, catalogRepository) { choice ->
            when (choice) {
                is TiviMateInstallChoice.CatalogDownload -> {
                    if (choice.entry.apkUrl.isBlank()) {
                        Toast.makeText(activity, R.string.tivimate_option_unavailable, Toast.LENGTH_LONG).show()
                    } else {
                        downloadAndInstall(choice.entry)
                    }
                }
                TiviMateInstallChoice.OfficialSite -> openOfficialInstallPage()
            }
        }
    }

    private fun launchInstalled() {
        val probe = TiviMateLauncher.detectInstalledVariant(activity)
        val launched = TiviMateLauncher.launchForGateway(activity, environment.loopbackBase())
        if (!launched) {
            Toast.makeText(activity, R.string.toast_tivimate_launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        when (probe.variant) {
            TiviMateInstalledVariant.STEP_DADDY -> {
                val state = TiviMateController.probeState().state
                val hint = TiviMatePlaylistStateHelper.launchHint(activity, state)
                if (hint != null) {
                    Toast.makeText(activity, hint, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, R.string.toast_tivimate_variant_daddy, Toast.LENGTH_SHORT).show()
                }
            }
            TiviMateInstalledVariant.PLAIN_MOD,
            TiviMateInstalledVariant.UNKNOWN,
            -> Toast.makeText(activity, R.string.toast_tivimate_variant_mod, Toast.LENGTH_SHORT).show()
            TiviMateInstalledVariant.NOT_INSTALLED -> Unit
        }
    }

    fun openOfficialInstallPage() = openOfficialSite()

    private fun openOfficialSite() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(InstallAppsCatalogRepository.TIVIMATE_OFFICIAL_URL),
        )
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, R.string.toast_tivimate_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadAndInstall(entry: InstallAppEntry) {
        if (!ensureInstallAllowed()) return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            onBusyChanged(true)
            runCatching {
                installMutex.withLock {
                    Toast.makeText(activity, R.string.toast_tivimate_installing, Toast.LENGTH_SHORT).show()
                    GatewayMessageBus.postInstallProgress(entry.name, "downloading")
                    val apkFile = installManager.downloadApk(entry) { }
                    if (!installManager.launchInstall(apkFile)) {
                        error(activity.getString(R.string.install_apps_launch_failed))
                    }
                    entry
                }
            }.onSuccess {
                Toast.makeText(activity, R.string.toast_tivimate_install_ready, Toast.LENGTH_LONG).show()
            }.onFailure { exc ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_tivimate_install_failed, exc.message ?: "error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            onBusyChanged(false)
        }
    }
}

sealed class TiviMateInstallChoice {
    data class CatalogDownload(val entry: InstallAppEntry) : TiviMateInstallChoice()
    data object OfficialSite : TiviMateInstallChoice()
}

object TiviMateInstallPickerDialog {
    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        catalogRepository: InstallAppsCatalogRepository,
        onSelected: (TiviMateInstallChoice) -> Unit,
    ) {
        scope.launch {
            val catalog = runCatching { catalogRepository.loadCatalog() }.getOrNull()
            val daddyEntry = catalog?.let { catalogRepository.findStepDaddyTiviMateEntry(it) }
            val modEntry = catalog?.let { catalogRepository.find461ModTiviMateEntry(it) }

            val choices = buildList {
                if (modEntry != null) {
                    add(TiviMateInstallChoice.CatalogDownload(modEntry))
                }
                if (daddyEntry != null) {
                    add(TiviMateInstallChoice.CatalogDownload(daddyEntry))
                } else {
                    add(TiviMateInstallChoice.CatalogDownload(InstallAppEntry(
                        id = InstallAppsCatalogRepository.STEPDADDY_TIVIMATE_CATALOG_ID,
                        name = activity.getString(R.string.tivimate_option_legacy_daddy_title),
                        description = activity.getString(R.string.tivimate_option_legacy_daddy_desc),
                        apkUrl = "",
                        source = InstallAppsCatalogRepository.SOURCE_STEPDADDY,
                    )))
                }
                add(TiviMateInstallChoice.OfficialSite)
            }

            val labels = choices.map { choice ->
                when (choice) {
                    is TiviMateInstallChoice.CatalogDownload -> {
                        val title = when {
                            choice.entry.packageName == "ar.tvplayer.tv" ||
                                choice.entry.name.contains("x2", ignoreCase = true) ||
                                choice.entry.name.contains("5.", ignoreCase = true) ->
                                activity.getString(R.string.tivimate_option_x2_title)
                            choice.entry.id == InstallAppsCatalogRepository.STEPDADDY_TIVIMATE_CATALOG_ID ||
                                choice.entry.name.contains("daddy", ignoreCase = true) ->
                                activity.getString(R.string.tivimate_option_legacy_daddy_title)
                            else -> activity.getString(R.string.tivimate_option_mod_title)
                        }
                        val desc = when {
                            choice.entry.packageName == "ar.tvplayer.tv" ||
                                choice.entry.name.contains("x2", ignoreCase = true) ->
                                activity.getString(R.string.tivimate_option_x2_desc)
                            choice.entry.id == InstallAppsCatalogRepository.STEPDADDY_TIVIMATE_CATALOG_ID ||
                                choice.entry.name.contains("daddy", ignoreCase = true) ->
                                activity.getString(R.string.tivimate_option_legacy_daddy_desc)
                            else -> activity.getString(R.string.tivimate_option_mod_desc)
                        }
                        "$title\n$desc"
                    }
                    TiviMateInstallChoice.OfficialSite ->
                        activity.getString(R.string.tivimate_option_official_title) + "\n" +
                            activity.getString(R.string.tivimate_option_official_desc)
                }
            }.toTypedArray()

            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.dialog_tivimate_install_title)
                    .setMessage(R.string.dialog_tivimate_install_message)
                    .setItems(labels) { dialog, which ->
                        dialog.dismiss()
                        onSelected(choices[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}

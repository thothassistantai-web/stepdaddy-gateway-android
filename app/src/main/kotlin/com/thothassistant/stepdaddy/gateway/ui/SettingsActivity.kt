package com.thothassistant.stepdaddy.gateway.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.GatewayStartHelper
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.update.AppUpdateDialogHelper
import com.thothassistant.stepdaddy.gateway.update.AppUpdateInfo
import com.thothassistant.stepdaddy.gateway.update.AppUpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment
    private lateinit var appUpdateManager: AppUpdateManager
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        appUpdateManager = AppUpdateManager(this, environment, ApkInstallManager(this))
        loadFields()
        binding.buttonSave.setOnClickListener { saveAndFinish() }
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        binding.buttonSave.requestFocus()
        if (environment.autoCheckUpdates) {
            checkForUpdates(manual = false)
        }
    }

    private fun loadFields() {
        binding.editPort.setText(environment.port.toString())
        binding.editRemoteGatewayUrl.setText(environment.remoteGatewayUrl)
        binding.editDlhdUrl.setText(environment.dlhdBaseUrl)
        binding.editMirrorUrls.setText(environment.mirrorUrls.joinToString(","))
        binding.editSupplementUrl.setText(environment.supplementBaseUrl)
        binding.switchEmbeddedSidecar.isChecked = environment.embeddedSidecarEnabled
        binding.switchSupplementSports.isChecked = environment.supplementSportsEnabled
        binding.switchSupplementIptvOrg.isChecked = environment.supplementIptvOrgEnabled
        binding.switchIptvOrgEpg.isChecked = environment.iptvOrgEpgEnabled
        binding.editIptvOrgEpgUrl.setText(environment.iptvOrgEpgUrl)
        binding.switchAutoStart.isChecked = environment.autoStartOnLaunch
        binding.switchLaunchTivimate.isChecked = environment.launchTivimateOnReady
        binding.switchBoot.isChecked = environment.startOnBoot
        binding.switchTivimateWatch.isChecked = environment.tivimateWatchEnabled
        binding.switchAutoCheckUpdates.isChecked = environment.autoCheckUpdates
        binding.switchAutoDownloadUpdates.isChecked = environment.autoDownloadUpdates
        binding.editUpdateManifestUrl.setText(environment.updateManifestUrlOverride)
        binding.editUpdateDriveFolderUrl.setText(environment.updateDriveFolderUrl)
        val built = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME))
        binding.textBuildInfo.text = getString(
            R.string.settings_build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_HASH,
            BuildConfig.BUILD_TYPE,
            built,
        )
    }

    private fun saveAndFinish() {
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, R.string.settings_port_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        environment.port = port
        environment.remoteGatewayUrl = binding.editRemoteGatewayUrl.text?.toString().orEmpty()
        environment.dlhdBaseUrl = binding.editDlhdUrl.text?.toString().orEmpty()
        environment.mirrorUrls = binding.editMirrorUrls.text?.toString()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        environment.supplementBaseUrl = binding.editSupplementUrl.text?.toString().orEmpty()
        environment.embeddedSidecarEnabled = binding.switchEmbeddedSidecar.isChecked
        environment.supplementSportsEnabled = binding.switchSupplementSports.isChecked
        environment.supplementIptvOrgEnabled = binding.switchSupplementIptvOrg.isChecked
        environment.iptvOrgEpgEnabled = binding.switchIptvOrgEpg.isChecked
        environment.iptvOrgEpgUrl = binding.editIptvOrgEpgUrl.text?.toString().orEmpty()
        environment.autoStartOnLaunch = binding.switchAutoStart.isChecked
        environment.launchTivimateOnReady = binding.switchLaunchTivimate.isChecked
        environment.startOnBoot = binding.switchBoot.isChecked
        environment.tivimateWatchEnabled = binding.switchTivimateWatch.isChecked
        environment.autoCheckUpdates = binding.switchAutoCheckUpdates.isChecked
        environment.autoDownloadUpdates = binding.switchAutoDownloadUpdates.isChecked
        environment.setUpdateManifestUrlOverride(binding.editUpdateManifestUrl.text?.toString().orEmpty())
        environment.updateDriveFolderUrl = binding.editUpdateDriveFolderUrl.text?.toString().orEmpty()
        if (environment.startOnBoot) {
            GatewayStartHelper.schedulePeriodicEnsureAlive(this)
        } else {
            GatewayStartHelper.cancelPeriodicEnsureAlive(this)
        }
        Toast.makeText(this, R.string.settings_saved_restart_hint, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheckJob?.isActive == true) return
        syncUpdateFieldsFromBinding()
        binding.textUpdateStatus.text = getString(R.string.settings_update_checking)
        updateCheckJob = lifecycleScope.launch {
            appUpdateManager.checkForUpdate()
                .onSuccess { info ->
                    if (info == null) {
                        binding.textUpdateStatus.text = getString(
                            R.string.settings_update_none,
                            BuildConfig.VERSION_NAME,
                        )
                        if (manual) {
                            Toast.makeText(this@SettingsActivity, R.string.update_none, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    binding.textUpdateStatus.text = getString(
                        R.string.settings_update_available,
                        info.manifest.versionName,
                        info.manifest.versionCode,
                    )
                    if (binding.switchAutoDownloadUpdates.isChecked) {
                        downloadUpdate(info)
                    }
                    if (manual || appUpdateManager.shouldPrompt(info)) {
                        promptForUpdate(info)
                    }
                }
                .onFailure { exc ->
                    val message = exc.message ?: "error"
                    binding.textUpdateStatus.text = getString(R.string.settings_update_check_failed, message)
                    if (manual) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_update_check_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
        }
    }

    private fun promptForUpdate(info: AppUpdateInfo) {
        val mandatory = appUpdateManager.isMandatory(info)
        AppUpdateDialogHelper.showUpdateDialog(
            activity = this,
            info = info,
            mandatory = mandatory,
            onUpdate = { downloadUpdate(info) },
            onDismiss = {
                if (!mandatory) {
                    appUpdateManager.dismissUpdate(info)
                }
            },
        )
    }

    private fun downloadUpdate(info: AppUpdateInfo) {
        if (!ensureInstallAllowed()) return
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
            appUpdateManager.downloadUpdate(info) { }
                .onSuccess { apkFile ->
                    AppUpdateDialogHelper.showInstallReadyDialog(
                        activity = this@SettingsActivity,
                        info = info,
                        onInstall = {
                            if (appUpdateManager.launchInstall(apkFile)) {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    R.string.update_download_ready,
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    R.string.install_apps_launch_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                }
                .onFailure { exc ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_download_failed, exc.message ?: "error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun ensureInstallAllowed(): Boolean {
        if (!appUpdateManager.canInstallPackages()) {
            Toast.makeText(this, R.string.install_apps_unknown_sources_hint, Toast.LENGTH_LONG).show()
            appUpdateManager.openInstallPermissionSettings()
            return false
        }
        return true
    }

    private fun syncUpdateFieldsFromBinding() {
        environment.autoCheckUpdates = binding.switchAutoCheckUpdates.isChecked
        environment.autoDownloadUpdates = binding.switchAutoDownloadUpdates.isChecked
        environment.setUpdateManifestUrlOverride(binding.editUpdateManifestUrl.text?.toString().orEmpty())
        environment.updateDriveFolderUrl = binding.editUpdateDriveFolderUrl.text?.toString().orEmpty()
    }
}

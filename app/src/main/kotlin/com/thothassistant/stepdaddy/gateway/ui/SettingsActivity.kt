package com.thothassistant.stepdaddy.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.GatewayStartHelper
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.network.NetworkAccessMode
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
    private var selectedNetworkMode: NetworkAccessMode = NetworkAccessMode.DEFAULT

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
        binding.buttonCopyAccessToken.setOnClickListener { copyAccessToken() }
        binding.buttonRegenerateAccessToken.setOnClickListener { regenerateAccessToken() }
        binding.toggleNetworkMode.addOnButtonCheckedListener(networkModeListener)
        binding.buttonSave.requestFocus()
        if (environment.autoCheckUpdates) {
            checkForUpdates(manual = false)
        }
    }

    private val networkModeListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@OnButtonCheckedListener
            selectedNetworkMode = when (checkedId) {
                R.id.buttonNetworkLocal -> NetworkAccessMode.LOCAL
                R.id.buttonNetworkRemote -> NetworkAccessMode.REMOTE
                else -> NetworkAccessMode.DEFAULT
            }
            if (selectedNetworkMode == NetworkAccessMode.REMOTE &&
                binding.editRemoteAccessToken.text.isNullOrBlank()
            ) {
                binding.editRemoteAccessToken.setText(environment.ensureRemoteAccessToken())
            }
            updateRemoteNetworkVisibility()
        }

    private fun loadFields() {
        binding.editPort.setText(environment.port.toString())
        selectedNetworkMode = environment.networkAccessMode
        when (selectedNetworkMode) {
            NetworkAccessMode.LOCAL -> binding.toggleNetworkMode.check(R.id.buttonNetworkLocal)
            NetworkAccessMode.REMOTE -> binding.toggleNetworkMode.check(R.id.buttonNetworkRemote)
            NetworkAccessMode.DEFAULT -> binding.toggleNetworkMode.check(R.id.buttonNetworkDefault)
        }
        binding.editGatewayName.setText(environment.gatewayName)
        binding.editRemoteGatewayUrl.setText(environment.remoteGatewayUrl)
        binding.editRemoteAccessToken.setText(
            environment.remoteAccessToken.ifBlank {
                if (selectedNetworkMode == NetworkAccessMode.REMOTE) {
                    environment.ensureRemoteAccessToken()
                } else {
                    ""
                }
            },
        )
        updateRemoteNetworkVisibility()
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

    private fun updateRemoteNetworkVisibility() {
        binding.layoutRemoteNetwork.visibility =
            if (selectedNetworkMode == NetworkAccessMode.REMOTE) View.VISIBLE else View.GONE
    }

    private fun saveAndFinish() {
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, R.string.settings_port_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        environment.port = port
        environment.networkAccessMode = selectedNetworkMode
        environment.gatewayName = binding.editGatewayName.text?.toString().orEmpty()
        environment.remoteGatewayUrl = binding.editRemoteGatewayUrl.text?.toString().orEmpty()
        if (selectedNetworkMode == NetworkAccessMode.REMOTE) {
            val token = binding.editRemoteAccessToken.text?.toString().orEmpty()
            environment.remoteAccessToken = if (token.isBlank()) {
                environment.ensureRemoteAccessToken()
            } else {
                token
            }
        }
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
        if (environment.startOnBoot) {
            GatewayStartHelper.schedulePeriodicEnsureAlive(this)
        } else {
            GatewayStartHelper.cancelPeriodicEnsureAlive(this)
        }
        Toast.makeText(this, R.string.settings_saved_restart_hint, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun copyAccessToken() {
        val token = binding.editRemoteAccessToken.text?.toString().orEmpty()
        if (token.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stepdaddy_token", token))
        Toast.makeText(this, R.string.toast_token_copied, Toast.LENGTH_SHORT).show()
    }

    private fun regenerateAccessToken() {
        environment.remoteAccessToken = ""
        val token = environment.ensureRemoteAccessToken()
        binding.editRemoteAccessToken.setText(token)
        Toast.makeText(this, R.string.toast_token_regenerated, Toast.LENGTH_SHORT).show()
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
    }
}

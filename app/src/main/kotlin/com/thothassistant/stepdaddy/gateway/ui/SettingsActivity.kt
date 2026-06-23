package com.thothassistant.stepdaddy.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator
import com.thothassistant.stepdaddy.gateway.update.AppUpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment
    private lateinit var updateCoordinator: AppUpdateCoordinator
    private val settingsUpdateListener: (AppUpdateInfo?) -> Unit = { info ->
        binding.textUpdateStatus.text = when {
            info == null -> getString(R.string.settings_update_none, BuildConfig.VERSION_NAME)
            else -> getString(
                R.string.settings_update_available,
                info.manifest.versionName,
                info.manifest.versionCode,
            )
        }
    }
    private var selectedNetworkMode: NetworkAccessMode = NetworkAccessMode.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        updateCoordinator = (application as GatewayApp).appUpdateCoordinator
        updateCoordinator.addAvailabilityListener(settingsUpdateListener)
        loadFields()
        binding.buttonSave.setOnClickListener { saveAndFinish() }
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        binding.buttonOpenAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.buttonCopyAccessToken.setOnClickListener { copyAccessToken() }
        binding.buttonRegenerateAccessToken.setOnClickListener { regenerateAccessToken() }
        binding.toggleNetworkMode.addOnButtonCheckedListener(networkModeListener)
        binding.buttonSave.requestFocus()
    }

    override fun onDestroy() {
        updateCoordinator.removeAvailabilityListener(settingsUpdateListener)
        super.onDestroy()
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
        SettingsSupplementControls.load(binding, environment)
        SettingsSupplementControls.wireListeners(binding)
        binding.switchGatewayEpg.isChecked = environment.gatewayEpgEnabled
        binding.editExternalEpgUrl.setText(environment.externalEpgUrlForDisplay())
        updateExternalEpgVisibility()
        binding.switchGatewayEpg.setOnCheckedChangeListener { _, _ -> updateExternalEpgVisibility() }
        binding.switchIptvOrgEpg.isChecked = environment.iptvOrgEpgEnabled
        binding.editIptvOrgEpgUrl.setText(environment.iptvOrgEpgUrl)
        binding.switchAutoStart.isChecked = environment.autoStartOnLaunch
        binding.switchLaunchTivimate.isChecked = environment.autoLaunchTiviMate
        binding.switchBoot.isChecked = environment.startOnBoot
        binding.switchTivimateWatch.isChecked = environment.tivimateWatchEnabled
        binding.switchXtreamPlaylistTitles.isChecked =
            environment.playlistTitleStyle == com.thothassistant.stepdaddy.gateway.upstream.PlaylistTitleStyle.XTREAM_CATEGORY
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

    private fun updateExternalEpgVisibility() {
        val gatewayEpg = binding.switchGatewayEpg.isChecked
        val external = !gatewayEpg
        binding.layoutExternalEpgUrl.visibility = if (external) View.VISIBLE else View.GONE
        binding.switchIptvOrgEpg.visibility = if (gatewayEpg) View.VISIBLE else View.GONE
        binding.layoutIptvOrgEpgUrl.visibility = if (gatewayEpg) View.VISIBLE else View.GONE
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
        SettingsSupplementControls.save(binding, environment)
        environment.gatewayEpgEnabled = binding.switchGatewayEpg.isChecked
        environment.externalEpgUrl = binding.editExternalEpgUrl.text?.toString().orEmpty()
        environment.iptvOrgEpgEnabled = binding.switchIptvOrgEpg.isChecked
        environment.iptvOrgEpgUrl = binding.editIptvOrgEpgUrl.text?.toString().orEmpty()
        environment.autoStartOnLaunch = binding.switchAutoStart.isChecked
        environment.autoLaunchTiviMate = binding.switchLaunchTivimate.isChecked
        environment.startOnBoot = binding.switchBoot.isChecked
        environment.tivimateWatchEnabled = binding.switchTivimateWatch.isChecked
        environment.playlistTitleStyle = if (binding.switchXtreamPlaylistTitles.isChecked) {
            com.thothassistant.stepdaddy.gateway.upstream.PlaylistTitleStyle.XTREAM_CATEGORY
        } else {
            com.thothassistant.stepdaddy.gateway.upstream.PlaylistTitleStyle.LEGACY
        }
        (application as GatewayApp).playlistCache.invalidate()
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
        syncUpdateFieldsFromBinding()
        binding.textUpdateStatus.text = getString(R.string.settings_update_checking)
        updateCoordinator.checkForUpdate(this, manual)
    }

    private fun ensureInstallAllowed(): Boolean {
        val manager = updateCoordinator.manager()
        if (!manager.canInstallPackages()) {
            Toast.makeText(this, R.string.install_apps_unknown_sources_hint, Toast.LENGTH_LONG).show()
            manager.openInstallPermissionSettings()
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

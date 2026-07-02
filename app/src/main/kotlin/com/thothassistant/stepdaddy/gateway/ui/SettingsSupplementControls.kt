package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.view.View
import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding
import com.thothassistant.stepdaddy.gateway.upstream.SupplementImportMode

internal object SettingsSupplementControls {
    private var syncingMaster = false

    fun load(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        binding.switchSupplementSports.isChecked = environment.supplementSportsEnabled
        binding.switchSupplementIptvOrg.isChecked = environment.supplementIptvOrgEnabled
        binding.switchSupplementXyzStreams.isChecked = environment.supplementXyzStreamsEnabled
        binding.switchSupplementNtvCx.isChecked = environment.supplementNtvCxEnabled
        binding.switchSupplementAdultSwim.isChecked = environment.supplementAdultSwimEnabled
        binding.switchSupplementTmdbMovies.isChecked = environment.supplementTmdbMoviesEnabled
        SettingsImportModeUi.load(binding.iptvOrgImportMode, environment.supplementIptvOrgImportMode)
        SettingsImportModeUi.load(binding.xyzStreamsImportMode, environment.supplementXyzStreamsImportMode)
        binding.switchXyzStreamsEpgDiscovery.isChecked = environment.supplementXyzStreamsEpgDiscoveryEnabled
        SettingsImportModeUi.load(binding.ntvCxImportMode, environment.supplementNtvCxImportMode)
        SettingsImportModeUi.load(binding.adultSwimImportMode, environment.supplementAdultSwimImportMode)
        refreshMasterSwitch(binding)
        updateImportModeVisibility(binding)
    }

    fun wireListeners(binding: ActivitySettingsBinding, host: SettingsActivity) {
        val visibilityListener = { _: android.widget.CompoundButton, _: Boolean ->
            updateImportModeVisibility(binding)
        }
        binding.switchSupplementSports.setOnCheckedChangeListener(visibilityListener)
        binding.switchSupplementIptvOrg.setOnCheckedChangeListener(visibilityListener)
        binding.switchSupplementXyzStreams.setOnCheckedChangeListener(visibilityListener)
        binding.switchSupplementNtvCx.setOnCheckedChangeListener(visibilityListener)
        binding.switchSupplementAdultSwim.setOnCheckedChangeListener(visibilityListener)
        binding.switchSupplementTmdbMovies.setOnCheckedChangeListener(visibilityListener)
        binding.buttonIptvOrgPlaylists.setOnClickListener {
            host.startActivity(Intent(host, IptvOrgPlaylistSettingsActivity::class.java))
        }
        binding.switchEnableAllSupplements.setOnCheckedChangeListener { _, checked ->
            if (syncingMaster) return@setOnCheckedChangeListener
            syncingMaster = true
            binding.switchSupplementSports.isChecked = checked
            binding.switchSupplementIptvOrg.isChecked = checked
            binding.switchSupplementXyzStreams.isChecked = checked
            binding.switchSupplementNtvCx.isChecked = checked
            binding.switchSupplementAdultSwim.isChecked = checked
            binding.switchSupplementTmdbMovies.isChecked = checked
            syncingMaster = false
            updateImportModeVisibility(binding)
        }
        updateImportModeVisibility(binding)
    }

    fun save(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        environment.supplementSportsEnabled = binding.switchSupplementSports.isChecked
        environment.supplementIptvOrgEnabled = binding.switchSupplementIptvOrg.isChecked
        environment.supplementXyzStreamsEnabled = binding.switchSupplementXyzStreams.isChecked
        environment.supplementNtvCxEnabled = binding.switchSupplementNtvCx.isChecked
        environment.supplementAdultSwimEnabled = binding.switchSupplementAdultSwim.isChecked
        environment.supplementTmdbMoviesEnabled = binding.switchSupplementTmdbMovies.isChecked
        environment.supplementIptvOrgImportMode = SettingsImportModeUi.read(binding.iptvOrgImportMode)
        environment.supplementXyzStreamsImportMode = SettingsImportModeUi.read(binding.xyzStreamsImportMode)
        environment.supplementXyzStreamsEpgDiscoveryEnabled = binding.switchXyzStreamsEpgDiscovery.isChecked
        environment.supplementNtvCxImportMode = SettingsImportModeUi.read(binding.ntvCxImportMode)
        environment.supplementAdultSwimImportMode = SettingsImportModeUi.read(binding.adultSwimImportMode)
    }

    private fun refreshMasterSwitch(binding: ActivitySettingsBinding) {
        if (syncingMaster) return
        syncingMaster = true
        val allOn = binding.switchSupplementSports.isChecked &&
            binding.switchSupplementIptvOrg.isChecked &&
            binding.switchSupplementXyzStreams.isChecked &&
            binding.switchSupplementNtvCx.isChecked &&
            binding.switchSupplementAdultSwim.isChecked &&
            binding.switchSupplementTmdbMovies.isChecked
        binding.switchEnableAllSupplements.isChecked = allOn
        syncingMaster = false
    }

    private fun updateImportModeVisibility(binding: ActivitySettingsBinding) {
        SettingsImportModeUi.setVisible(binding.iptvOrgImportMode, binding.switchSupplementIptvOrg.isChecked)
        binding.buttonIptvOrgPlaylists.visibility =
            if (binding.switchSupplementIptvOrg.isChecked) View.VISIBLE else View.GONE
        SettingsImportModeUi.setVisible(binding.xyzStreamsImportMode, binding.switchSupplementXyzStreams.isChecked)
        setDiscoveryRow(binding, binding.switchSupplementXyzStreams.isChecked)
        SettingsImportModeUi.setVisible(binding.ntvCxImportMode, binding.switchSupplementNtvCx.isChecked)
        SettingsImportModeUi.setVisible(binding.adultSwimImportMode, binding.switchSupplementAdultSwim.isChecked)
    }

    private fun setDiscoveryRow(binding: ActivitySettingsBinding, providerOn: Boolean) {
        binding.switchXyzStreamsEpgDiscovery.visibility = if (providerOn) View.VISIBLE else View.GONE
        binding.switchXyzStreamsEpgDiscovery.isEnabled = providerOn
    }
}

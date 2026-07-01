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
        binding.switchIptvOrgSkipDuplicates.isChecked =
            environment.supplementIptvOrgImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchXyzStreamsSkipDuplicates.isChecked =
            environment.supplementXyzStreamsImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchXyzStreamsEpgDiscovery.isChecked = environment.supplementXyzStreamsEpgDiscoveryEnabled
        binding.switchNtvCxSkipDuplicates.isChecked =
            environment.supplementNtvCxImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchAdultSwimSkipDuplicates.isChecked =
            environment.supplementAdultSwimImportMode == SupplementImportMode.SKIP_DUPLICATES
        refreshMasterSwitch(binding)
        updateSkipDuplicateVisibility(binding)
    }

    fun wireListeners(binding: ActivitySettingsBinding, host: SettingsActivity) {
        val skipListener = { _: android.widget.CompoundButton, _: Boolean ->
            updateSkipDuplicateVisibility(binding)
        }
        binding.switchSupplementSports.setOnCheckedChangeListener(skipListener)
        binding.switchSupplementIptvOrg.setOnCheckedChangeListener(skipListener)
        binding.switchSupplementXyzStreams.setOnCheckedChangeListener(skipListener)
        binding.switchSupplementNtvCx.setOnCheckedChangeListener(skipListener)
        binding.switchSupplementAdultSwim.setOnCheckedChangeListener(skipListener)
        binding.switchSupplementTmdbMovies.setOnCheckedChangeListener(skipListener)
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
            updateSkipDuplicateVisibility(binding)
        }
        updateSkipDuplicateVisibility(binding)
    }

    fun save(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        environment.supplementSportsEnabled = binding.switchSupplementSports.isChecked
        environment.supplementIptvOrgEnabled = binding.switchSupplementIptvOrg.isChecked
        environment.supplementXyzStreamsEnabled = binding.switchSupplementXyzStreams.isChecked
        environment.supplementNtvCxEnabled = binding.switchSupplementNtvCx.isChecked
        environment.supplementAdultSwimEnabled = binding.switchSupplementAdultSwim.isChecked
        environment.supplementTmdbMoviesEnabled = binding.switchSupplementTmdbMovies.isChecked
        environment.supplementIptvOrgImportMode = importMode(binding.switchIptvOrgSkipDuplicates.isChecked)
        environment.supplementXyzStreamsImportMode = importMode(binding.switchXyzStreamsSkipDuplicates.isChecked)
        environment.supplementXyzStreamsEpgDiscoveryEnabled = binding.switchXyzStreamsEpgDiscovery.isChecked
        environment.supplementNtvCxImportMode = importMode(binding.switchNtvCxSkipDuplicates.isChecked)
        environment.supplementAdultSwimImportMode = importMode(binding.switchAdultSwimSkipDuplicates.isChecked)
    }

    private fun importMode(skipDuplicates: Boolean): SupplementImportMode =
        if (skipDuplicates) SupplementImportMode.SKIP_DUPLICATES else SupplementImportMode.FULL_CATALOG

    /** Reflect all-on state on load only; individual toggles do not move the master switch. */
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

    private fun updateSkipDuplicateVisibility(binding: ActivitySettingsBinding) {
        setSkipDuplicateRow(binding.switchIptvOrgSkipDuplicates, binding.switchSupplementIptvOrg.isChecked)
        binding.buttonIptvOrgPlaylists.visibility =
            if (binding.switchSupplementIptvOrg.isChecked) View.VISIBLE else View.GONE
        setSkipDuplicateRow(binding.switchXyzStreamsSkipDuplicates, binding.switchSupplementXyzStreams.isChecked)
        setSkipDuplicateRow(binding.switchXyzStreamsEpgDiscovery, binding.switchSupplementXyzStreams.isChecked)
        setSkipDuplicateRow(binding.switchNtvCxSkipDuplicates, binding.switchSupplementNtvCx.isChecked)
        setSkipDuplicateRow(binding.switchAdultSwimSkipDuplicates, binding.switchSupplementAdultSwim.isChecked)
    }

    private fun setSkipDuplicateRow(switch: android.widget.CompoundButton, providerOn: Boolean) {
        switch.visibility = if (providerOn) View.VISIBLE else View.GONE
        switch.isEnabled = providerOn
    }
}

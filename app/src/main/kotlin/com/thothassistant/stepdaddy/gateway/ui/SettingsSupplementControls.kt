package com.thothassistant.stepdaddy.gateway.ui

import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding
import com.thothassistant.stepdaddy.gateway.upstream.SupplementImportMode

internal object SettingsSupplementControls {
    private var syncingMaster = false

    fun load(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        binding.switchEmbeddedSidecar.isChecked = environment.embeddedSidecarEnabled
        binding.switchSupplementSports.isChecked = environment.supplementSportsEnabled
        binding.switchSupplementIptvOrg.isChecked = environment.supplementIptvOrgEnabled
        binding.switchSupplementNtvCx.isChecked = environment.supplementNtvCxEnabled
        binding.switchSupplementAdultSwim.isChecked = environment.supplementAdultSwimEnabled
        binding.switchSidecarSkipDuplicates.isChecked =
            environment.supplementSidecarImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchIptvOrgSkipDuplicates.isChecked =
            environment.supplementIptvOrgImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchNtvCxSkipDuplicates.isChecked =
            environment.supplementNtvCxImportMode == SupplementImportMode.SKIP_DUPLICATES
        binding.switchAdultSwimSkipDuplicates.isChecked =
            environment.supplementAdultSwimImportMode == SupplementImportMode.SKIP_DUPLICATES
        syncMasterSwitch(binding)
    }

    fun wireListeners(binding: ActivitySettingsBinding) {
        val providerListener = { _: android.widget.CompoundButton, _: Boolean ->
            syncMasterSwitch(binding)
            updateSkipDuplicateEnabled(binding)
        }
        binding.switchEmbeddedSidecar.setOnCheckedChangeListener(providerListener)
        binding.switchSupplementIptvOrg.setOnCheckedChangeListener(providerListener)
        binding.switchSupplementNtvCx.setOnCheckedChangeListener(providerListener)
        binding.switchSupplementAdultSwim.setOnCheckedChangeListener(providerListener)
        binding.switchEnableAllSupplements.setOnCheckedChangeListener { _, checked ->
            if (syncingMaster) return@setOnCheckedChangeListener
            syncingMaster = true
            binding.switchEmbeddedSidecar.isChecked = checked
            binding.switchSupplementIptvOrg.isChecked = checked
            binding.switchSupplementNtvCx.isChecked = checked
            binding.switchSupplementAdultSwim.isChecked = checked
            syncingMaster = false
            updateSkipDuplicateEnabled(binding)
        }
        updateSkipDuplicateEnabled(binding)
    }

    fun save(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        environment.embeddedSidecarEnabled = binding.switchEmbeddedSidecar.isChecked
        environment.supplementSportsEnabled = binding.switchSupplementSports.isChecked
        environment.supplementIptvOrgEnabled = binding.switchSupplementIptvOrg.isChecked
        environment.supplementNtvCxEnabled = binding.switchSupplementNtvCx.isChecked
        environment.supplementAdultSwimEnabled = binding.switchSupplementAdultSwim.isChecked
        environment.supplementSidecarImportMode = importMode(binding.switchSidecarSkipDuplicates.isChecked)
        environment.supplementIptvOrgImportMode = importMode(binding.switchIptvOrgSkipDuplicates.isChecked)
        environment.supplementNtvCxImportMode = importMode(binding.switchNtvCxSkipDuplicates.isChecked)
        environment.supplementAdultSwimImportMode = importMode(binding.switchAdultSwimSkipDuplicates.isChecked)
    }

    private fun importMode(skipDuplicates: Boolean): SupplementImportMode =
        if (skipDuplicates) SupplementImportMode.SKIP_DUPLICATES else SupplementImportMode.FULL_CATALOG

    private fun syncMasterSwitch(binding: ActivitySettingsBinding) {
        if (syncingMaster) return
        syncingMaster = true
        val allCore = binding.switchEmbeddedSidecar.isChecked &&
            binding.switchSupplementIptvOrg.isChecked &&
            binding.switchSupplementNtvCx.isChecked &&
            binding.switchSupplementAdultSwim.isChecked
        binding.switchEnableAllSupplements.isChecked = allCore
        syncingMaster = false
    }

    private fun updateSkipDuplicateEnabled(binding: ActivitySettingsBinding) {
        val sidecarOn = binding.switchEmbeddedSidecar.isChecked
        binding.switchSidecarSkipDuplicates.isEnabled = sidecarOn
        binding.switchSidecarSkipDuplicates.alpha = if (sidecarOn) 1f else 0.5f

        val iptvOn = binding.switchSupplementIptvOrg.isChecked
        binding.switchIptvOrgSkipDuplicates.isEnabled = iptvOn
        binding.switchIptvOrgSkipDuplicates.alpha = if (iptvOn) 1f else 0.5f

        val ntvOn = binding.switchSupplementNtvCx.isChecked
        binding.switchNtvCxSkipDuplicates.isEnabled = ntvOn
        binding.switchNtvCxSkipDuplicates.alpha = if (ntvOn) 1f else 0.5f

        val adultSwimOn = binding.switchSupplementAdultSwim.isChecked
        binding.switchAdultSwimSkipDuplicates.isEnabled = adultSwimOn
        binding.switchAdultSwimSkipDuplicates.alpha = if (adultSwimOn) 1f else 0.5f
    }
}

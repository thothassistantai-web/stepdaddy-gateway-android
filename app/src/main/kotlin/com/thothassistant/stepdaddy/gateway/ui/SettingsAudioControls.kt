package com.thothassistant.stepdaddy.gateway.ui

import com.google.android.material.slider.Slider
import com.thothassistant.stepdaddy.gateway.audio.AudioPlaybackSettings
import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding

internal object SettingsAudioControls {
    fun load(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        binding.switchVolumeNormalization.isChecked = environment.volumeNormalizationEnabled
        binding.sliderAmplificationGain.value = environment.amplificationGainDb
        updateGainLabel(binding, environment.amplificationGainDb)
    }

    fun wireListeners(binding: ActivitySettingsBinding) {
        binding.sliderAmplificationGain.addOnChangeListener(
            Slider.OnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    updateGainLabel(binding, value)
                }
            },
        )
    }

    fun save(binding: ActivitySettingsBinding, environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment) {
        environment.volumeNormalizationEnabled = binding.switchVolumeNormalization.isChecked
        environment.amplificationGainDb = binding.sliderAmplificationGain.value
    }

    private fun updateGainLabel(binding: ActivitySettingsBinding, gainDb: Float) {
        binding.textAmplificationGainValue.text =
            AudioPlaybackSettings.formatGainDbForDisplay(gainDb)
    }
}

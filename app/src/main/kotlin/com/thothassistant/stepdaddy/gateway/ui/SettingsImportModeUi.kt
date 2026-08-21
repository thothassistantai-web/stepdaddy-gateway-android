package com.thothassistant.stepdaddy.gateway.ui

import com.google.android.material.button.MaterialButtonToggleGroup
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.IncludeSettingsImportModeBinding
import com.thothassistant.stepdaddy.gateway.upstream.SupplementImportMode

internal object SettingsImportModeUi {
    fun load(binding: IncludeSettingsImportModeBinding, mode: SupplementImportMode) {
        binding.toggleImportMode.check(
            when (mode) {
                SupplementImportMode.FULL_CATALOG -> R.id.buttonImportFull
                SupplementImportMode.SKIP_DUPLICATES -> R.id.buttonImportSkip
                SupplementImportMode.CONSOLIDATE_FALLBACKS -> R.id.buttonImportFallback
            },
        )
    }

    fun read(binding: IncludeSettingsImportModeBinding): SupplementImportMode =
        when (binding.toggleImportMode.checkedButtonId) {
            R.id.buttonImportSkip -> SupplementImportMode.SKIP_DUPLICATES
            R.id.buttonImportFull -> SupplementImportMode.FULL_CATALOG
            R.id.buttonImportFallback -> SupplementImportMode.CONSOLIDATE_FALLBACKS
            else -> SupplementImportMode.CONSOLIDATE_FALLBACKS
        }

    fun setVisible(binding: IncludeSettingsImportModeBinding, visible: Boolean) {
        binding.root.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        binding.toggleImportMode.isEnabled = visible
    }
}

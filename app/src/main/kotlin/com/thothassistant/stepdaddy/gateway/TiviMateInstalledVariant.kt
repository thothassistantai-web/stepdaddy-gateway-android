package com.thothassistant.stepdaddy.gateway

/**
 * Classifies an installed TiViMate build for launch and install-picker UX.
 *
 * StepDaddy patch exposes [patchVersion] on loopback `GET :4617/status`.
 */
enum class TiviMateInstalledVariant {
    NOT_INSTALLED,
    STEP_DADDY,
    PLAIN_MOD,
    UNKNOWN,
}

data class TiviMateVariantProbe(
    val variant: TiviMateInstalledVariant,
    val patchVersion: String? = null,
    val versionName: String? = null,
)

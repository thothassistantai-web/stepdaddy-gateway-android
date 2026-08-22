package com.thothassistant.stepdaddy.gateway.update

/**
 * Resolves whether a published [UpdateManifest] is optional or mandatory for the
 * currently installed [installedVersionCode].
 *
 * Manifest fields (backward compatible):
 * - `updateType`: `"optional"` | `"mandatory"` (default optional when absent)
 * - `mandatory`: boolean (legacy; true → mandatory)
 * - `minSupportedVersionCode` / `minVersionCode`: if installed code is below this,
 *   treat as mandatory even when updateType is optional
 */
object UpdatePolicy {
    const val TYPE_OPTIONAL = "optional"
    const val TYPE_MANDATORY = "mandatory"

    fun isMandatory(manifest: UpdateManifest, installedVersionCode: Int): Boolean {
        if (manifest.mandatory) return true
        val type = manifest.updateType?.trim().orEmpty()
        if (type.equals(TYPE_MANDATORY, ignoreCase = true)) return true
        val minSupported = effectiveMinSupportedVersionCode(manifest) ?: return false
        return installedVersionCode < minSupported
    }

    fun effectiveMinSupportedVersionCode(manifest: UpdateManifest): Int? =
        manifest.minSupportedVersionCode ?: manifest.minVersionCode

    fun dialogTitle(manifest: UpdateManifest): String? =
        manifest.title?.trim()?.takeIf { it.isNotEmpty() }

    /** Explicit `message` field only — callers still append [UpdateManifest.releaseNotes]. */
    fun dialogMessage(manifest: UpdateManifest): String? =
        manifest.message?.trim()?.takeIf { it.isNotEmpty() }
}

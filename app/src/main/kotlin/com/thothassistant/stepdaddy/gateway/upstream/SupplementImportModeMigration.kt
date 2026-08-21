package com.thothassistant.stepdaddy.gateway.upstream

/**
 * One-time prefs migration when the compiled default import mode flips
 * from [SupplementImportMode.FULL_CATALOG] to [SupplementImportMode.CONSOLIDATE_FALLBACKS].
 *
 * Untouched installs (stored value still the old default, `userSet` false) move to consolidate.
 * Users who explicitly chose an import mode keep it.
 */
object SupplementImportModeMigration {
    /** Prefs schema for import-mode defaults; bump when the shipped default changes again. */
    const val DEFAULTS_VERSION = 2

    private val LEGACY_DEFAULT_TOKENS = setOf(
        "FULL_CATALOG",
        "ALL",
        "",
    )

    /**
     * @param rawStored value from SharedPreferences, or null when the key was never written
     * @param userSet true when Settings (or equivalent) explicitly saved an import mode choice
     */
    fun shouldMigrateToConsolidate(rawStored: String?, userSet: Boolean): Boolean {
        if (userSet) return false
        val token = rawStored?.trim().orEmpty()
        return token.uppercase() in LEGACY_DEFAULT_TOKENS
    }

    fun targetMode(): SupplementImportMode = SupplementImportMode.CONSOLIDATE_FALLBACKS
}

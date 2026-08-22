package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Prefs migration when the compiled default import mode changes.
 *
 * v2 (3.0.32): untouched FULL_CATALOG → CONSOLIDATE_FALLBACKS.
 * v3 (3.0.38): untouched CONSOLIDATE_FALLBACKS (auto-default) → FULL_CATALOG.
 *
 * Users who explicitly chose Merge / Skip / Full via Settings keep their choice
 * (`*_import_mode_user_set` = true).
 */
object SupplementImportModeMigration {
    /** Prefs schema for import-mode defaults; bump when the shipped default changes again. */
    const val DEFAULTS_VERSION = 3

    private val CONSOLIDATE_TOKENS = setOf(
        "CONSOLIDATE_FALLBACKS",
        "",
    )

    /**
     * @param rawStored value from SharedPreferences, or null when the key was never written
     * @param userSet true when Settings (or equivalent) explicitly saved an import mode choice
     */
    fun shouldMigrateToFullCatalog(rawStored: String?, userSet: Boolean): Boolean {
        if (userSet) return false
        val token = rawStored?.trim().orEmpty()
        // Null key (never written) and empty / consolidate tokens flip to full catalog.
        if (rawStored == null) return true
        return token.uppercase() in CONSOLIDATE_TOKENS
    }

    /** @deprecated Use [shouldMigrateToFullCatalog]; kept for older call sites/tests. */
    @Suppress("UNUSED_PARAMETER")
    fun shouldMigrateToConsolidate(rawStored: String?, userSet: Boolean): Boolean = false

    fun targetMode(): SupplementImportMode = SupplementImportMode.FULL_CATALOG
}

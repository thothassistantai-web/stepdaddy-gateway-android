package com.thothassistant.stepdaddy.gateway.upstream

/**
 * How a supplement provider is merged into the TiviMate playlist.
 *
 * - [FULL_CATALOG]: every upstream row (default — never silently switched).
 * - [SKIP_DUPLICATES]: skip rows whose score-based match against DaddyLive is ≥ threshold.
 * - [CONSOLIDATE_FALLBACKS]: same row count as skip, but attach high-confidence duplicates as failover mirrors.
 *
 * Matching is region/language aware; see docs/CHANNEL-BACKUPS.md.
 */
enum class SupplementImportMode {
    FULL_CATALOG,
    SKIP_DUPLICATES,
    CONSOLIDATE_FALLBACKS,
    ;

    fun skipsDuplicateRows(): Boolean =
        this == SKIP_DUPLICATES || this == CONSOLIDATE_FALLBACKS

    fun attachesFallbacks(): Boolean = this == CONSOLIDATE_FALLBACKS

    companion object {
        fun fromPref(raw: String?): SupplementImportMode {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return FULL_CATALOG
            // Legacy ntv.cx merge mode values
            if (trimmed.equals("ALL", ignoreCase = true)) return FULL_CATALOG
            if (trimmed.equals("SUPPLEMENT_ONLY", ignoreCase = true)) return SKIP_DUPLICATES
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: FULL_CATALOG
        }

        /** @deprecated Use per-provider import mode toggles in Settings. */
        fun fromSkipDuplicatesPref(skipDuplicates: Boolean): SupplementImportMode =
            if (skipDuplicates) SKIP_DUPLICATES else FULL_CATALOG
    }
}

/** @deprecated Use [SupplementImportMode] */
typealias NtvCxMergeMode = SupplementImportMode

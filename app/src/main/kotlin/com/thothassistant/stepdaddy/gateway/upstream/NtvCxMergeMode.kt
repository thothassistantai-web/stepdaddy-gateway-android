package com.thothassistant.stepdaddy.gateway.upstream

/**
 * How ntv.cx CDN Live rows are merged into the playlist.
 *
 * - [ALL]: every CDN Live catalog row (default; useful while verifying name matching).
 * - [SUPPLEMENT_ONLY]: skip rows whose normalized name already exists on the main DaddyLive list.
 */
enum class NtvCxMergeMode {
    ALL,
    SUPPLEMENT_ONLY,
    ;

    companion object {
        fun fromPref(raw: String?): NtvCxMergeMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ALL

        fun fromSupplementOnlyPref(supplementOnly: Boolean): NtvCxMergeMode =
            if (supplementOnly) SUPPLEMENT_ONLY else ALL
    }
}

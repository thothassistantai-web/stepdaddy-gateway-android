package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.upstream.FastEpgCatalog

/**
 * Resolves iptv-org supplement [tvg-id] values using FAST provider context,
 * the mjh.nz name index, and bundled name overrides.
 */
class FastChannelTvgIdResolver(
    private val catalogLookup: (channelName: String, providerTag: String?) -> String?,
    private val epgChannelMapper: EpgChannelMapper?,
    private val nameIndex: IptvOrgNameIndex? = null,
) {
    constructor(
        fastEpgCatalog: FastEpgCatalog,
        epgChannelMapper: EpgChannelMapper?,
        nameIndex: IptvOrgNameIndex? = null,
    ) : this(fastEpgCatalog::lookupChannelId, epgChannelMapper, nameIndex)

    data class Match(
        val tvgId: String,
        val confidence: Float,
        val method: String,
    )

    fun resolve(
        displayName: String,
        groupTitle: String?,
        providerTag: String?,
        currentTvgId: String?,
    ): Match? {
        val provider = effectiveProvider(providerTag, displayName, groupTitle)
        val current = currentTvgId?.trim().orEmpty()

        if (current.isNotEmpty()) {
            if (FastChannelContext.isMongoHexId(current)) {
                lookupDotIdForEpg(displayName)?.let { dot ->
                    if (dot != current) {
                        return Match(dot, 0.86f, "hex_to_dot_epg")
                    }
                }
            }
            if (provider == null || FastChannelContext.tvgIdMatchesProvider(current, provider)) {
                return Match(current, 1.0f, "current_valid")
            }
            lookupCandidate(displayName, provider)?.let { candidate ->
                if (candidate != current) {
                    return Match(candidate, 0.92f, "catalog_context_fix")
                }
            }
            epgChannelMapper?.tvgIdForName(displayName)?.trim()?.takeIf { it.isNotEmpty() }?.let { override ->
                if (override != current && FastChannelContext.tvgIdMatchesProvider(override, provider)) {
                    return Match(override, 0.88f, "name_override_fix")
                }
            }
            return null
        }

        epgChannelMapper?.tvgIdForName(displayName)?.trim()?.takeIf { it.isNotEmpty() }?.let { override ->
            if (provider == null || FastChannelContext.tvgIdMatchesProvider(override, provider)) {
                return Match(override, 0.9f, "name_override")
            }
        }

        lookupDotIdForEpg(displayName)?.let { dot ->
            if (provider !in FastChannelContext.FAST_HASH_PROVIDERS) {
                return Match(dot, 0.84f, "channels_db_backfill")
            }
        }

        provider?.let { lookupCandidate(displayName, it) }?.let { candidate ->
            return Match(candidate, 0.85f, "fast_catalog")
        }

        return null
    }

    fun validateAndFix(
        currentTvgId: String?,
        displayName: String,
        groupTitle: String?,
        providerTag: String?,
    ): String? {
        val current = currentTvgId?.trim().orEmpty()
        val provider = effectiveProvider(providerTag, displayName, groupTitle)

        if (current.isEmpty()) {
            val resolved = resolve(displayName, groupTitle, providerTag, currentTvgId)
            val candidate = resolved?.tvgId?.trim().orEmpty()
            return candidate.takeIf { it.isNotEmpty() }
        }

        if (FastChannelContext.isMongoHexId(current)) {
            lookupDotIdForEpg(displayName)?.let { dot ->
                if (dot != current) return dot
            }
        }

        if (provider != null && FastChannelContext.tvgIdMatchesProvider(current, provider)) {
            return canonicalQualitySuffixBase(current)
        }

        val resolved = resolve(displayName, groupTitle, providerTag, currentTvgId)
        val candidate = resolved?.tvgId?.trim().orEmpty()
        if (candidate.isNotEmpty() && candidate != current) return candidate
        return canonicalQualitySuffixBase(current)
    }

    private fun lookupDotIdForEpg(displayName: String): String? {
        val index = nameIndex ?: return null
        if (!index.isLoaded()) return null
        index.lookupExact(displayName)?.trim()?.takeIf { it.isNotEmpty() }?.let { exact ->
            if (FastChannelContext.isIptvOrgDotId(exact)) return exact
        }
        index.lookupFuzzy(displayName, WOFTV_BACKFILL_MIN_SCORE)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { fuzzy ->
                if (FastChannelContext.isIptvOrgDotId(fuzzy)) return fuzzy
            }
        return null
    }

    private fun effectiveProvider(
        providerTag: String?,
        displayName: String,
        groupTitle: String?,
    ): String? =
        FastChannelContext.normalizeProvider(providerTag)
            ?: FastChannelContext.parseProviderFromName(displayName)
            ?: groupTitle?.let { FastChannelContext.parseProviderFromGroup(it) }

    private fun lookupCandidate(displayName: String, provider: String): String? =
        catalogLookup(displayName, provider)?.trim()?.takeIf { it.isNotEmpty() }

    /** Strip @SD/@HD when the base id is a valid iptv-org dot id (epgshare uses base form). */
    private fun canonicalQualitySuffixBase(tvgId: String): String? {
        val trimmed = tvgId.trim()
        for (suffix in QUALITY_SUFFIXES) {
            if (!trimmed.endsWith(suffix, ignoreCase = true)) continue
            val base = trimmed.dropLast(suffix.length)
            if (base.isNotEmpty() && FastChannelContext.isIptvOrgDotId(base)) return base
        }
        return null
    }

    companion object {
        private val QUALITY_SUFFIXES = listOf("@SD", "@HD", "@UHD", "@4K", "@FHD")
        /** channels_db fuzzy threshold for no-tvg-id / hex-id EPG backfill (audit score floor). */
        const val WOFTV_BACKFILL_MIN_SCORE = 0.65
    }
}

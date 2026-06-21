package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.upstream.FastEpgCatalog

/**
 * Resolves iptv-org supplement [tvg-id] values using FAST provider context,
 * the mjh.nz name index, and bundled name overrides.
 */
class FastChannelTvgIdResolver(
    private val catalogLookup: (channelName: String, providerTag: String?) -> String?,
    private val epgChannelMapper: EpgChannelMapper?,
) {
    constructor(
        fastEpgCatalog: FastEpgCatalog,
        epgChannelMapper: EpgChannelMapper?,
    ) : this(fastEpgCatalog::lookupChannelId, epgChannelMapper)

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
        val provider = effectiveProvider(providerTag, displayName, groupTitle) ?: return null

        if (current.isNotEmpty() && FastChannelContext.tvgIdMatchesProvider(current, provider)) {
            return null
        }

        val resolved = resolve(displayName, groupTitle, providerTag, currentTvgId)
        val candidate = resolved?.tvgId?.trim().orEmpty()
        if (candidate.isEmpty() || candidate == current) return null
        return candidate
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
}

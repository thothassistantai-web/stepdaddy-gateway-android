package com.thothassistant.stepdaddy.gateway.epg

/**
 * Detects FAST provider context from channel metadata and classifies tvg-id styles.
 */
object FastChannelContext {
    /** Providers whose XMLTV guides use mjh-style hash channel ids (no iptv-org dot suffix). */
    val FAST_HASH_PROVIDERS: Set<String> = setOf(
        "Samsung",
        "Pluto",
        "Plex",
        "Xumo",
        "Roku",
        "Tubi",
        "LocalNow",
    )

    private val KNOWN_PROVIDERS: Set<String> = FAST_HASH_PROVIDERS + setOf(
        "STIRR",
        "Distro",
        "FireTV",
    )

    private val PROVIDER_ALIASES: Map<String, String> = mapOf(
        "samsung" to "Samsung",
        "pluto" to "Pluto",
        "plex" to "Plex",
        "xumo" to "Xumo",
        "roku" to "Roku",
        "tubi" to "Tubi",
        "stirr" to "STIRR",
        "distro" to "Distro",
        "firetv" to "FireTV",
        "fire tv" to "FireTV",
        "local" to "LocalNow",
        "localnow" to "LocalNow",
    )

    private val NAME_SUFFIX_PATTERN = Regex(
        """(?:[🇺🇸🇬🇧🇨🇦📡🎬]\s*)?(?:US|UK|CA)\s+""" +
            """(Samsung|Pluto|Distro|Xumo|Roku|Plex|STIRR|Tubi|Fire\s*TV|Local(?:Now)?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val IPTV_ORG_DOT_PATTERN = Regex(
        """\.(us|uk|ca|de|fr|it|es|au|nz|in|br|mx|jp|kr|se|no|dk|fi|nl|be|at|ch|pt|pl|gr|ie|za)(@|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val HASH_PREFIX_PATTERN = Regex(
        """^(USBD|US1800|USBA|US1)""",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeProvider(raw: String?): String? {
        val tag = raw?.trim().orEmpty()
        if (tag.isEmpty()) return null
        PROVIDER_ALIASES[tag.lowercase()]?.let { return it }
        KNOWN_PROVIDERS.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        return KNOWN_PROVIDERS
            .sortedByDescending { it.length }
            .firstOrNull { provider ->
                tag.endsWith(provider, ignoreCase = true) ||
                    containsProviderToken(tag, provider)
            }
    }

    fun parseProviderFromName(displayName: String): String? {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return null

        NAME_SUFFIX_PATTERN.find(trimmed)?.groupValues?.getOrNull(1)?.let { token ->
            normalizeProvider(token)?.let { return it }
        }

        for (provider in KNOWN_PROVIDERS.sortedByDescending { it.length }) {
            if (containsProviderToken(trimmed, provider)) {
                return provider
            }
        }
        return null
    }

    fun parseProviderFromGroup(groupTitle: String): String? {
        val trimmed = groupTitle.trim()
        if (trimmed.isEmpty()) return null

        trimmed.split('|')
            .asReversed()
            .forEach { segment ->
                val part = segment.trim()
                normalizeProvider(part)?.let { return it }
                for (provider in KNOWN_PROVIDERS.sortedByDescending { it.length }) {
                    if (part.endsWith(provider, ignoreCase = true) ||
                        part.contains(provider, ignoreCase = true)
                    ) {
                        return provider
                    }
                }
            }
        return null
    }

    private val MONGO_HEX_ID = Regex("^[0-9a-fA-F]{24}$")

    fun isMongoHexId(tvgId: String): Boolean = MONGO_HEX_ID.matches(tvgId.trim())

    fun isHashStyleFastId(tvgId: String): Boolean {
        val id = tvgId.trim()
        if (id.isEmpty()) return false
        if (isMongoHexId(id)) return true
        if (!id.contains('.')) return true
        return HASH_PREFIX_PATTERN.containsMatchIn(id)
    }

    fun isIptvOrgDotId(tvgId: String): Boolean {
        val id = tvgId.trim()
        if (id.isEmpty()) return false
        return IPTV_ORG_DOT_PATTERN.containsMatchIn(id)
    }

    fun tvgIdMatchesProvider(tvgId: String, provider: String): Boolean {
        val normalized = normalizeProvider(provider) ?: return true
        val id = tvgId.trim()
        if (id.isEmpty()) return false

        return when (normalized) {
            in FAST_HASH_PROVIDERS ->
                isHashStyleFastId(id) && !isIptvOrgDotId(id)
            "STIRR", "FireTV" ->
                isIptvOrgDotId(id) && !isHashStyleFastId(id)
            "Distro" ->
                isHashStyleFastId(id) || isIptvOrgDotId(id)
            else -> true
        }
    }

    private fun containsProviderToken(name: String, provider: String): Boolean {
        val pattern = when (provider) {
            "FireTV" -> Regex("""\bfire\s*tv\b""", RegexOption.IGNORE_CASE)
            "LocalNow" -> Regex("""\blocal(?:\s*now)?\b""", RegexOption.IGNORE_CASE)
            else -> Regex("""\b${Regex.escape(provider)}\b""", RegexOption.IGNORE_CASE)
        }
        return pattern.containsMatchIn(name)
    }
}

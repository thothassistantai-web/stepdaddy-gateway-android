package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import java.util.Locale

/**
 * Score-based DaddyLive ↔ supplement overlap matching for skip/consolidate import modes.
 *
 * Old matching used exact [EpgChannelMapper.normalizeName] (which strips US/UK) or tvg-id only,
 * so cross-region and cross-language cousins collapsed incorrectly. This scorer requires a
 * minimum confidence before treating rows as the same channel.
 */
object SupplementMatchScorer {
    const val MIN_SCORE = 70

    data class Signals(
        val coreName: String,
        val tokens: List<String>,
        val region: String?,
        val languageMarkers: Set<String>,
        val isShort: Boolean,
    )

    data class ScoredMatch(
        val daddyChannelId: String,
        val score: Int,
        val reasons: List<String>,
    )

    fun extractSignals(
        name: String,
        tvgId: String? = null,
        tags: List<String> = emptyList(),
        countryHint: String? = null,
        sourcePlaylist: String? = null,
    ): Signals {
        val lower = name.lowercase(Locale.US)
        val languageMarkers = LANGUAGE_MARKERS.filter { marker ->
            Regex("""\b${Regex.escape(marker)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }.toSet()

        val region = normalizeRegion(countryHint)
            ?: regionFromTags(tags)
            ?: regionFromPlaylist(sourcePlaylist)
            ?: regionFromTvgId(tvgId)
            ?: regionFromName(name)

        var core = lower
        core = core.replace(Regex("""\([^)]*\)"""), " ")
        core = core.replace("+", " plus ").replace("&", " and ")
        for (marker in languageMarkers) {
            core = core.replace(Regex("""\b${Regex.escape(marker)}\b""", RegexOption.IGNORE_CASE), " ")
        }
        for (token in REGION_NAME_TOKENS) {
            core = core.replace(Regex("""\b${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE), " ")
        }
        core = core.replace(Regex("""\b(hd|fhd|uhd|4k|sd|tv|channel|live|network)\b"""), " ")
        core = core.replace(Regex("""[^a-z0-9]+"""), " ")
        core = core.trim().replace(Regex("""\s+"""), " ")

        val tokens = core.split(' ').filter { it.isNotEmpty() }
        val isShort = tokens.size <= 1 && (tokens.firstOrNull()?.length ?: 0) <= 4
        return Signals(
            coreName = core,
            tokens = tokens,
            region = region,
            languageMarkers = languageMarkers,
            isShort = isShort,
        )
    }

    fun score(
        daddyName: String,
        daddyTvgId: String?,
        daddyTags: List<String>,
        candidateName: String,
        candidateTvgId: String?,
        candidateTags: List<String> = emptyList(),
        candidateCountryHint: String? = null,
        candidateSourcePlaylist: String? = null,
    ): Pair<Int, List<String>> {
        val daddy = extractSignals(daddyName, daddyTvgId, daddyTags)
        val candidate = extractSignals(
            name = candidateName,
            tvgId = candidateTvgId,
            tags = candidateTags,
            countryHint = candidateCountryHint,
            sourcePlaylist = candidateSourcePlaylist,
        )
        val reasons = mutableListOf<String>()

        if (daddy.languageMarkers != candidate.languageMarkers &&
            (daddy.languageMarkers.isNotEmpty() || candidate.languageMarkers.isNotEmpty())
        ) {
            return 0 to listOf("language_mismatch:${daddy.languageMarkers}|${candidate.languageMarkers}")
        }

        if (!regionsCompatible(daddy.region, candidate.region)) {
            return 0 to listOf("region_mismatch:${daddy.region}|${candidate.region}")
        }

        val tvgHit = tvgIdsMatch(daddyTvgId, candidateTvgId)
        if (tvgHit) {
            reasons += "tvg_id"
            return 100 to reasons
        }

        if (daddy.coreName.isEmpty() || candidate.coreName.isEmpty()) {
            return 0 to listOf("empty_core")
        }

        if (daddy.coreName != candidate.coreName) {
            return 0 to listOf("core_mismatch")
        }
        reasons += "core_name"

        if (daddy.isShort || candidate.isShort) {
            val exactDisplay = qualityStripped(daddyName)
                .equals(qualityStripped(candidateName), ignoreCase = true)
            // Short cores (CNN) without any region signal are too risky unless labels match exactly.
            if (daddy.region == null && candidate.region == null && !exactDisplay) {
                return 0 to listOf("short_name_needs_region_or_exact")
            }
            reasons += "short_name_guard"
        }

        val score = when {
            daddy.region != null && daddy.region == candidate.region -> 95
            daddy.region == null && candidate.region == null -> 80
            else -> 75 // one side known, other unknown — compatible
        }
        if (daddy.region != null && daddy.region == candidate.region) reasons += "same_region"
        return score to reasons
    }

    fun bestMatch(
        candidateName: String,
        candidateTvgId: String?,
        indexes: SupplementImportMatcher.DaddyIndexes,
        candidateTags: List<String> = emptyList(),
        candidateCountryHint: String? = null,
        candidateSourcePlaylist: String? = null,
        minScore: Int = MIN_SCORE,
    ): ScoredMatch? {
        val candidateSignals = extractSignals(
            name = candidateName,
            tvgId = candidateTvgId,
            tags = candidateTags,
            countryHint = candidateCountryHint,
            sourcePlaylist = candidateSourcePlaylist,
        )
        val pool = LinkedHashSet<SupplementImportMatcher.DaddyMatchTarget>()
        for (key in SupplementDedup.tvgIdKeys(candidateTvgId)) {
            indexes.byTvg[key]?.let { pool += it }
        }
        if (candidateSignals.coreName.isNotEmpty()) {
            indexes.byCoreName[candidateSignals.coreName]?.let { pool.addAll(it) }
        }
        // Exact legacy norm index as a soft recall bucket (still scored).
        val legacyNorm = legacyNormalize(candidateName)
        if (legacyNorm.isNotEmpty()) {
            indexes.byLegacyNorm[legacyNorm]?.let { pool.addAll(it) }
        }

        var best: ScoredMatch? = null
        for (target in pool) {
            val (score, reasons) = score(
                daddyName = target.name,
                daddyTvgId = target.tvgId,
                daddyTags = target.tags,
                candidateName = candidateName,
                candidateTvgId = candidateTvgId,
                candidateTags = candidateTags,
                candidateCountryHint = candidateCountryHint,
                candidateSourcePlaylist = candidateSourcePlaylist,
            )
            if (score < minScore) continue
            if (best == null || score > best.score) {
                best = ScoredMatch(target.id, score, reasons)
            }
        }
        return best
    }

    fun countryHintFromPlaylist(sourcePlaylist: String?): String? =
        regionFromPlaylist(sourcePlaylist)

    fun mirrorFingerprint(mirror: com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror): String =
        when {
            !mirror.duloChannelId.isNullOrBlank() -> "dulo:${mirror.duloChannelId.trim()}"
            !mirror.ntvCdnLiveKey.isNullOrBlank() -> "ntv:${mirror.ntvCdnLiveKey.trim()}"
            mirror.streamUrl.isNotBlank() -> "url:${mirror.streamUrl.trim().lowercase(Locale.US)}"
            else -> "label:${mirror.label.trim().lowercase(Locale.US)}"
        }

    fun pairKey(daddyChannelId: String, fingerprint: String): String =
        "${daddyChannelId.trim()}|$fingerprint"

    private fun tvgIdsMatch(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        val leftKeys = SupplementDedup.tvgIdKeys(left)
        val rightKeys = SupplementDedup.tvgIdKeys(right)
        return leftKeys.any { it in rightKeys }
    }

    private fun regionsCompatible(
        left: String?,
        right: String?,
        requireBoth: Boolean = false,
    ): Boolean {
        if (left == null && right == null) return !requireBoth
        if (left == null || right == null) return !requireBoth
        return left == right
    }

    private fun qualityStripped(name: String): String {
        var s = name.lowercase(Locale.US)
        s = s.replace(Regex("""\([^)]*\)"""), " ")
        s = s.replace(Regex("""\b(hd|fhd|uhd|4k|sd)\b"""), " ")
        s = s.replace(Regex("""[^a-z0-9]+"""), " ")
        return s.trim().replace(Regex("""\s+"""), " ")
    }

    private fun legacyNormalize(name: String): String =
        EpgChannelMapper.normalizeName(name)

    private fun regionFromTags(tags: List<String>): String? {
        for (tag in tags) {
            val t = tag.trim()
            if (t.isEmpty()) continue
            FLAG_TO_REGION[t]?.let { return it }
            if (t.startsWith("#")) {
                normalizeRegion(t.removePrefix("#"))?.let { return it }
            }
        }
        return null
    }

    private fun regionFromPlaylist(sourcePlaylist: String?): String? {
        val file = sourcePlaylist?.lowercase(Locale.US).orEmpty()
        if (file.isEmpty()) return null
        return when {
            "usa" in file || file.startsWith("us_") || file.startsWith("us.") -> "US"
            "canada" in file || file.startsWith("ca_") || "canadian" in file -> "CA"
            file.startsWith("uk") || "united_kingdom" in file || "_uk." in file || "britain" in file -> "UK"
            "australia" in file || file.startsWith("au_") -> "AU"
            "mexico" in file || file.startsWith("mx_") -> "MX"
            "germany" in file || file.startsWith("de_") -> "DE"
            "france" in file || file.startsWith("fr_") -> "FR"
            "spain" in file || file.startsWith("es_") -> "ES"
            "italy" in file || file.startsWith("it_") -> "IT"
            "turkey" in file || "türk" in file || file.startsWith("tr_") -> "TR"
            else -> null
        }
    }

    private fun regionFromTvgId(tvgId: String?): String? {
        val raw = tvgId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val base = raw.substringBefore('@')
        val suffix = base.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        return normalizeRegion(suffix)
    }

    private fun regionFromName(name: String): String? {
        val upper = " ${name.uppercase(Locale.US)} "
        for ((token, region) in NAME_REGION_PATTERNS) {
            if (upper.contains(" $token ")) return region
        }
        if (Regex("""\|\s*USA\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "US"
        if (Regex("""\|\s*UK\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "UK"
        if (Regex("""\|\s*CA\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "CA"
        return null
    }

    fun normalizeRegion(raw: String?): String? {
        val value = raw?.trim()?.uppercase(Locale.US).orEmpty()
        if (value.isEmpty()) return null
        return REGION_ALIASES[value] ?: value.takeIf { it.length in 2..3 && it.all { ch -> ch.isLetter() } }
    }

    private val LANGUAGE_MARKERS = listOf(
        "deportes", "deportiva", "espanol", "español", "latino", "turk", "türk", "turkiye",
        "francais", "français", "deutsch", "italiano", "portugues", "português", "arabic",
        "russian", "hindi", "mandarin", "cantonese", "japanese", "korean", "polish", "dutch",
        "greek", "hebrew", "svenska", "norsk", "dansk", "suomi",
    )

    private val REGION_NAME_TOKENS = listOf(
        "usa", "us", "uk", "gb", "britain", "canada", "ca", "australia", "au", "mexico", "mx",
        "germany", "de", "france", "fr", "spain", "es", "italy", "it", "turkey", "tr",
        "united states", "united kingdom",
    )

    private val NAME_REGION_PATTERNS = listOf(
        "UNITED STATES" to "US",
        "UNITED KINGDOM" to "UK",
        "USA" to "US",
        "US" to "US",
        "UK" to "UK",
        "GB" to "UK",
        "CANADA" to "CA",
        "CA" to "CA",
        "AUSTRALIA" to "AU",
        "AU" to "AU",
        "MEXICO" to "MX",
        "MX" to "MX",
    )

    private val REGION_ALIASES = mapOf(
        "USA" to "US",
        "US" to "US",
        "GB" to "UK",
        "UK" to "UK",
        "CA" to "CA",
        "CAN" to "CA",
        "AU" to "AU",
        "MX" to "MX",
        "DE" to "DE",
        "FR" to "FR",
        "ES" to "ES",
        "IT" to "IT",
        "TR" to "TR",
        "INT" to "INT",
    )

    private val FLAG_TO_REGION = mapOf(
        "🇺🇸" to "US",
        "🇬🇧" to "UK",
        "🇨🇦" to "CA",
        "🇦🇺" to "AU",
        "🇲🇽" to "MX",
        "🇩🇪" to "DE",
        "🇫🇷" to "FR",
        "🇪🇸" to "ES",
        "🇮🇹" to "IT",
        "🇹🇷" to "TR",
        "🏴" to "UK",
    )
}

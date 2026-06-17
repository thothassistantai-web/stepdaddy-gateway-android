package com.nova.stepdaddylivehd.gateway.upstream

/**
 * Normalizes TiviMate display titles: strip legacy suffixes, fix spelling, append flag + ISO code.
 */
object ChannelTitleNormalizer {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val multiSpaceRe = Regex("\\s+")

    private val spellingFixes = mapOf(
        "Israe" to "Israel",
        "Espanol" to "Español",
        "espanol" to "español",
    )

    fun displayTitle(channelName: String, resolution: GroupTitleResolver.Resolution): String {
        var title = channelName.replace("\"", "'").trim()
        title = categorySuffixRe.replace(title, "").trim()
        title = applySpellingFixes(title)

        if (!resolution.appendCountrySuffix || resolution.countryCode.isBlank()) {
            return title
        }

        title = stripDuplicateCountrySuffix(title, resolution.countryCode, resolution.flagEmoji)
        val flag = resolution.flagEmoji ?: return title
        val suffix = "$flag ${resolution.countryCode}"
        if (title.endsWith(suffix, ignoreCase = false)) return title
        return "$title $suffix".trim()
    }

    private fun stripDuplicateCountrySuffix(title: String, countryCode: String, flagEmoji: String?): String {
        var result = title
        val flag = flagEmoji ?: GroupTitleResolver.flagForCode(countryCode)
        if (flag != null) {
            val withCode = "$flag $countryCode"
            if (result.endsWith(withCode, ignoreCase = false)) {
                return result.dropLast(withCode.length).trimEnd()
            }
            if (result.endsWith(flag, ignoreCase = false)) {
                return result.dropLast(flag.length).trimEnd()
            }
        }
        return result
    }

    private fun applySpellingFixes(title: String): String {
        var result = title
        for ((wrong, right) in spellingFixes) {
            result = result.replace(wrong, right, ignoreCase = false)
        }
        return multiSpaceRe.replace(result, " ").trim()
    }
}

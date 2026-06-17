package com.nova.stepdaddylivehd.gateway.upstream

import java.util.Locale

object TvgIdNormalizer {
    private val dropParts = setOf(
        "hd", "fhd", "sd", "4k", "alternate", "pacific", "east", "west",
        "dummy", "radio", "the", "channel", "plus", "este",
    )
    private val regionRe = Regex(
        "^(us\\d*|us_locals\\d+|uk\\d*|ae\\d*|ca\\d*|au\\d*|[a-z]{2}\\d*)$",
        RegexOption.IGNORE_CASE,
    )
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val parenRe = Regex("\\([^)]*\\)")
    private val tokenRe = Regex("[^a-z0-9]+")
    private val digitLetterRe1 = Regex("([a-z]{2,})(\\d+)\\b")
    private val digitLetterRe2 = Regex("(\\d)([a-z]{2,})")
    private val noiseWordsRe = Regex("\\b(usa|us|uk|hd|fhd|4k|sd|tv|channel|live)\\b")

    fun normalizeChannelName(name: String): String {
        var s = fixMojibake(name)
        s = parenRe.replace(s, " ")
        s = categorySuffixRe.replace(s, "")
        s = s.lowercase(Locale.US)
            .replace("+", " plus ")
            .replace("&", " and ")
        s = digitLetterRe1.replace(s, "$1 $2")
        s = digitLetterRe2.replace(s, "$1 $2")
        s = noiseWordsRe.replace(s, " ")
        s = tokenRe.replace(s, " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    fun normTvgId(tvgId: String): String {
        val parts = mutableListOf<String>()
        for (part in tvgId.split('.')) {
            var pl = parenRe.replace(part.lowercase(Locale.US), "").trim()
            pl = pl.replace("+", " plus ")
            pl = tokenRe.replace(pl, " ").trim()
            if (pl.isEmpty() || pl in dropParts) continue
            if (regionRe.matches(pl)) continue
            if (pl.length <= 2 && pl.all { it.isLetter() }) continue
            parts += pl
        }
        return parts.joinToString(" ")
    }

    fun compact(value: String): String =
        value.lowercase(Locale.US).replace(Regex("\\s+"), "")

    fun fixMojibake(name: String): String {
        var s = name.replace("\uFFFD", "")
        for ((bad, good) in listOf(
            "贸" to "ó",
            "Ã³" to "ó",
            "Ã±" to "ñ",
            "Ã©" to "é",
            "\u8D38" to "",
        )) {
            s = s.replace(bad, good)
        }
        return s
    }
}

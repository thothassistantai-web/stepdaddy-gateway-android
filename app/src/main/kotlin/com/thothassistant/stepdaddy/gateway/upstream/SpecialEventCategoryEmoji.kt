package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Emoji prefix for Special Events guide channel titles by sport/category keyword.
 */
object SpecialEventCategoryEmoji {
    private val keywordEmoji = listOf(
        "college baseball" to "⚾",
        "baseball" to "⚾",
        "mlb" to "⚾",
        "basketball" to "🏀",
        "nba" to "🏀",
        "college basketball" to "🏀",
        "football" to "🏈",
        "nfl" to "🏈",
        "am. football" to "🏈",
        "american football" to "🏈",
        "college football" to "🏈",
        "soccer" to "⚽",
        "football (" to "⚽",
        "hockey" to "🏒",
        "nhl" to "🏒",
        "tennis" to "🎾",
        "golf" to "⛳",
        "swimming" to "🏊",
        "boxing" to "🥊",
        "ufc" to "🥊",
        "mma" to "🥊",
        "wwe" to "🤼",
        "wrestling" to "🤼",
        "rugby" to "🏉",
        "cricket" to "🏏",
        "f1" to "🏎️",
        "formula" to "🏎️",
        "nascar" to "🏁",
        "motorsport" to "🏁",
        "racing" to "🏁",
        "cycling" to "🚴",
        "volleyball" to "🏐",
        "handball" to "🤾",
        "darts" to "🎯",
        "snooker" to "🎱",
        "billiards" to "🎱",
        "ski" to "⛷️",
        "winter" to "❄️",
        "olympic" to "🏅",
        "athletics" to "🏃",
        "track" to "🏃",
        "horse" to "🏇",
        "equestrian" to "🏇",
        "lacrosse" to "🥍",
        "softball" to "🥎",
        "ppv" to "🎟️",
        "live events" to "🔴",
        "live event" to "🔴",
    )

    fun forCategory(category: String, league: String? = null): String {
        val haystack = buildString {
            append(category.trim().lowercase())
            league?.trim()?.lowercase()?.let { append(' ').append(it) }
        }
        if (haystack.isEmpty()) return "📅"
        for ((keyword, emoji) in keywordEmoji) {
            if (keyword in haystack) return emoji
        }
        return "📅"
    }

    fun stripLeadingEmoji(title: String): String {
        var result = title.trim()
        while (result.isNotEmpty()) {
            val codePoint = result.codePointAt(0)
            val type = Character.getType(codePoint)
            val isEmojiLike = type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.SURROGATE.toInt() ||
                type == Character.MODIFIER_SYMBOL.toInt()
            if (!isEmojiLike && result.first() != ' ') break
            result = result.substring(result.offsetByCodePoints(0, 1)).trim()
        }
        return result
    }
}

package com.nova.stepdaddylivehd.gateway.upstream

import com.nova.stepdaddylivehd.gateway.model.Channel

object GroupTitleResolver {
    data class Resolution(
        val groupTitle: String,
        val categoryLabel: String,
        val countryCode: String,
        val isAdult: Boolean,
    ) {
        val sortBucket: Int get() = if (isAdult) 2 else 0
        val categoryOrder: Int get() = CATEGORY_ORDER[categoryLabel] ?: 50
    }

    private val FLAG_TO_CODE = mapOf(
        "🇺🇸" to "US",
        "🇬🇧" to "UK",
        "🇨🇦" to "CA",
        "🇦🇺" to "AU",
        "🇳🇿" to "NZ",
        "🇩🇪" to "DE",
        "🇫🇷" to "FR",
        "🇮🇹" to "IT",
        "🇪🇸" to "ES",
        "🇵🇱" to "PL",
        "🇬🇷" to "GR",
        "🇶🇦" to "QA",
        "🇮🇱" to "IL",
        "🇦🇪" to "AE",
        "🇷🇸" to "RS",
        "🇭🇷" to "HR",
        "🇧🇦" to "BA",
        "🇧🇬" to "BG",
        "🇿🇦" to "ZA",
        "🇩🇰" to "DK",
        "🇵🇹" to "PT",
        "🇲🇽" to "MX",
        "🇸🇪" to "SE",
        "🇨🇿" to "CZ",
        "🇳🇱" to "NL",
        "🇹🇷" to "TR",
        "🇧🇷" to "BR",
        "🇲🇾" to "MY",
        "🇷🇴" to "RO",
        "🇦🇷" to "AR",
        "🇨🇾" to "CY",
        "🇷🇺" to "RU",
        "🇮🇳" to "IN",
        "🇮🇪" to "IE",
        "🇵🇰" to "PK",
        "🇭🇺" to "HU",
        "🇪🇬" to "EG",
        "🏴" to "UK",
        "🇦🇹" to "AT",
        "🇧🇩" to "BD",
        "🇨🇱" to "CL",
        "🇺🇾" to "UY",
        "🇨🇴" to "CO",
        "🌐" to "INT",
        "🌍" to "INT",
    )

    private val CODE_TO_FLAG = FLAG_TO_CODE
        .filterKeys { it != "🌐" && it != "🌍" }
        .entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (_, flags) -> flags.first() }

    private val SPORT_TAGS = setOf(
        "sports", "football", "cricket", "tennis", "motorsport", "f1", "college",
        "golf", "basketball", "hockey", "rugby", "boxing", "mma", "baseball",
    )

    private val ADULT_TAGS = setOf("nsfw", "adult")

    private val IGNORED_TAGS = setOf("hd")

    private val TAG_TO_CATEGORY = mapOf(
        "entertainment" to "Entertainment",
        "general" to "Entertainment",
        "variety" to "Entertainment",
        "drama" to "Entertainment",
        "comedy" to "Entertainment",
        "animation" to "Entertainment",
        "reality" to "Entertainment",
        "series" to "Entertainment",
        "family" to "Entertainment",
        "youth" to "Entertainment",
        "lifestyle" to "Entertainment",
        "arabic" to "Entertainment",
        "spanish" to "Entertainment",
        "movies" to "Movies",
        "action" to "Movies",
        "thriller" to "Movies",
        "horror" to "Movies",
        "classic" to "Movies",
        "romance" to "Movies",
        "news" to "News",
        "local" to "News",
        "international" to "News",
        "politics" to "News",
        "public" to "News",
        "documentary" to "Documentary",
        "crime" to "Documentary",
        "culture" to "Culture",
        "arts" to "Culture",
        "music" to "Music",
        "kids" to "Kids",
        "regional" to "Regional",
        "premium" to "Premium",
        "live" to "Entertainment",
    )

    private val CATEGORY_ORDER = mapOf(
        "Sports" to 0,
        "Entertainment" to 1,
        "Movies" to 2,
        "News" to 3,
        "Documentary" to 4,
        "Culture" to 5,
        "Music" to 6,
        "Kids" to 7,
        "Premium" to 8,
        "Regional" to 9,
        "General" to 100,
        "Adult" to 200,
    )

    /** Longest suffixes first so "New Zealand" matches before " Zealand". */
    private val NAME_COUNTRY_SUFFIXES = listOf(
        " USA" to ("🇺🇸" to "US"),
        " United States" to ("🇺🇸" to "US"),
        " UK" to ("🇬🇧" to "UK"),
        " France" to ("🇫🇷" to "FR"),
        " Germany" to ("🇩🇪" to "DE"),
        " Italy" to ("🇮🇹" to "IT"),
        " Spain" to ("🇪🇸" to "ES"),
        " Poland" to ("🇵🇱" to "PL"),
        " Portugal" to ("🇵🇹" to "PT"),
        " Greece" to ("🇬🇷" to "GR"),
        " Canada" to ("🇨🇦" to "CA"),
        " Australia" to ("🇦🇺" to "AU"),
        " New Zealand" to ("🇳🇿" to "NZ"),
        " Mexico" to ("🇲🇽" to "MX"),
        " Brazil" to ("🇧🇷" to "BR"),
        " Turkey" to ("🇹🇷" to "TR"),
        " Sweden" to ("🇸🇪" to "SE"),
        " Denmark" to ("🇩🇰" to "DK"),
        " Netherlands" to ("🇳🇱" to "NL"),
        " Romania" to ("🇷🇴" to "RO"),
        " Argentina" to ("🇦🇷" to "AR"),
        " Serbia" to ("🇷🇸" to "RS"),
        " Croatia" to ("🇭🇷" to "HR"),
        " Bosnia" to ("🇧🇦" to "BA"),
        " Bulgaria" to ("🇧🇬" to "BG"),
        " Czech Republic" to ("🇨🇿" to "CZ"),
        " Czechia" to ("🇨🇿" to "CZ"),
        " Malaysia" to ("🇲🇾" to "MY"),
        " Pakistan" to ("🇵🇰" to "PK"),
        " India" to ("🇮🇳" to "IN"),
        " Ireland" to ("🇮🇪" to "IE"),
        " Russia" to ("🇷🇺" to "RU"),
        " Egypt" to ("🇪🇬" to "EG"),
        " Austria" to ("🇦🇹" to "AT"),
        " Hungary" to ("🇭🇺" to "HU"),
        " Colombia" to ("🇨🇴" to "CO"),
        " Chile" to ("🇨🇱" to "CL"),
        " Uruguay" to ("🇺🇾" to "UY"),
        " Bangladesh" to ("🇧🇩" to "BD"),
        " Schweiz" to ("🇨🇭" to "CH"),
        " Switzerland" to ("🇨🇭" to "CH"),
        " CA" to ("🇨🇦" to "CA"),
        " DE" to ("🇩🇪" to "DE"),
        " FR" to ("🇫🇷" to "FR"),
        " IT" to ("🇮🇹" to "IT"),
        " ES" to ("🇪🇸" to "ES"),
        " PL" to ("🇵🇱" to "PL"),
        " GR" to ("🇬🇷" to "GR"),
        " QA" to ("🇶🇦" to "QA"),
        " AE" to ("🇦🇪" to "AE"),
        " IL" to ("🇮🇱" to "IL"),
        " NL" to ("🇳🇱" to "NL"),
        " SE" to ("🇸🇪" to "SE"),
        " TR" to ("🇹🇷" to "TR"),
        " BR" to ("🇧🇷" to "BR"),
        " MX" to ("🇲🇽" to "MX"),
        " AU" to ("🇦🇺" to "AU"),
        " NZ" to ("🇳🇿" to "NZ"),
    ).sortedByDescending { it.first.length }

    fun resolve(channelName: String, tags: List<String>): Resolution {
        val hashTags = tags
            .filter { it.startsWith("#") && it.length > 1 }
            .map { it.removePrefix("#").lowercase() }
            .filter { it !in IGNORED_TAGS }

        if (isAdult(channelName, tags, hashTags)) {
            return Resolution(
                groupTitle = "Adult",
                categoryLabel = "Adult",
                countryCode = "",
                isAdult = true,
            )
        }

        val category = resolveCategory(hashTags)
        val (flag, code) = resolveCountry(tags, channelName)

        val groupTitle = when {
            code == "INT" -> "🌐 | INT | $category"
            flag != null -> "$flag | $code | $category"
            else -> "$code | $category"
        }

        return Resolution(
            groupTitle = groupTitle,
            categoryLabel = category,
            countryCode = code,
            isAdult = false,
        )
    }

    fun sortChannels(channels: List<Channel>): List<Channel> =
        channels.sortedWith(channelComparator())

    fun channelComparator(): Comparator<Channel> = Comparator { left, right ->
        val a = resolve(left.name, left.tags)
        val b = resolve(right.name, right.tags)
        compareValuesBy(a, b, { it.sortBucket }, { it.countryCode.ifBlank { "ZZZ" } }, { it.categoryOrder })
            .takeIf { it != 0 }
            ?: left.name.compareTo(right.name, ignoreCase = true)
    }

    private fun isAdult(channelName: String, tags: List<String>, hashTags: List<String>): Boolean {
        if (channelName.startsWith("18+")) return true
        if (tags.any { it == "🔞" }) return true
        return hashTags.any { it in ADULT_TAGS }
    }

    private fun resolveCategory(hashTags: List<String>): String {
        if (hashTags.isEmpty()) return "General"

        if (hashTags.any { it in SPORT_TAGS }) return "Sports"

        if (hashTags.contains("premium")) return "Premium"

        if (hashTags.contains("live") && hashTags.any { it in SPORT_TAGS }) return "Sports"

        for (tag in hashTags) {
            TAG_TO_CATEGORY[tag]?.let { mapped ->
                if (tag == "live" && hashTags.any { it in SPORT_TAGS }) return "Sports"
                return mapped
            }
        }

        val fallback = hashTags.firstOrNull { it !in IGNORED_TAGS }
        if (fallback != null) {
            return fallback.replace("-", " ").split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                    }
                }
        }
        return "General"
    }

    private fun resolveCountry(tags: List<String>, channelName: String): Pair<String?, String> {
        val markers = tags.filter { it.isNotEmpty() && !it.startsWith("#") && it != "🔞" }

        markers.firstOrNull { it == "🌐" || it == "🌍" }?.let {
            return "🌐" to "INT"
        }

        for (marker in markers) {
            FLAG_TO_CODE[marker]?.let { code ->
                if (code == "INT") return "🌐" to "INT"
                return marker to code
            }
        }

        parseCountryFromName(channelName)?.let { return it }

        return "🌐" to "INT"
    }

    private fun parseCountryFromName(channelName: String): Pair<String?, String>? {
        for ((suffix, country) in NAME_COUNTRY_SUFFIXES) {
            if (channelName.endsWith(suffix, ignoreCase = true)) {
                return country.first to country.second
            }
        }
        return null
    }
}

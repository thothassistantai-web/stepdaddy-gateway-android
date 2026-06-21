package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel

object GroupTitleResolver {
    const val LOCAL_CHANNELS = "Local Channels"
    const val ENTERTAINMENT = "Entertainment"
    const val MOVIES = "Movies"
    const val MUSIC = "Music"
    const val KIDS = "Kids"
    const val SPORTS = "Sports"
    const val NEWS = "News"
    const val DOCUMENTARY = "Documentary"
    const val INTERNATIONAL = "International"
    const val EN_ESPANOL = "En Español"
    const val ADULT = "XXX Adult"
    const val EXTRA_247 = "📡 | Extra | 24/7"
    const val SPECIAL_EVENTS = "🎟️ Special Events"

    /** Default sort slot for unknown / legacy group-title labels. */
    const val DEFAULT_GROUP_SORT_ORDER = 50

    data class Resolution(
        val groupTitle: String,
        val categoryLabel: String,
        val countryCode: String,
        val flagEmoji: String?,
        val isAdult: Boolean,
        val appendCountrySuffix: Boolean,
    ) {
        val sortBucket: Int get() = if (isAdult) 2 else 0
        val categoryOrder: Int get() = groupSortOrder(categoryLabel)
    }

    /** TiviMate sidebar order when playlist groups are sorted by playlist order. */
    fun groupSortOrder(groupTitle: String): Int {
        val key = groupTitle.trim()
        GROUP_ORDER[key]?.let { return it }
        GROUP_TITLE_ALIASES[key]?.let { alias -> GROUP_ORDER[alias]?.let { return it } }
        return when {
            key.equals("Locals", ignoreCase = true) -> GROUP_ORDER.getValue(LOCAL_CHANNELS)
            key.equals("Premium", ignoreCase = true) -> GROUP_ORDER.getValue(MOVIES)
            key.startsWith("🌐 | iptv-org") -> GROUP_ORDER.getValue(INTERNATIONAL)
            key.startsWith("🏈 | Sports") -> GROUP_ORDER.getValue(SPECIAL_EVENTS)
            else -> DEFAULT_GROUP_SORT_ORDER
        }
    }

    /** Canonical playlist / sidebar group sequence. */
    val PLAYLIST_GROUP_SEQUENCE: List<String> = listOf(
        ENTERTAINMENT,
        MOVIES,
        LOCAL_CHANNELS,
        NEWS,
        SPORTS,
        KIDS,
        DOCUMENTARY,
        MUSIC,
        EXTRA_247,
        INTERNATIONAL,
        EN_ESPANOL,
        SPECIAL_EVENTS,
        ADULT,
    )

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

    /** TiviMate sidebar order when playlist groups are sorted by playlist order. */
    private val GROUP_ORDER = mapOf(
        ENTERTAINMENT to 0,
        MOVIES to 1,
        LOCAL_CHANNELS to 2,
        NEWS to 3,
        SPORTS to 4,
        KIDS to 5,
        DOCUMENTARY to 6,
        MUSIC to 7,
        EXTRA_247 to 8,
        INTERNATIONAL to 9,
        EN_ESPANOL to 10,
        SPECIAL_EVENTS to 11,
        ADULT to 12,
    )

    /** Supplement / legacy group-title labels that share a canonical sort slot. */
    private val GROUP_TITLE_ALIASES = mapOf(
        "🏈 | Sports | TheTvApp" to SPECIAL_EVENTS,
        "🎟️ | Special Events" to SPECIAL_EVENTS,
        "🎬 | Adult Swim | Marathon" to ENTERTAINMENT,
    )

    private val SPORT_TAGS = setOf(
        "sports", "football", "cricket", "tennis", "motorsport", "f1", "college",
        "golf", "basketball", "hockey", "rugby", "boxing", "mma", "baseball",
    )

    private val MOVIE_TAGS = setOf(
        "movies", "action", "thriller", "horror", "classic", "romance", "mystery",
        "sciencefiction", "indie", "drama",
    )

    private val DOCUMENTARY_TAGS = setOf(
        "documentary", "crime", "culture", "arts", "history",
    )

    private val SPANISH_TAGS = setOf("spanish", "latino", "espanol", "español")

    private val IGNORED_TAGS = setOf("hd")

    private val TAG_TO_CATEGORY = mapOf(
        "entertainment" to ENTERTAINMENT,
        "general" to ENTERTAINMENT,
        "variety" to ENTERTAINMENT,
        "drama" to ENTERTAINMENT,
        "comedy" to ENTERTAINMENT,
        "animation" to ENTERTAINMENT,
        "reality" to ENTERTAINMENT,
        "series" to ENTERTAINMENT,
        "family" to ENTERTAINMENT,
        "youth" to ENTERTAINMENT,
        "lifestyle" to ENTERTAINMENT,
        "arabic" to ENTERTAINMENT,
        "religious" to ENTERTAINMENT,
        "education" to DOCUMENTARY,
        "legislative" to NEWS,
        "business" to NEWS,
        "news" to NEWS,
        "local" to LOCAL_CHANNELS,
        "international" to NEWS,
        "politics" to NEWS,
        "public" to NEWS,
        "documentary" to DOCUMENTARY,
        "crime" to DOCUMENTARY,
        "culture" to DOCUMENTARY,
        "arts" to DOCUMENTARY,
        "history" to DOCUMENTARY,
        "music" to MUSIC,
        "kids" to KIDS,
        "cartoons" to KIDS,
        "regional" to LOCAL_CHANNELS,
        "premium" to ENTERTAINMENT,
        "live" to ENTERTAINMENT,
        "movies" to MOVIES,
        "action" to MOVIES,
        "thriller" to MOVIES,
        "horror" to MOVIES,
        "classic" to MOVIES,
        "romance" to MOVIES,
        "mystery" to MOVIES,
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

    fun flagForCode(code: String): String? = CODE_TO_FLAG[code] ?: if (code == "INT") "🌐" else null

    private val resolveCache = ThreadLocal<MutableMap<ResolveKey, Resolution>?>()

    private data class ResolveKey(val channelId: String?, val name: String, val tags: List<String>)

    /** Memoize [resolve] for one playlist build (cleared when the block returns). */
    fun <T> withResolveCache(block: () -> T): T {
        val cache = mutableMapOf<ResolveKey, Resolution>()
        resolveCache.set(cache)
        return try {
            block()
        } finally {
            resolveCache.set(null)
        }
    }

    fun resolve(channelName: String, tags: List<String>, channelId: String? = null): Resolution {
        resolveCache.get()?.let { cache ->
            val key = ResolveKey(channelId, channelName, tags)
            return cache.getOrPut(key) { resolveUncached(channelId, channelName, tags) }
        }
        return resolveUncached(channelId, channelName, tags)
    }

    private fun resolveUncached(channelId: String?, channelName: String, tags: List<String>): Resolution {
        val hashTags = tags
            .filter { it.startsWith("#") && it.length > 1 }
            .map { it.removePrefix("#").lowercase() }
            .filter { it !in IGNORED_TAGS }

        if (isXxxAdult(channelName, tags, hashTags)) {
            return Resolution(
                groupTitle = ADULT,
                categoryLabel = ADULT,
                countryCode = "",
                flagEmoji = null,
                isAdult = true,
                appendCountrySuffix = false,
            )
        }

        val (flag, code) = resolveCountry(tags, channelName)
        val category = CategoryOverrideStore.overrideGroup(channelId, channelName)
            ?: resolveCategory(hashTags, code, channelName)

        return Resolution(
            groupTitle = category,
            categoryLabel = category,
            countryCode = code,
            flagEmoji = flag,
            isAdult = false,
            appendCountrySuffix = code.isNotBlank(),
        )
    }

    fun sortChannels(channels: List<Channel>): List<Channel> =
        channels.sortedWith(channelComparator())

    fun channelComparator(): Comparator<Channel> = Comparator { left, right ->
        val a = resolve(left.name, left.tags, left.id)
        val b = resolve(right.name, right.tags, right.id)
        compareValuesBy(
            a,
            b,
            { it.sortBucket },
            { it.categoryOrder },
            { ChannelCountrySort.prioritySortKey(it.countryCode) },
            {
                ChannelTitleNormalizer.displayTitle(left.name, a)
                    .compareTo(ChannelTitleNormalizer.displayTitle(right.name, b), ignoreCase = true)
            },
        ).takeIf { it != 0 }
            ?: left.name.compareTo(right.name, ignoreCase = true)
    }

    private fun countrySortKey(code: String): String = ChannelCountrySort.prioritySortKey(code)

    private fun isXxxAdult(channelName: String, tags: List<String>, hashTags: List<String>): Boolean {
        if (channelName.startsWith("18+")) return true
        if (AdultChannelMatcher.matches(channelName)) return true
        if (tags.any { it == "🔞" }) return true
        if (hashTags.contains("nsfw") || hashTags.contains("xxx")) return true
        if (hashTags.contains("adult") && !isMatureCartoon(hashTags)) return true
        return false
    }

    private fun isSpanish(hashTags: List<String>): Boolean =
        hashTags.any { it in SPANISH_TAGS }

    private fun isMatureCartoon(hashTags: List<String>): Boolean =
        (hashTags.contains("animation") || hashTags.contains("cartoons")) &&
            (hashTags.contains("adult") || hashTags.contains("comedy"))

    private fun isKids(hashTags: List<String>): Boolean =
        (hashTags.contains("kids") || hashTags.contains("cartoons")) && !isMatureCartoon(hashTags)

    private fun isPremiumMovieChannel(hashTags: List<String>): Boolean =
        hashTags.contains("premium") && hashTags.any { it in MOVIE_TAGS }

    private fun resolveCategory(
        hashTags: List<String>,
        countryCode: String,
        channelName: String,
    ): String {
        if (isSpanish(hashTags)) return EN_ESPANOL

        if (hashTags.any { it in SPORT_TAGS }) return SPORTS

        if (hashTags.contains("news")) return NEWS

        if (hashTags.contains("local") || hashTags.contains("regional")) return LOCAL_CHANNELS

        if (PremiumMovieChannelMatcher.matches(channelName)) return MOVIES

        if (hashTags.contains("movies")) return MOVIES

        if (isPremiumMovieChannel(hashTags)) return MOVIES

        if (isKids(hashTags)) return KIDS

        if (hashTags.contains("music")) return MUSIC

        if (hashTags.any { it in DOCUMENTARY_TAGS }) return DOCUMENTARY

        for (tag in hashTags) {
            TAG_TO_CATEGORY[tag]?.let { return it }
        }

        return when {
            countryCode == "INT" -> INTERNATIONAL
            countryCode.isNotBlank() && countryCode !in DOMESTIC_CODES -> ENTERTAINMENT
            else -> ENTERTAINMENT
        }
    }

    private val DOMESTIC_CODES = setOf("US", "CA")

    private fun resolveCountry(tags: List<String>, channelName: String): Pair<String?, String> {
        val markers = tags.filter { it.isNotEmpty() && !it.startsWith("#") && it != "🔞" }

        markers.firstOrNull { it == "🌐" || it == "🌍" }?.let {
            return "🌐" to "INT"
        }

        for (marker in markers) {
            FLAG_TO_CODE[marker]?.let { code ->
                if (code == "INT") return "🌐" to "INT"
                val flag = if (marker == "🏴") "🇬🇧" else marker
                return flag to code
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

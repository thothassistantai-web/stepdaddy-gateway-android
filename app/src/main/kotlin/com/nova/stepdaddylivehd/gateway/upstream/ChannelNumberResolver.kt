package com.nova.stepdaddylivehd.gateway.upstream

import com.nova.stepdaddylivehd.gateway.model.Channel

/**
 * Assigns NYC FiOS/Spectrum-inspired `tvg-chno` values per group-title category block.
 * Pinned flagship channels keep stable numbers; remaining channels fill sequentially within ranges.
 */
object ChannelNumberResolver {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val multiSpaceRe = Regex("\\s+")

    /** Playlist fill order — matches [GroupTitleResolver] sidebar sequence. */
    private val GROUP_FILL_ORDER = listOf(
        GroupTitleResolver.LOCAL_CHANNELS,
        GroupTitleResolver.SPORTS,
        GroupTitleResolver.ENTERTAINMENT,
        GroupTitleResolver.MOVIES,
        GroupTitleResolver.NEWS,
        GroupTitleResolver.DOCUMENTARY,
        GroupTitleResolver.MUSIC,
        GroupTitleResolver.KIDS,
        GroupTitleResolver.INTERNATIONAL,
        GroupTitleResolver.EN_ESPANOL,
        GroupTitleResolver.ADULT,
    )

    /** Numeric bands per group-title (FiOS/Spectrum NYC hybrid). */
    private val CATEGORY_RANGES: Map<String, List<IntRange>> = mapOf(
        GroupTitleResolver.LOCAL_CHANNELS to listOf(1..49),
        GroupTitleResolver.SPORTS to listOf(
            26..29,
            48..48,
            53..53,
            70..99,
            300..339,
            380..449,
        ),
        GroupTitleResolver.ENTERTAINMENT to listOf(50..69, 170..209),
        GroupTitleResolver.MOVIES to listOf(340..379, 500..549),
        GroupTitleResolver.NEWS to listOf(100..119),
        GroupTitleResolver.DOCUMENTARY to listOf(120..139),
        GroupTitleResolver.MUSIC to listOf(210..229),
        GroupTitleResolver.KIDS to listOf(250..269),
        GroupTitleResolver.INTERNATIONAL to listOf(1400..1549),
        GroupTitleResolver.EN_ESPANOL to listOf(1500..1699),
        GroupTitleResolver.ADULT to listOf(900..999),
    )

    /** Exact normalized channel name → channel number (group-agnostic). */
    private val NAME_PINS: Map<String, Int> = buildMap {
        // Local Channels — NYC broadcast anchors
        put("cbs usa", 2)
        put("cbsny usa", 2)
        put("nbc usa", 4)
        put("fox usa", 5)
        put("abc usa", 7)
        put("abc ny usa", 7)
        put("cw usa", 11)
        put("cw pix 11 usa", 11)
        put("pbs usa", 13)
        put("cbc ca", 4)

        // Sports — Spectrum RSN + FiOS national anchors
        put("sportsnet new york (sny)", 26)
        put("msg usa", 27)
        put("sportsnet 360", 28)
        put("sportsnet ontario", 28)
        put("sportsnet east", 28)
        put("sportsnet west", 28)
        put("sportsnet one", 28)
        put("tsn1", 29)
        put("tsn2", 29)
        put("tsn3", 29)
        put("tsn4", 29)
        put("tsn5", 29)
        put("espn usa", 70)
        put("espn2 usa", 74)
        put("yes network usa", 53)
        put("fox sports 1 usa", 83)
        put("fox sports 2 usa", 84)
        put("mlb network usa", 86)
        put("nfl network", 88)
        put("cbs sports network (cbssn)", 94)
        put("nba tv usa", 308)
        put("nfl redzone", 335)
        put("big ten network (btn usa)", 382)
        put("sec network usa", 384)
        put("acc network usa", 388)
        put("bein sports max 4 france", 417)

        // Entertainment
        put("usa network", 50)
        put("tnt usa", 51)
        put("tbs usa", 52)
        put("amc usa", 54)
        put("bravo usa", 55)
        put("comedy central", 56)
        put("fx usa", 58)

        // News
        put("cnn usa", 100)
        put("cnbc usa", 102)
        put("msnbc", 103)
        put("bbc news channel hd", 107)
        put("fox news", 118)

        // Documentary
        put("discovery channel", 120)
        put("national geographic (ngc)", 121)
        put("history usa", 128)
        put("animal planet", 130)

        // Music
        put("mtv usa", 210)
        put("cmt usa", 221)

        // Kids
        put("disney channel", 250)
        put("nickelodeon", 252)
        put("nicktoons", 253)
        put("disney xd", 251)
        put("cartoon network", 257)

        // Movies — premium anchors
        put("showtime usa", 365)
        put("starz", 370)
        put("hbo usa", 401)
        put("hbo2 usa", 402)
        put("cinemax usa", 420)

        // En Español
        put("espn deportes", 1520)
        put("fox deportes usa", 1522)
        put("univision", 1502)
        put("telemundo", 1503)

        // International
        put("tv japan", 1500)
    }

    /** Group-specific pins when the same normalized name could map differently. */
    private val GROUP_NAME_PINS: Map<String, Map<String, Int>> = mapOf(
        GroupTitleResolver.ENTERTAINMENT to mapOf(
            "mtv usa" to 57,
        ),
        GroupTitleResolver.MUSIC to mapOf(
            "mtv usa" to 210,
        ),
    )

    /** EPG tvg-id (lowercase) → channel number. */
    private val TVG_ID_PINS: Map<String, Int> = mapOf(
        "wcbs.us" to 2,
        "wcbshd.us" to 2,
        "wnbc.us" to 4,
        "wnbchd.us" to 4,
        "nbc.east.stream.us2" to 4,
        "wnyw.us" to 5,
        "wnywhd.us" to 5,
        "wabc.us" to 7,
        "wabchd.us" to 7,
        "wpix.us" to 11,
        "wpixhd.us" to 11,
        "wnet.us" to 13,
        "wnethd.us" to 13,
        "sny.us" to 26,
        "msg.us" to 27,
        "msgplus.us" to 48,
        "yes.us" to 53,
        "espn.us" to 70,
        "espn2.us" to 74,
        "fs1.us" to 83,
        "fs2.us" to 84,
        "mlbn.us" to 86,
        "nfln.us" to 88,
        "cnn.us" to 100,
        "msnbc.us" to 103,
        "fnc.us" to 118,
        "disney.us" to 250,
        "nick.us" to 252,
        "hbo.us" to 401,
        "sho.us" to 365,
    )

    /** Tag-assisted local affiliate pins: hashtag → (name substring → number). */
    private val TAG_LOCAL_PINS: Map<String, List<Pair<String, Int>>> = mapOf(
        "local" to listOf(
            "cbs" to 2,
            "nbc" to 4,
            "fox" to 5,
            "abc" to 7,
            "cw" to 11,
            "pix 11" to 11,
            "pbs" to 13,
            "wliw" to 21,
            "wnye" to 25,
            "univision" to 41,
            "telemundo" to 47,
        ),
        "regional" to listOf(
            "cbs" to 2,
            "nbc" to 4,
            "fox" to 5,
            "abc" to 7,
            "cw" to 11,
            "pbs" to 13,
        ),
    )

    fun assignAll(channels: List<Channel>): Map<String, Int> {
        val sorted = GroupTitleResolver.sortChannels(channels)
        val occupied = mutableSetOf<Int>()
        val result = linkedMapOf<String, Int>()

        for (channel in sorted) {
            val pin = resolvePin(channel) ?: continue
            if (pin !in occupied) {
                result[channel.id] = pin
                occupied += pin
            }
        }

        for (group in GROUP_FILL_ORDER) {
            val ranges = CATEGORY_RANGES[group] ?: continue
            val groupChannels = sorted.filter { channel ->
                channel.id !in result &&
                    GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle == group
            }
            allocateSequential(groupChannels, ranges, occupied, result)
        }

        return result
    }

    fun numberFor(channel: Channel, channels: List<Channel>): Int =
        assignAll(channels)[channel.id]
            ?: fallbackNumber(channel)

    private fun allocateSequential(
        channels: List<Channel>,
        ranges: List<IntRange>,
        occupied: MutableSet<Int>,
        result: MutableMap<String, Int>,
    ) {
        if (ranges.isEmpty() || channels.isEmpty()) return

        var rangeIndex = 0
        var cursor = ranges[rangeIndex].first

        for (channel in channels) {
            while (true) {
                if (rangeIndex >= ranges.size) {
                    result[channel.id] = cursor
                    occupied += cursor
                    cursor++
                    break
                }
                val range = ranges[rangeIndex]
                if (cursor > range.last) {
                    rangeIndex++
                    if (rangeIndex >= ranges.size) {
                        result[channel.id] = cursor
                        occupied += cursor
                        cursor++
                        break
                    }
                    cursor = ranges[rangeIndex].first
                    continue
                }
                if (cursor !in occupied) {
                    result[channel.id] = cursor
                    occupied += cursor
                    cursor++
                    break
                }
                cursor++
            }
        }
    }

    private fun resolvePin(channel: Channel): Int? {
        val group = GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle
        val normalizedName = normalizeName(channel.name)

        GROUP_NAME_PINS[group]?.get(normalizedName)?.let { return it }
        NAME_PINS[normalizedName]?.let { return it }

        channel.tvgId?.let { normalizeTvgId(it) }?.let { TVG_ID_PINS[it] }?.let { return it }

        matchPinByTags(channel, normalizedName)?.let { return it }

        return matchPartialNamePin(group, normalizedName)
    }

    private fun matchPinByTags(channel: Channel, normalizedName: String): Int? {
        val hashTags = channel.tags
            .filter { it.startsWith("#") && it.length > 1 }
            .map { it.removePrefix("#").lowercase() }

        for (tag in hashTags) {
            val patterns = TAG_LOCAL_PINS[tag] ?: continue
            for ((fragment, number) in patterns) {
                if (normalizedName.contains(fragment) && !isExcludedLocalMatch(normalizedName, fragment)) {
                    return number
                }
            }
        }
        return null
    }

    private fun isExcludedLocalMatch(normalizedName: String, fragment: String): Boolean {
        if (fragment == "fox") {
            return normalizedName.contains("fox sports") ||
                normalizedName.contains("fox news") ||
                normalizedName.contains("fox deportes")
        }
        if (fragment == "nbc") {
            return normalizedName.contains("nbc sports") ||
                normalizedName.contains("nbc universo")
        }
        if (fragment == "cbs") {
            return normalizedName.contains("cbs sports")
        }
        if (fragment == "abc") {
            return normalizedName.contains("abc news")
        }
        return false
    }

    private fun matchPartialNamePin(group: String, normalizedName: String): Int? {
        if (group != GroupTitleResolver.SPORTS) return null
        return when {
            normalizedName.contains("sny") || normalizedName.contains("sportsnet new york") -> 26
            normalizedName.contains("msg plus") -> 48
            normalizedName == "msg usa" || normalizedName.startsWith("msg ") -> 27
            normalizedName.contains("yes network") -> 53
            else -> null
        }
    }

    private fun normalizeName(name: String): String =
        categorySuffixRe.replace(name.trim(), "")
            .lowercase()
            .let { multiSpaceRe.replace(it, " ") }

    private fun normalizeTvgId(tvgId: String): String =
        tvgId.trim().lowercase()

    private fun fallbackNumber(channel: Channel): Int {
        val group = GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle
        val ranges = CATEGORY_RANGES[group] ?: return 1
        return ranges.first().first
    }
}

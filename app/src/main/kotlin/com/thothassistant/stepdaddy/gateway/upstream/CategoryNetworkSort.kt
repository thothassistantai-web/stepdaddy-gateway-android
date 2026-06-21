package com.thothassistant.stepdaddy.gateway.upstream

import java.text.Normalizer

/**
 * Country + network-family ordering for bulk-sorted playlist categories
 * (Local, Sports, Entertainment): US → CA → UK → rest, then grouped by network family.
 */
object CategoryNetworkSort {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val parentheticalRe = Regex(" \\([^)]*\\)")
    private val multiSpaceRe = Regex("\\s+")
    private val flagCountryRe = Regex(" [\uD83C\uDDE6-\uD83C\uDDFF]{2} [A-Z]{2,3}.*$")
    private val providerSuffixRe = Regex(
        "(?i) (pluto|tubi|xumo|roku|samsung|sofast|stirr|distro|firetv|klowdtv|30a|3abn|plex|rakuten|cineversetv|distrotv|freetv|ottera|vidaa|localnow)( .*)?$",
    )

    private val COUNTRY_SUFFIXES = listOf(
        " United States",
        " USA",
        " UK",
        " Canada",
        " CA",
        " France",
        " Germany",
        " Italy",
        " Spain",
        " Poland",
        " West",
    ).sortedByDescending { it.length }

    private val LOCAL_FAMILIES = listOf(
        "abc ny" to "abc",
        "abc" to "abc",
        "cbsny" to "cbs",
        "cbs" to "cbs",
        "nbc" to "nbc",
        "fox" to "fox",
        "cw pix 11" to "cw",
        "cw" to "cw",
        "pbs" to "pbs",
        "wliw" to "wliw",
        "wnye" to "wnye",
        "ion" to "ion",
        "my network tv" to "mytv",
        "mytv" to "mytv",
        "univision" to "univision",
        "telemundo" to "telemundo",
        "cbc" to "cbc",
        "citytv" to "citytv",
        "ctv 2" to "ctv",
        "ctv" to "ctv",
        "global" to "global",
        "noovo" to "noovo",
        "tva" to "tva",
        "tvo" to "tvo",
        "bbc one" to "bbc",
        "bbc two" to "bbc",
        "bbc three" to "bbc",
        "bbc four" to "bbc",
        "itv 1" to "itv",
        "itv 2" to "itv",
        "itv 3" to "itv",
        "itv 4" to "itv",
        "channel 4" to "channel 4",
        "channel 5" to "channel 5",
    ).sortedByDescending { it.first.length }

    private val SPORTS_FAMILIES = listOf(
        "sportsnet new york" to "sny",
        "sportsnet ontario" to "sportsnet",
        "sportsnet east" to "sportsnet",
        "sportsnet west" to "sportsnet",
        "sportsnet one" to "sportsnet",
        "sportsnet 360" to "sportsnet",
        "sportsnet" to "sportsnet",
        "fox sports 2" to "fox sports",
        "fox sports 1" to "fox sports",
        "fox sports" to "fox sports",
        "nbc sports" to "nbc sports",
        "cbs sports network" to "cbs sports",
        "cbs sports" to "cbs sports",
        "big ten network" to "big ten",
        "btn usa" to "big ten",
        "sec network" to "sec",
        "acc network" to "acc",
        "nfl redzone" to "nfl redzone",
        "nfl network" to "nfl",
        "mlb network" to "mlb",
        "nba tv" to "nba",
        "golf channel" to "golf",
        "tennis channel" to "tennis",
        "olympic channel" to "olympic",
        "marquee sports" to "marquee",
        "monumental sports" to "monumental",
        "root sports" to "root",
        "bein sports" to "bein",
        "sky sports" to "sky sports",
        "bt sport" to "bt sport",
        "dazn" to "dazn",
        "f1 tv" to "f1",
        "formula 1" to "f1",
        "yes network" to "yes",
        "msg plus" to "msg",
        "msg" to "msg",
        "tsn5" to "tsn",
        "tsn4" to "tsn",
        "tsn3" to "tsn",
        "tsn2" to "tsn",
        "tsn1" to "tsn",
        "tsn" to "tsn",
        "espn deportes" to "espn",
        "espn2" to "espn",
        "espn" to "espn",
        "sny" to "sny",
    ).sortedByDescending { it.first.length }

    private val ENTERTAINMENT_FAMILIES = listOf(
        "usa network" to "usa network",
        "comedy central" to "comedy central",
        "cartoon network" to "cartoon network",
        "food network" to "food network",
        "travel channel" to "travel channel",
        "history channel" to "history",
        "discovery life channel" to "discovery",
        "discovery channel" to "discovery",
        "destination america" to "discovery",
        "investigation discovery" to "discovery",
        "bbc america" to "bbc",
        "bbc three" to "bbc",
        "bbc two" to "bbc",
        "bbc one" to "bbc",
        "bbc four" to "bbc",
        "sky atlantic" to "sky",
        "sky witness" to "sky",
        "sky arts" to "sky",
        "sky cinema" to "sky",
        "sky one" to "sky",
        "sky two" to "sky",
        "sky news" to "sky",
        "itv 4" to "itv",
        "itv 3" to "itv",
        "itv 2" to "itv",
        "itv 1" to "itv",
        "itvbe" to "itv",
        "channel 5" to "channel 5",
        "channel 4" to "channel 4",
        "fx movie channel" to "fx",
        "fxx" to "fx",
        "fx" to "fx",
        "tnt" to "tnt",
        "tbs" to "tbs",
        "tru tv" to "tru tv",
        "adult swim" to "adult swim",
        "e entertainment television" to "e!",
        "e!" to "e!",
        "freeform" to "freeform",
        "bravo" to "bravo",
        "syfy" to "syfy",
        "amc" to "amc",
        "bet" to "bet",
        "vh1" to "vh1",
        "mtv" to "mtv",
        "cmt" to "cmt",
        "paramount network" to "paramount",
        "paramount" to "paramount",
        "nick at nite" to "nickelodeon",
        "nickelodeon" to "nickelodeon",
        "disney" to "disney",
        "hallmark" to "hallmark",
        "lifetime" to "lifetime",
        "a&e" to "a&e",
        "oxygen" to "oxygen",
        "we tv" to "we tv",
        "ion" to "ion",
        "cw" to "cw",
        "fox" to "fox",
        "nbc" to "nbc",
        "abc" to "abc",
        "cbs" to "cbs",
        "ctv" to "ctv",
        "global" to "global",
        "yes tv" to "yes tv",
        "ytv" to "ytv",
        "noovo" to "noovo",
        "reelz" to "reelz",
        "3abn" to "3abn",
        "30a" to "30a",
    ).sortedByDescending { it.first.length }

    private var normalizeCache: HashMap<String, String>? = null

    private val BULK_SORT_GROUPS = setOf(
        GroupTitleResolver.LOCAL_CHANNELS,
        GroupTitleResolver.SPORTS,
        GroupTitleResolver.ENTERTAINMENT,
    )

    fun beginBatch() {
        normalizeCache = HashMap(8192)
    }

    fun endBatch() {
        normalizeCache = null
    }

    fun isBulkSortedGroup(groupTitle: String): Boolean = groupTitle in BULK_SORT_GROUPS

    /** US → CA → UK → all other countries A→Z. */
    fun countrySortKey(countryCode: String): String {
        val code = ChannelCountrySort.normalizeCode(countryCode)
        return when (code) {
            "US" -> "0"
            "CA" -> "1"
            "UK" -> "2"
            "" -> "9"
            else -> "3$code"
        }
    }

    fun familyKey(groupTitle: String, channelName: String): String {
        val prefixes = when (groupTitle) {
            GroupTitleResolver.LOCAL_CHANNELS -> LOCAL_FAMILIES
            GroupTitleResolver.SPORTS -> SPORTS_FAMILIES
            GroupTitleResolver.ENTERTAINMENT -> ENTERTAINMENT_FAMILIES
            else -> ENTERTAINMENT_FAMILIES
        }
        val norm = normalize(channelName)
        if (norm.isEmpty()) return ""
        for ((prefix, family) in prefixes) {
            if (norm == prefix || norm.startsWith("$prefix ")) return family
        }
        return norm.substringBefore(' ').ifBlank { norm }
    }

    fun normalize(channelName: String): String {
        normalizeCache?.let { cache ->
            return cache.getOrPut(channelName) { normalizeUncached(channelName) }
        }
        return normalizeUncached(channelName)
    }

    fun bulkSortKey(
        groupTitle: String,
        countryCode: String,
        channelName: String,
        orderHint: Int,
        displayTitle: String,
    ): String = buildString(96) {
        append(countrySortKey(countryCode))
        append('\u0000')
        append(familyKey(groupTitle, channelName))
        append('\u0000')
        append(orderHint.toString().padStart(8, '0'))
        append('\u0000')
        append(displayTitle.lowercase())
        append('\u0000')
        append(channelName.lowercase())
    }

    private fun normalizeUncached(channelName: String): String {
        var name = categorySuffixRe.replace(channelName.trim(), "").trim()
        name = parentheticalRe.replace(name, "").trim()
        name = flagCountryRe.replace(name, "").trim()
        name = providerSuffixRe.replace(name, "").trim()
        for (suffix in COUNTRY_SUFFIXES) {
            if (name.endsWith(suffix, ignoreCase = true)) {
                name = name.dropLast(suffix.length).trim()
                break
            }
        }
        name = name.replace("/", " ")
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return multiSpaceRe.replace(ascii.lowercase(), " ").trim()
    }
}

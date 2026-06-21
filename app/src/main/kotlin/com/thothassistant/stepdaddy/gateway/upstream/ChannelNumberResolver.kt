package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Assigns `tvg-chno` values per group-title category.
 * Local, Sports, and Entertainment are fully bulk-sorted (US → CA → UK → rest, network families, no pin exceptions).
 * Other categories keep FiOS/Spectrum-inspired pins and sequential fill.
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

    /** Bulk-sorted categories — skipped during pin + sequential passes. */
    private val BULK_SORT_GROUPS = listOf(
        GroupTitleResolver.LOCAL_CHANNELS,
        GroupTitleResolver.SPORTS,
        GroupTitleResolver.ENTERTAINMENT,
    )

    /** Numeric bands per group-title (FiOS/Spectrum NYC hybrid). */
    private val CATEGORY_RANGES: Map<String, List<IntRange>> = mapOf(
        GroupTitleResolver.LOCAL_CHANNELS to emptyList(),
        GroupTitleResolver.SPORTS to emptyList(),
        GroupTitleResolver.ENTERTAINMENT to emptyList(),
        GroupTitleResolver.MOVIES to listOf(340..379),
        GroupTitleResolver.NEWS to listOf(100..119),
        GroupTitleResolver.DOCUMENTARY to listOf(120..139),
        GroupTitleResolver.MUSIC to listOf(210..229),
        GroupTitleResolver.KIDS to listOf(250..269),
        GroupTitleResolver.INTERNATIONAL to listOf(1400..1549),
        GroupTitleResolver.EN_ESPANOL to listOf(1500..1699),
        GroupTitleResolver.ADULT to listOf(900..999),
    )

    private val LOCAL_BULK_RANGES = listOf(1..499)
    private val SPORTS_BULK_RANGES = listOf(500..1599)
    private val ENTERTAINMENT_BULK_RANGES = listOf(1600..3999)

    private val BULK_RANGES_BY_GROUP = mapOf(
        GroupTitleResolver.LOCAL_CHANNELS to LOCAL_BULK_RANGES,
        GroupTitleResolver.SPORTS to SPORTS_BULK_RANGES,
        GroupTitleResolver.ENTERTAINMENT to ENTERTAINMENT_BULK_RANGES,
    )

    /** Exact normalized channel name → channel number (non-bulk categories only). */
    private val NAME_PINS: Map<String, Int> = buildMap {
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
        GroupTitleResolver.MUSIC to mapOf(
            "mtv usa" to 210,
        ),
    )

    /** EPG tvg-id (lowercase) → channel number (non-bulk categories). */
    private val TVG_ID_PINS: Map<String, Int> = mapOf(
        "cnn.us" to 100,
        "msnbc.us" to 103,
        "fnc.us" to 118,
        "disney.us" to 250,
        "nick.us" to 252,
        "hbo.us" to 401,
        "sho.us" to 365,
    )

    fun assignAll(channels: List<Channel>): Map<String, Int> =
        assignPlaylist(channels, emptyList()).first

    fun assignPlaylist(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
    ): Pair<Map<String, Int>, Map<String, Int>> = GroupTitleResolver.withResolveCache {
        CategoryNetworkSort.beginBatch()
        try {
            assignPlaylistUncached(channels, supplements)
        } finally {
            CategoryNetworkSort.endBatch()
        }
    }

    private fun assignPlaylistUncached(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
    ): Pair<Map<String, Int>, Map<String, Int>> {
        val sorted = GroupTitleResolver.sortChannels(channels)
        val occupied = mutableSetOf<Int>()
        val channelResult = linkedMapOf<String, Int>()
        val supplementResult = linkedMapOf<String, Int>()

        for (channel in sorted) {
            val pin = resolvePin(channel) ?: continue
            if (pin !in occupied) {
                channelResult[channel.id] = pin
                occupied += pin
            }
        }

        for (group in BULK_SORT_GROUPS) {
            val ranges = BULK_RANGES_BY_GROUP[group] ?: continue
            allocateBulkCategoryBlock(
                groupTitle = group,
                channels = sorted,
                supplements = supplements,
                channelResult = channelResult,
                supplementResult = supplementResult,
                ranges = ranges,
                occupied = occupied,
            )
        }

        for (group in GROUP_FILL_ORDER) {
            if (group in BULK_SORT_GROUPS) continue
            val ranges = CATEGORY_RANGES[group] ?: continue

            val groupChannels = sorted
                .filter { channel ->
                    channel.id !in channelResult &&
                        GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle == group
                }
                .sortedWith(channelCountryNameComparator())
            allocateSequential(groupChannels, ranges, occupied, channelResult)

            val groupSupplements = supplements
                .filter {
                    it.id !in supplementResult && supplementGroup(it) == group
                }
                .sortedWith(supplementCountryNameComparator())
            allocateSupplementSequential(groupSupplements, ranges, occupied, supplementResult)
        }

        val unassigned = supplements.filter { it.id !in supplementResult }
        if (unassigned.isNotEmpty()) {
            var cursor = (occupied.maxOrNull() ?: 0) + 1
            for (supplement in unassigned.sortedWith(supplementCountryNameComparator())) {
                while (cursor in occupied) cursor++
                supplementResult[supplement.id] = cursor
                occupied += cursor
                cursor++
            }
        }

        return channelResult to supplementResult
    }

    fun assignSupplements(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        groupFor: (SupplementChannel) -> String,
        channelNumbers: Map<String, Int> = assignAll(channels),
    ): Map<String, Int> {
        if (groupFor == ::supplementGroup) {
            return assignPlaylist(channels, supplements).second
        }
        return assignSupplementsLegacy(channels, supplements, groupFor, channelNumbers)
    }

    private fun assignSupplementsLegacy(
        @Suppress("UNUSED_PARAMETER") channels: List<Channel>,
        supplements: List<SupplementChannel>,
        groupFor: (SupplementChannel) -> String,
        channelNumbers: Map<String, Int>,
    ): Map<String, Int> {
        val occupied = channelNumbers.values.toMutableSet()
        val result = linkedMapOf<String, Int>()

        for (group in GROUP_FILL_ORDER) {
            val ranges = CATEGORY_RANGES[group] ?: continue
            val groupSupplements = supplements
                .filter { groupFor(it) == group }
                .sortedWith(supplementCountryNameComparator())
            allocateSupplementSequential(groupSupplements, ranges, occupied, result)
        }

        val unassigned = supplements.filter { it.id !in result }
        if (unassigned.isNotEmpty()) {
            var cursor = (occupied.maxOrNull() ?: 0) + 1
            for (supplement in unassigned.sortedWith(supplementCountryNameComparator())) {
                while (cursor in occupied) cursor++
                result[supplement.id] = cursor
                occupied += cursor
                cursor++
            }
        }
        return result
    }

    fun supplementGroup(supplement: SupplementChannel): String =
        when {
            supplement.id.startsWith("sport:") -> GroupTitleResolver.SPORTS
            supplement.id.startsWith("iptv:") && supplement.tags.isNotEmpty() ->
                GroupTitleResolver.resolve(supplement.name, supplement.tags).groupTitle
            supplement.id.startsWith("sup:") -> supplement.groupTitle
            supplement.id.startsWith("ntv:") -> supplement.groupTitle
            supplement.id.startsWith("adultswim:") -> supplement.groupTitle
            else -> GroupTitleResolver.ENTERTAINMENT
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

    private fun allocateSupplementSequential(
        supplements: List<SupplementChannel>,
        ranges: List<IntRange>,
        occupied: MutableSet<Int>,
        result: MutableMap<String, Int>,
    ) {
        if (ranges.isEmpty() || supplements.isEmpty()) return

        var rangeIndex = 0
        var cursor = ranges[rangeIndex].first

        for (supplement in supplements) {
            while (true) {
                if (rangeIndex >= ranges.size) {
                    result[supplement.id] = cursor
                    occupied += cursor
                    cursor++
                    break
                }
                val range = ranges[rangeIndex]
                if (cursor > range.last) {
                    rangeIndex++
                    if (rangeIndex >= ranges.size) {
                        result[supplement.id] = cursor
                        occupied += cursor
                        cursor++
                        break
                    }
                    cursor = ranges[rangeIndex].first
                    continue
                }
                if (cursor !in occupied) {
                    result[supplement.id] = cursor
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
        if (CategoryNetworkSort.isBulkSortedGroup(group)) return null
        val normalizedName = normalizeName(channel.name)

        GROUP_NAME_PINS[group]?.get(normalizedName)?.let { return it }
        NAME_PINS[normalizedName]?.let { return it }

        channel.tvgId?.let { normalizeTvgId(it) }?.let { TVG_ID_PINS[it] }?.let { return it }

        return null
    }

    private fun normalizeName(name: String): String =
        categorySuffixRe.replace(name.trim(), "")
            .lowercase()
            .let { multiSpaceRe.replace(it, " ") }

    private fun normalizeTvgId(tvgId: String): String =
        tvgId.trim().lowercase()

    private fun fallbackNumber(channel: Channel): Int {
        val group = GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle
        BULK_RANGES_BY_GROUP[group]?.firstOrNull()?.first?.let { return it }
        val ranges = CATEGORY_RANGES[group] ?: return 1
        return ranges.first().first
    }

    private fun channelCountryNameComparator(): Comparator<Channel> =
        compareBy(
            {
                ChannelCountrySort.prioritySortKey(
                    GroupTitleResolver.resolve(it.name, it.tags).countryCode,
                )
            },
            {
                val resolution = GroupTitleResolver.resolve(it.name, it.tags)
                ChannelTitleNormalizer.displayTitle(it.name, resolution).lowercase()
            },
            { it.name.lowercase() },
        )

    private fun supplementCountryNameComparator(): Comparator<SupplementChannel> =
        compareBy(
            { ChannelCountrySort.prioritySortKey(supplementCountryCode(it)) },
            { it.name.lowercase() },
        )

    private fun supplementCountryCode(supplement: SupplementChannel): String {
        val resolution = GroupTitleResolver.resolve(supplement.name, supplement.tags)
        val code = resolution.countryCode
            .takeIf { it.isNotBlank() && it != "INT" }
            ?: GroupTitleResolver.resolve(supplement.name, emptyList()).countryCode
        return ChannelCountrySort.normalizeCode(code)
    }

    private sealed class BulkSortEntry {
        abstract val sortKey: String

        data class ChannelEntry(
            val channel: Channel,
            override val sortKey: String,
        ) : BulkSortEntry()

        data class SupplementEntry(
            val supplement: SupplementChannel,
            override val sortKey: String,
        ) : BulkSortEntry()
    }

    private fun allocateBulkCategoryBlock(
        groupTitle: String,
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        channelResult: MutableMap<String, Int>,
        supplementResult: MutableMap<String, Int>,
        ranges: List<IntRange>,
        occupied: MutableSet<Int>,
    ) {
        if (ranges.isEmpty()) return

        val groupChannels = channels.filter {
            GroupTitleResolver.resolve(it.name, it.tags).groupTitle == groupTitle
        }
        val groupSupplements = supplements.filter { supplementGroup(it) == groupTitle }

        releaseBulkCategoryNumbers(
            groupChannels = groupChannels,
            groupSupplements = groupSupplements,
            channelResult = channelResult,
            supplementResult = supplementResult,
            occupied = occupied,
        )

        val unpinnedChannels = groupChannels.filter { it.id !in channelResult }

        val channelOrder = unpinnedChannels
            .sortedWith(channelCountryNameComparator())
            .mapIndexed { index, channel -> channel.id to index }
            .toMap()
        val supplementOrder = groupSupplements
            .sortedWith(supplementCountryNameComparator())
            .mapIndexed { index, supplement -> supplement.id to (50_000 + index) }
            .toMap()

        val entries = buildList {
            unpinnedChannels.forEach { channel ->
                val resolution = GroupTitleResolver.resolve(channel.name, channel.tags)
                val displayTitle = ChannelTitleNormalizer.displayTitle(channel.name, resolution)
                add(
                    BulkSortEntry.ChannelEntry(
                        channel = channel,
                        sortKey = CategoryNetworkSort.bulkSortKey(
                            groupTitle = groupTitle,
                            countryCode = resolution.countryCode,
                            channelName = channel.name,
                            orderHint = channelOrder[channel.id] ?: Int.MAX_VALUE,
                            displayTitle = displayTitle,
                        ),
                    ),
                )
            }
            groupSupplements.forEach { supplement ->
                add(
                    BulkSortEntry.SupplementEntry(
                        supplement = supplement,
                        sortKey = CategoryNetworkSort.bulkSortKey(
                            groupTitle = groupTitle,
                            countryCode = supplementCountryCode(supplement),
                            channelName = supplement.name,
                            orderHint = supplementOrder[supplement.id] ?: Int.MAX_VALUE,
                            displayTitle = supplement.name,
                        ),
                    ),
                )
            }
        }.sortedBy { it.sortKey }

        var rangeIndex = 0
        var cursor = ranges[rangeIndex].first

        for (entry in entries) {
            while (true) {
                if (rangeIndex >= ranges.size) {
                    assignBulkSortNumber(entry, cursor, channelResult, supplementResult, occupied)
                    cursor++
                    break
                }
                val range = ranges[rangeIndex]
                if (cursor > range.last) {
                    rangeIndex++
                    if (rangeIndex >= ranges.size) {
                        assignBulkSortNumber(entry, cursor, channelResult, supplementResult, occupied)
                        cursor++
                        break
                    }
                    cursor = ranges[rangeIndex].first
                    continue
                }
                if (cursor !in occupied) {
                    assignBulkSortNumber(entry, cursor, channelResult, supplementResult, occupied)
                    cursor++
                    break
                }
                cursor++
            }
        }
    }

    private fun releaseBulkCategoryNumbers(
        groupChannels: List<Channel>,
        groupSupplements: List<SupplementChannel>,
        channelResult: MutableMap<String, Int>,
        supplementResult: MutableMap<String, Int>,
        occupied: MutableSet<Int>,
    ) {
        for (channel in groupChannels) {
            val number = channelResult.remove(channel.id) ?: continue
            occupied.remove(number)
        }
        for (supplement in groupSupplements) {
            val number = supplementResult.remove(supplement.id) ?: continue
            occupied.remove(number)
        }
    }

    private fun assignBulkSortNumber(
        entry: BulkSortEntry,
        number: Int,
        channelResult: MutableMap<String, Int>,
        supplementResult: MutableMap<String, Int>,
        occupied: MutableSet<Int>,
    ) {
        when (entry) {
            is BulkSortEntry.ChannelEntry -> channelResult[entry.channel.id] = number
            is BulkSortEntry.SupplementEntry -> supplementResult[entry.supplement.id] = number
        }
        occupied += number
    }
}

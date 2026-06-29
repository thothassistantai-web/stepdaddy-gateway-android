package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventMetadata
import com.thothassistant.stepdaddy.gateway.model.EventMetadataSource
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Scrapes title, slug, region, league, and sport type from DaddyLive `tv.json` /
 * `tv2.json` rows, TheTvApp embed pages, and cached supplement channel fields.
 */
object EventMetadataScraper {
    private val regionPrefixRe = Regex("""^(US|UK|CA|FR|ES|DE|IT|PT|AU|MX|BR)\s*:""", RegexOption.IGNORE_CASE)
    private val scheduleRegionRe = Regex(
        """\b(?:Schedule\s+Time\s+)?(UK|US|CA|AU|FR|ES|DE|IT|PT|MX|BR)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val categoryRegionHints = listOf(
        "canadian" to "CA",
        "canada" to "CA",
        "quebec" to "CA",
        "british" to "UK",
        "england" to "UK",
        "scotland" to "UK",
        "wales" to "UK",
        "australian" to "AU",
        "australia" to "AU",
        "mexican" to "MX",
        "mexico" to "MX",
        "french" to "FR",
        "france" to "FR",
        "spanish" to "ES",
        "spain" to "ES",
        "german" to "DE",
        "germany" to "DE",
        "italian" to "IT",
        "italy" to "IT",
        "portuguese" to "PT",
        "portugal" to "PT",
        "brazilian" to "BR",
        "brazil" to "BR",
    )

    fun scrapeChannels(channels: List<SupplementChannel>): Map<String, EventMetadata> =
        buildMap {
            channels.forEach { channel ->
                fromSupplementChannel(channel)?.let { put(channel.id, it) }
            }
        }

    fun fromDlhdParsedEvent(
        event: DaddyLiveEventResolver.ParsedEvent,
        channelId: String,
        streamLabel: String? = null,
    ): EventMetadata {
        val source = when (event.streams.firstOrNull()?.source) {
            DaddyLiveEventResolver.StreamSource.TV2 -> EventMetadataSource.DLHD_TV2
            DaddyLiveEventResolver.StreamSource.TV,
            null,
            -> EventMetadataSource.DLHD_TV
        }
        val title = event.title.trim()
        val league = SpecialEventSort.normalizeLeague(event.league)
        val region = resolveRegion(
            dateKey = event.dateKey,
            category = event.category,
            title = title,
            streamLabel = streamLabel,
            languageCode = null,
        )
        return EventMetadata(
            title = title,
            slug = eventSlug(title, event.category, channelId),
            region = region,
            league = league,
            sportType = sportTypeFor(league, event.category, title),
            source = source,
            category = event.category.trim().ifEmpty { null },
            dateKey = event.dateKey.trim().ifEmpty { null },
            timeLabel = event.timeLabel.trim().ifEmpty { null },
            eventSourceUrl = "${event.category}|${event.dateKey}|${event.timeLabel}|$title",
            streamLabel = streamLabel?.trim()?.ifEmpty { null },
            languageCode = SpecialEventLanguageIdentifier.identify(
                SpecialEventLanguageIdentifier.Context(
                    eventTitle = title,
                    streamLabel = streamLabel.orEmpty(),
                    category = event.category,
                    league = league,
                ),
            ),
        )
    }

    fun fromSupplementChannel(channel: SupplementChannel): EventMetadata? = when {
        channel.id.startsWith("dlhd-guide:") -> fromDlhdGuide(channel)
        channel.id.startsWith("dlhd-event:") -> fromDlhdEventChannel(channel)
        channel.id.startsWith("sport:") -> fromTheTvAppChannel(channel)
        else -> null
    }

    private fun fromDlhdGuide(channel: SupplementChannel): EventMetadata {
        val category = SpecialEventCategoryEmoji.stripLeadingEmoji(
            channel.name.removeSuffix(" Schedule").trim(),
        )
        val league = SpecialEventSort.normalizeLeague(channel.providerTag.orEmpty())
        val slug = channel.id.removePrefix("dlhd-guide:")
        return EventMetadata(
            title = category,
            slug = slug,
            region = resolveRegion(category = category, title = category, languageCode = channel.languageCode),
            league = league,
            sportType = sportTypeFor(league, category, category),
            source = EventMetadataSource.DLHD_TV,
            category = category,
            languageCode = channel.languageCode,
        )
    }

    private fun fromDlhdEventChannel(channel: SupplementChannel): EventMetadata {
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl)
        val title = meta?.title?.trim().orEmpty().ifEmpty { channel.name.trim() }
        val category = meta?.category?.trim().orEmpty()
        val league = SpecialEventSort.normalizeLeague(
            channel.providerTag?.trim().orEmpty().ifEmpty {
                if (category.isNotEmpty() && title.isNotEmpty()) {
                    SpecialEventSort.leagueFromCategoryOrTitle(category, title)
                } else {
                    ""
                }
            },
        )
        val streamLabel = streamLabelFromChannelName(title, channel.name)
        val source = when {
            channel.dlhdEventStreamKey?.startsWith("tv2|", ignoreCase = true) == true ->
                EventMetadataSource.DLHD_TV2
            else -> EventMetadataSource.DLHD_TV
        }
        return EventMetadata(
            title = title,
            slug = eventSlug(title, category, channel.id),
            region = resolveRegion(
                dateKey = meta?.dateKey,
                category = category,
                title = title,
                streamLabel = streamLabel,
                languageCode = channel.languageCode,
            ),
            league = league,
            sportType = sportTypeFor(league, category, title),
            source = source,
            category = category.ifEmpty { null },
            dateKey = meta?.dateKey,
            timeLabel = meta?.timeLabel,
            eventSourceUrl = channel.eventSourceUrl,
            streamLabel = streamLabel,
            languageCode = channel.languageCode ?: SpecialEventLanguageIdentifier.identify(
                SpecialEventLanguageIdentifier.Context(
                    eventTitle = title,
                    streamLabel = streamLabel.orEmpty(),
                    category = category,
                    league = league,
                    eventSourceUrl = channel.eventSourceUrl,
                ),
            ),
        )
    }

    private fun fromTheTvAppChannel(channel: SupplementChannel): EventMetadata {
        val eventUrl = channel.eventSourceUrl.orEmpty()
        val title = channel.name.trim()
        val league = SpecialEventSort.normalizeLeague(
            channel.providerTag?.trim().orEmpty().ifEmpty {
                if (eventUrl.isNotEmpty()) SpecialEventSort.leagueFromEventUrl(eventUrl) else ""
            },
        )
        val slug = theTvAppSlug(eventUrl).ifEmpty { SpecialEventsMerger.slugify(title) }
        return EventMetadata(
            title = title,
            slug = slug,
            region = resolveRegion(
                title = title,
                eventSourceUrl = eventUrl,
                languageCode = channel.languageCode,
            ),
            league = league,
            sportType = sportTypeFor(league, category = league, title = title),
            source = EventMetadataSource.THE_TV_APP,
            eventSourceUrl = eventUrl.ifEmpty { null },
            languageCode = channel.languageCode ?: SpecialEventLanguageIdentifier.identify(
                SpecialEventLanguageIdentifier.Context(
                    eventTitle = title,
                    league = league,
                    eventSourceUrl = eventUrl,
                    siteLocale = "en",
                ),
            ),
        )
    }

    fun sportTypeFor(league: String, category: String, title: String): String {
        val haystack = "$league $category $title".lowercase()
        return when {
            haystack.contains("basketball") || league == "NBA" || haystack.contains("wnba") -> "Basketball"
            haystack.contains("baseball") || league == "MLB" -> "Baseball"
            haystack.contains("hockey") || league == "NHL" -> "Ice Hockey"
            haystack.contains("nascar") || league == "NASCAR" -> "Motorsport"
            haystack.contains("formula") || league == "F1" || haystack.contains("grand prix") -> "Motorsport"
            haystack.contains("motorsport") || haystack.contains("racing") -> "Motorsport"
            haystack.contains("tennis") || league == "TENNIS" || haystack.contains("wimbledon") -> "Tennis"
            haystack.contains("golf") || league == "GOLF" -> "Golf"
            haystack.contains("boxing") || league == "BOXING" -> "Boxing"
            haystack.contains("ufc") || haystack.contains("mma") || league == "UFC" -> "MMA"
            haystack.contains("wwe") || haystack.contains("wrestling") || league == "WWE" -> "Wrestling"
            haystack.contains("rugby") || league == "RUGBY" -> "Rugby"
            haystack.contains("cricket") || league == "CRICKET" -> "Cricket"
            haystack.contains("soccer") || league == "MLS" || league == "SOCCER" ||
                haystack.contains("premier league") || haystack.contains("la liga") ||
                haystack.contains("champions league") || haystack.contains("football (") -> "Soccer"
            haystack.contains("swimming") -> "Swimming"
            haystack.contains("cycling") -> "Cycling"
            haystack.contains("volleyball") -> "Volleyball"
            haystack.contains("ski") || haystack.contains("winter") -> "Winter Sports"
            haystack.contains("olympic") || haystack.contains("athletics") -> "Multi-sport"
            league == "NFL" || haystack.contains("nfl") -> "American Football"
            league == "NCAA" && haystack.contains("football") -> "American Football"
            league == "NCAA" && haystack.contains("basketball") -> "Basketball"
            league == "NCAA" && haystack.contains("baseball") -> "Baseball"
            league == "NCAA" -> "College Sports"
            haystack.contains("football") && !haystack.contains("soccer") -> "American Football"
            else -> category.trim().ifEmpty { league }.ifEmpty { "Sports" }
        }
    }

    fun resolveRegion(
        dateKey: String? = null,
        category: String = "",
        title: String = "",
        streamLabel: String? = null,
        eventSourceUrl: String? = null,
        languageCode: String? = null,
    ): String {
        regionPrefixRe.find(title)?.groupValues?.getOrNull(1)?.uppercase()?.let { return it }
        regionPrefixRe.find(category)?.groupValues?.getOrNull(1)?.uppercase()?.let { return it }
        dateKey?.let { scheduleRegionRe.find(it)?.groupValues?.getOrNull(1)?.uppercase() }?.let { return it }
        if (dateKey?.contains("UK GMT", ignoreCase = true) == true) return "UK"
        if (dateKey?.contains("US ", ignoreCase = true) == true) return "US"

        val corpus = listOf(category, title, streamLabel.orEmpty()).joinToString(" ").lowercase()
        for ((hint, code) in categoryRegionHints) {
            if (hint in corpus) return code
        }

        languageCode?.trim()?.lowercase()?.let { lang ->
            when {
                lang.startsWith("fr") -> return "FR"
                lang.startsWith("es") -> return "ES"
                lang.startsWith("de") -> return "DE"
                lang.startsWith("it") -> return "IT"
                lang.startsWith("pt") -> return "PT"
                else -> Unit
            }
        }

        eventSourceUrl?.let { localeFromTheTvAppUrl(it) }?.let { return it }

        return "US"
    }

    private fun localeFromTheTvAppUrl(url: String): String? {
        val path = url.substringAfter("thetvapp.link/", "").trim('/').lowercase()
        val segment = path.substringBefore('/')
        return when (segment) {
            "fr", "fra" -> "FR"
            "es", "esp" -> "ES"
            "de", "deu" -> "DE"
            "it", "ita" -> "IT"
            "pt", "por" -> "PT"
            "ca" -> "CA"
            "uk" -> "UK"
            "au" -> "AU"
            "mx" -> "MX"
            "br" -> "BR"
            else -> null
        }
    }

    fun theTvAppSlug(eventUrl: String): String {
        val path = eventUrl.substringAfter("thetvapp.link/", "").trim('/')
        if (path.isEmpty()) return ""
        val withoutId = path.substringBeforeLast('/').trim('/')
        return withoutId.ifEmpty { path.substringBefore('/') }
    }

    private fun eventSlug(title: String, category: String, channelId: String): String {
        val fromTitle = SpecialEventsMerger.slugify(title.substringAfter(": ", title))
        if (fromTitle != "events") return fromTitle
        val fromCategory = SpecialEventsMerger.slugify(category)
        if (fromCategory != "events") return fromCategory
        return channelId.substringAfter(':')
    }

    private fun streamLabelFromChannelName(eventTitle: String, channelName: String): String? {
        val core = eventTitle.substringAfter(": ", eventTitle).trim().ifEmpty { eventTitle.trim() }
        val name = channelName.trim()
        if (!name.contains('(') || !name.endsWith(')')) return null
        val suffix = name.substringAfterLast('(').removeSuffix(")").trim()
        if (suffix.isEmpty() || suffix.equals(core, ignoreCase = true)) return null
        return suffix
    }
}

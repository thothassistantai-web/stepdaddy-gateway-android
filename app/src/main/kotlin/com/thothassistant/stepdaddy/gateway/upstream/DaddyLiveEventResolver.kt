package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parses DaddyLive homepage schedule feeds (`cache/tv/tv.json`, `cache/tv2/tv2.json`).
 */
class DaddyLiveEventResolver(
    private val httpClient: OkHttpClient = defaultClient(),
) {
    data class ResolveStats(
        val tvEvents: Int = 0,
        val tv2Events: Int = 0,
        val streamLinks: Int = 0,
    )

    data class ParsedEvent(
        val category: String,
        val dateKey: String,
        val timeLabel: String,
        val title: String,
        val league: String,
        val streams: List<ParsedStream>,
        val live: Boolean,
    )

    data class ParsedStream(
        val label: String,
        val channelId: String,
        val source: StreamSource,
    )

    enum class StreamSource { TV, TV2 }

    @Serializable
    private data class ScheduleEvent(
        val time: String = "",
        val event: String = "",
        val channels: List<ScheduleChannel> = emptyList(),
        @SerialName("channels2")
        val channels2: List<ScheduleChannel> = emptyList(),
    )

    @Serializable
    private data class ScheduleChannel(
        @SerialName("channel_name")
        val channelName: String = "",
        @SerialName("channel_id")
        val channelId: String = "",
    )

    fun resolveFromNetwork(dlhdBaseUrl: String): Pair<List<ParsedEvent>, ResolveStats> {
        val base = dlhdBaseUrl.trimEnd('/')
        val tvJson = fetchJson(SupplementConfig.dlhdTvJsonUrl(base))
        val tv2Json = fetchJson(SupplementConfig.dlhdTv2JsonUrl(base))
        return parseFeeds(tvJson, tv2Json)
    }

    fun parseFeeds(
        tvJson: String?,
        tv2Json: String?,
    ): Pair<List<ParsedEvent>, ResolveStats> {
        val json = Json { ignoreUnknownKeys = true }
        val tvRoot = tvJson?.let { runCatching { json.decodeFromString<Map<String, Map<String, List<ScheduleEvent>>>>(it) }.getOrNull() }
        val tv2Root = tv2Json?.let { runCatching { json.decodeFromString<Map<String, Map<String, List<ScheduleEvent>>>>(it) }.getOrNull() }

        val events = mutableListOf<ParsedEvent>()
        var tvCount = 0
        var tv2Count = 0
        var links = 0

        tvRoot?.forEach { (dateKey, categories) ->
            categories.forEach { (category, rows) ->
                rows.forEach { row ->
                    val parsed = toParsedEvent(
                        category = category,
                        dateKey = dateKey,
                        row = row,
                        defaultSource = StreamSource.TV,
                    )
                    if (parsed == null) return@forEach
                    tvCount++
                    links += parsed.streams.size
                    events += parsed
                }
            }
        }

        tv2Root?.forEach { (dateKey, categories) ->
            categories.forEach { (category, rows) ->
                rows.forEach { row ->
                    val parsed = toParsedEvent(
                        category = category,
                        dateKey = dateKey,
                        row = row,
                        defaultSource = StreamSource.TV2,
                    )
                    if (parsed == null) return@forEach
                    tv2Count++
                    links += parsed.streams.size
                    events += parsed
                }
            }
        }

        return events to ResolveStats(tvEvents = tvCount, tv2Events = tv2Count, streamLinks = links)
    }

    private fun toParsedEvent(
        category: String,
        dateKey: String,
        row: ScheduleEvent,
        defaultSource: StreamSource,
    ): ParsedEvent? {
        val title = row.event.trim()
        if (title.isEmpty()) return null
        val streams = buildList {
            row.channels.forEach { ch ->
                val id = ch.channelId.trim()
                if (id.isEmpty()) return@forEach
                add(
                    ParsedStream(
                        label = ch.channelName.trim().ifEmpty { "Link" },
                        channelId = id,
                        source = streamSourceFor(id, defaultSource),
                    ),
                )
            }
            row.channels2.forEach { ch ->
                val id = ch.channelId.trim()
                if (id.isEmpty()) return@forEach
                add(
                    ParsedStream(
                        label = ch.channelName.trim().ifEmpty { "Link" },
                        channelId = id,
                        source = streamSourceFor(id, defaultSource),
                    ),
                )
            }
        }
        if (streams.isEmpty()) return null
        val timeLabel = row.time.trim().ifEmpty { "Live" }
        return ParsedEvent(
            category = category.trim().ifEmpty { "Events" },
            dateKey = dateKey,
            timeLabel = timeLabel,
            title = title,
            league = SpecialEventSort.leagueFromCategoryOrTitle(category, title),
            streams = streams,
            live = timeLabel.equals("live", ignoreCase = true),
        )
    }

    private fun streamSourceFor(channelId: String, defaultSource: StreamSource): StreamSource =
        if (channelId.all { it.isDigit() }) StreamSource.TV else defaultSource

    private fun fetchJson(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.bytes() ?: return null
                if (body.size > SupplementConfig.MAX_JSON_BYTES) return null
                body.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
    }
}

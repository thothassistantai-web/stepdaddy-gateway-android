package com.thothassistant.stepdaddy.gateway.epg

import android.util.Log
import com.thothassistant.stepdaddy.gateway.upstream.SupplementConfig
import com.thothassistant.stepdaddy.gateway.upstream.XyzStreamsConfig
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches TV Guide schedule data (same source as xyzstreams.st) and writes XMLTV for xyz channels. */
class XyzStreamsEpgFetcher(
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    private data class GuideResponse(
        val data: GuideData? = null,
    )

    @Serializable
    private data class GuideData(
        val items: List<GuideItem> = emptyList(),
    )

    @Serializable
    private data class GuideItem(
        val channel: GuideChannel? = null,
        @SerialName("programSchedules") val programSchedules: List<GuideProgram> = emptyList(),
    )

    @Serializable
    private data class GuideChannel(
        val name: String? = null,
        @SerialName("networkName") val networkName: String? = null,
    )

    @Serializable
    private data class GuideProgram(
        @SerialName("startTime") val startTime: Long = 0,
        @SerialName("endTime") val endTime: Long = 0,
        val title: String? = null,
    )

    data class RefreshResult(
        val programmesWritten: Int = 0,
        val channelsMatched: Int = 0,
    )

    fun refresh(output: File, now: Instant = Instant.now()): RefreshResult {
        val startEpoch = now.epochSecond
        val payload = fetchGuideJson(startEpoch) ?: run {
            if (output.isFile) return RefreshResult()
            output.delete()
            return RefreshResult()
        }

        val events = mutableListOf<SpecialEventsEpgGenerator.EventProgramme>()
        val matchedTvgIds = linkedSetOf<String>()

        payload.data?.items.orEmpty().forEach { item ->
            val channelName = item.channel?.name?.trim().orEmpty()
            val networkName = item.channel?.networkName?.trim().orEmpty()
            val row = XyzStreamsConfig.catalogRowForEpgKey(channelName)
                ?: networkName.takeIf { it.isNotEmpty() }?.let { XyzStreamsConfig.catalogRowForEpgKey(it) }
                ?: return@forEach

            matchedTvgIds += row.tvgId
            item.programSchedules.forEach { prog ->
                if (prog.startTime <= 0 || prog.endTime <= prog.startTime) return@forEach
                val title = prog.title?.trim().orEmpty().ifEmpty { row.displayName }
                events += SpecialEventsEpgGenerator.EventProgramme(
                    channelId = row.tvgId,
                    displayName = row.displayName,
                    title = title,
                    start = Instant.ofEpochSecond(prog.startTime),
                    stop = Instant.ofEpochSecond(prog.endTime),
                    regionCode = "US",
                )
            }
        }

        if (events.isEmpty()) {
            Log.w(TAG, "xyzstreams EPG: no programmes matched (${matchedTvgIds.size} channels)")
            if (!output.isFile) output.delete()
            return RefreshResult(channelsMatched = matchedTvgIds.size)
        }

        SpecialEventsEpgGenerator.writeXml(events, output)
        Log.i(TAG, "xyzstreams EPG: ${events.size} programmes for ${matchedTvgIds.size} channels")
        return RefreshResult(
            programmesWritten = events.size,
            channelsMatched = matchedTvgIds.size,
        )
    }

    private fun fetchGuideJson(startEpoch: Long): GuideResponse? {
        val urls = listOf(
            XyzStreamsConfig.tvguideScheduleUrl(startEpoch, useProxy = true),
            XyzStreamsConfig.tvguideScheduleUrl(startEpoch, useProxy = false),
        )
        for (url in urls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SupplementConfig.USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            val body = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "TV Guide fetch ${response.code} $url")
                        return@use null
                    }
                    response.body?.string()
                }
            }.getOrElse { exc ->
                Log.w(TAG, "TV Guide fetch error $url", exc)
                null
            } ?: continue

            val parsed = runCatching { json.decodeFromString<GuideResponse>(body) }
                .getOrElse { exc ->
                    Log.w(TAG, "TV Guide parse error", exc)
                    null
                }
            if (parsed?.data?.items?.isNotEmpty() == true) return parsed
        }
        return null
    }

    companion object {
        private const val TAG = "XyzStreamsEpgFetcher"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

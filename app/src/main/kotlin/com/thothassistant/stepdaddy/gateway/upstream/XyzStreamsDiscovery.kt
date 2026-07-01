package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Probes 247v2 for TV Guide callsigns not covered by [XyzStreamsCatalog.CATALOG]
 * and returns extra live channel rows.
 */
class XyzStreamsDiscovery(
    private val httpClient: OkHttpClient = XyzStreamsProbe.defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class Stats(
        val epgKeysScanned: Int = 0,
        val candidatesProbed: Int = 0,
        val channelsDiscovered: Int = 0,
    )

    fun discoverAdditionalChannels(
        staticCatalog: List<XyzStreamsConfig.ChannelRow> = XyzStreamsCatalog.CATALOG,
        maxEpgKeys: Int = 48,
        maxProbes: Int = 96,
    ): Pair<List<XyzStreamsConfig.ChannelRow>, Stats> {
        val coveredKeys = staticCatalog.flatMap { it.epgKeys }.map { it.uppercase() }.toSet()
        val staticStreamIds = staticCatalog.map { it.streamId.lowercase() }.toSet()
        val epgKeys = fetchUnmappedEpgKeys(coveredKeys).take(maxEpgKeys)
        if (epgKeys.isEmpty()) return emptyList<XyzStreamsConfig.ChannelRow>() to Stats()

        var probes = 0
        val discovered = mutableListOf<XyzStreamsConfig.ChannelRow>()
        val usedStreamIds = staticStreamIds.toMutableSet()

        for (epgKey in epgKeys) {
            if (probes >= maxProbes) break
            for (candidate in XyzStreamsProbe.candidateStreamIds(epgKey)) {
                if (probes >= maxProbes) break
                val normalized = candidate.lowercase()
                if (normalized in usedStreamIds) continue
                probes++
                if (!XyzStreamsProbe.isLiveManifest(normalized, httpClient = httpClient)) continue

                usedStreamIds += normalized
                val displayName = XyzStreamsCatalog.displayNameForEpgKey(epgKey)
                discovered += XyzStreamsConfig.ChannelRow(
                    streamId = normalized,
                    displayName = displayName,
                    tvgId = XyzStreamsCatalog.tvgIdFor(displayName, normalized),
                    logo = null,
                    epgKeys = setOf(epgKey),
                    groupTitle = XyzStreamsCatalog.groupTitleForEpgKey(epgKey),
                )
                break
            }
        }

        if (discovered.isNotEmpty()) {
            Log.i(TAG, "xyzstreams discovery: +${discovered.size} channels from EPG probes ($probes probes)")
        }

        return discovered to Stats(
            epgKeysScanned = epgKeys.size,
            candidatesProbed = probes,
            channelsDiscovered = discovered.size,
        )
    }

    private fun fetchUnmappedEpgKeys(coveredKeys: Set<String>): List<String> {
        val startEpoch = System.currentTimeMillis() / 1000L
        val urls = listOf(
            XyzStreamsConfig.tvguideScheduleUrl(startEpoch, useProxy = true),
            XyzStreamsConfig.tvguideScheduleUrl(startEpoch, useProxy = false),
        )
        for (url in urls) {
            val body = runCatching {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", SupplementConfig.USER_AGENT)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()
                }
            }.getOrNull() ?: continue

            val parsed = runCatching {
                json.decodeFromString<EpgEnvelope>(body)
            }.getOrNull() ?: continue

            val keys = linkedSetOf<String>()
            parsed.data?.items.orEmpty().forEach { item ->
                item.channel?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { keys += it }
                item.channel?.networkName?.trim()?.takeIf { it.isNotEmpty() }?.let { keys += it }
            }
            return keys.filter { it.uppercase() !in coveredKeys }.sorted()
        }
        return emptyList()
    }

    @kotlinx.serialization.Serializable
    private data class EpgEnvelope(val data: EpgData? = null)

    @kotlinx.serialization.Serializable
    private data class EpgData(val items: List<EpgItem> = emptyList())

    @kotlinx.serialization.Serializable
    private data class EpgItem(val channel: EpgChannel? = null)

    @kotlinx.serialization.Serializable
    private data class EpgChannel(
        val name: String? = null,
        val networkName: String? = null,
    )

    companion object {
        private const val TAG = "XyzStreamsDiscovery"
    }
}

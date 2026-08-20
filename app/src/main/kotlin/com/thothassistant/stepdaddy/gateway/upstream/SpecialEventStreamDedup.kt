package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.net.URI

/**
 * Removes duplicate special-event stream channels that share the same canonical upstream
 * stream URL or play-time resolve token. Keeps the entry with the richest metadata.
 */
object SpecialEventStreamDedup {
    private val embedIdPattern = Regex(
        """new-stream-embed/(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val genericLinkNamePattern = Regex(
        """^Link\s*-\s*\d+$""",
        RegexOption.IGNORE_CASE,
    )
    private val duplicateNamePattern = Regex(
        """duplicate""",
        RegexOption.IGNORE_CASE,
    )

    data class Result(
        val channels: List<SupplementChannel>,
        val removedCount: Int,
    )

    fun dedupeBundle(bundle: SpecialEventsMerger.EpgBundle): SpecialEventsMerger.EpgBundle {
        val result = dedupeChannels(bundle.channels)
        return bundle.copy(channels = result.channels)
    }

    fun dedupeChannels(channels: List<SupplementChannel>): Result {
        if (channels.isEmpty()) return Result(channels, 0)

        val bestByKey = linkedMapOf<String, SupplementChannel>()
        val keyOrder = mutableListOf<String>()
        val noKeyChannels = mutableListOf<SupplementChannel>()
        val removed = mutableListOf<String>()

        for (channel in channels) {
            if (!isEventStream(channel)) continue
            val key = canonicalStreamKey(channel)
            if (key == null) {
                noKeyChannels += channel
                continue
            }
            val existing = bestByKey[key]
            if (existing == null) {
                bestByKey[key] = channel
                keyOrder += key
            } else {
                val (kept, dropped) = pickBetter(existing, channel)
                bestByKey[key] = kept
                removed += formatRemoval(key, dropped, kept)
            }
        }

        if (removed.isEmpty()) {
            return Result(channels, 0)
        }

        val emittedKeys = mutableSetOf<String>()
        val deduped = mutableListOf<SupplementChannel>()
        for (channel in channels) {
            if (isGuide(channel)) {
                deduped += channel
                continue
            }
            val key = canonicalStreamKey(channel)
            if (key == null) {
                deduped += channel
                continue
            }
            if (key in emittedKeys) continue
            emittedKeys += key
            deduped += bestByKey.getValue(key)
        }

        return Result(deduped, removed.size)
    }

    fun canonicalStreamKey(channel: SupplementChannel): String? {
        channel.dlhdEventKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "eventkey:${it.lowercase()}"
        }
        channel.dlhdEventStreamKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "dlhd:${it.lowercase()}"
        }
        val streamUrl = channel.streamUrl.trim()
        if (streamUrl.isNotEmpty()) {
            return "url:${normalizeStreamUrl(streamUrl)}"
        }
        embedIdFromReferer(channel.referer)?.let { return "embed:$it" }
        channel.eventSourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "event:${it.lowercase()}"
        }
        return null
    }

    fun metadataScore(channel: SupplementChannel): Int {
        var score = 0
        if (!channel.tvgId.isNullOrBlank()) score += 20
        if (!channel.eventSourceUrl.isNullOrBlank()) score += 15
        if (!channel.providerTag.isNullOrBlank()) score += 10
        if (!channel.dlhdEventStreamKey.isNullOrBlank()) score += 8
        if (channel.dlhdEventMirrors.isNotEmpty()) score += channel.dlhdEventMirrors.size.coerceAtMost(20)
        if (!channel.logo.isNullOrBlank()) score += 5
        if (channel.id.startsWith("dlhd-event:")) score += 4
        score += channel.name.trim().length.coerceAtMost(40)
        if (genericLinkNamePattern.matches(channel.name.trim())) score -= 10
        if (duplicateNamePattern.containsMatchIn(channel.name)) score -= 15
        return score
    }

    private fun isGuide(channel: SupplementChannel): Boolean =
        channel.id.startsWith("dlhd-guide:")

    private fun isEventStream(channel: SupplementChannel): Boolean =
        channel.id.startsWith("dlhd-event:")

    private fun pickBetter(
        a: SupplementChannel,
        b: SupplementChannel,
    ): Pair<SupplementChannel, SupplementChannel> =
        if (metadataScore(a) >= metadataScore(b)) a to b else b to a

    private fun embedIdFromReferer(referer: String?): String? =
        referer?.let { embedIdPattern.find(it)?.groupValues?.getOrNull(1) }

    private fun normalizeStreamUrl(url: String): String {
        val trimmed = url.trim().lowercase()
        if (trimmed.isEmpty()) return trimmed
        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path?.trimEnd('/').orEmpty()
            val stableQuery = uri.rawQuery
                ?.split('&')
                ?.map { it.trim() }
                ?.filter { part ->
                    part.isNotEmpty() &&
                        !part.startsWith("token=") &&
                        !part.startsWith("expires=") &&
                        !part.startsWith("sig=")
                }
                ?.sorted()
                ?.joinToString("&")
            buildString {
                append(host)
                append(path)
                if (!stableQuery.isNullOrEmpty()) {
                    append('?')
                    append(stableQuery)
                }
            }
        }.getOrDefault(trimmed)
    }

    private fun formatRemoval(
        key: String,
        dropped: SupplementChannel,
        kept: SupplementChannel,
    ): String = "key=$key dropped=${dropped.id}|${dropped.name} kept=${kept.id}|${kept.name}"
}

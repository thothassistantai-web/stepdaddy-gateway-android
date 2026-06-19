package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves live sports event streams from thetvapp.link (embed → playlist → HLS variant).
 * Separate from the dead linear-TV /token/ flow documented in thetvapp-link-token-flow-research.md.
 */
class TheTvAppSportsResolver(
    private val httpClient: OkHttpClient = defaultClient(),
) {
    data class ResolveStats(
        val eventsScanned: Int = 0,
        val eventsWithEmbed: Int = 0,
        val playable: Int = 0,
    )

    fun resolveLiveEvents(
        homepageHtml: String,
        fetchEventHtml: (String) -> String?,
        fetchPlaylist: (embedId: String, embedReferer: String) -> String?,
        maxEvents: Int = SupplementConfig.MAX_SPORTS_EVENTS,
    ): Pair<List<SupplementChannel>, ResolveStats> {
        val eventUrls = extractEventUrls(homepageHtml).take(maxEvents)
        val channels = mutableListOf<SupplementChannel>()
        var withEmbed = 0
        for (eventUrl in eventUrls) {
            val eventHtml = fetchEventHtml(eventUrl) ?: continue
            val embedId = extractEmbedId(eventHtml) ?: continue
            withEmbed++
            val embedReferer = "$EMBED_BASE/$embedId"
            val playlist = fetchPlaylist(embedId, embedReferer) ?: continue
            val variant = firstVariantUrl(playlist) ?: continue
            val name = eventTitle(eventHtml, eventUrl)
            channels += SupplementChannel(
                id = "sport:${shortHash(eventUrl)}",
                name = name,
                tvgId = null,
                logo = null,
                groupTitle = SupplementConfig.SPORTS_GROUP_TITLE,
                streamUrl = variant,
                referer = embedReferer,
                origin = EMBED_ORIGIN,
            )
        }
        return channels to ResolveStats(
            eventsScanned = eventUrls.size,
            eventsWithEmbed = withEmbed,
            playable = channels.size,
        )
    }

    suspend fun fetchHomepage(): String? = fetchText(HOMEPAGE_URL)

    suspend fun resolveFromNetwork(maxEvents: Int = SupplementConfig.MAX_SPORTS_EVENTS): Pair<List<SupplementChannel>, ResolveStats> {
        val home = fetchHomepage() ?: return emptyList<SupplementChannel>() to ResolveStats()
        return resolveLiveEvents(
            homepageHtml = home,
            fetchEventHtml = { url -> fetchText(url) },
            fetchPlaylist = { embedId, embedReferer ->
                fetchText(
                    playlistApiUrl(embedId),
                    referer = embedReferer,
                    origin = EMBED_ORIGIN,
                )
            },
            maxEvents = maxEvents,
        )
    }

    private fun fetchText(
        url: String,
        referer: String? = null,
        origin: String? = null,
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
        referer?.let { builder.header("Referer", it) }
        origin?.let { builder.header("Origin", it) }
        return runCatching {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        const val HOMEPAGE_URL = "https://thetvapp.link/"
        private const val EMBED_BASE = "https://gooz.aapmains.net/new-stream-embed"
        private const val EMBED_ORIGIN = "https://gooz.aapmains.net"

        private val eventUrlPattern = Regex(
            """href="(https://thetvapp\.link/[^"]+/\d+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val embedIdPattern = Regex(
            """new-stream-embed/(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val titlePattern = Regex(
            """<title>([^<]+)</title>""",
            RegexOption.IGNORE_CASE,
        )

        fun extractEventUrls(homepageHtml: String): List<String> =
            eventUrlPattern.findAll(homepageHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

        fun extractEmbedId(eventHtml: String): String? =
            embedIdPattern.find(eventHtml)?.groupValues?.getOrNull(1)

        fun playlistApiUrl(embedId: String): String =
            "https://chatgpt.hereisman.net/playlist/$embedId/load-playlist"

        fun firstVariantUrl(playlistText: String): String? =
            playlistText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

        fun eventTitle(eventHtml: String, fallbackUrl: String): String {
            val raw = titlePattern.find(eventHtml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val cleaned = raw
                .replace(Regex("""\s*[|\-–]\s*TheTvApp.*$""", RegexOption.IGNORE_CASE), "")
                .replace("&amp;", "&")
                .trim()
            if (cleaned.isNotEmpty() && !cleaned.equals("TheTVApp", ignoreCase = true)) {
                return cleaned
            }
            val slug = fallbackUrl.trimEnd('/').substringAfterLast('/')
            val teams = fallbackUrl.removeSuffix("/$slug").substringAfterLast('/')
            return teams.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }

        private fun shortHash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(50, TimeUnit.SECONDS)
                .build()
    }
}

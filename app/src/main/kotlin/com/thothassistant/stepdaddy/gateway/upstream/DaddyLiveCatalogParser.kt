package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.UpstreamChannelRow

/** Public channel directory fallback when the legacy JSON endpoint is removed. */
object DaddyLiveCatalogParser {
    fun parse(html: String, baseUrl: String): List<UpstreamChannelRow> {
        val anchors = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val title = Regex("""<[^>]+class\s*=\s*["'][^"']*\bcard__title\b[^"']*["'][^>]*>(.*?)</[^>]+>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return anchors.findAll(html).mapNotNull { anchor ->
            val link = href.find(anchor.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val id = Regex("""(?:watch\.php\?id=|/stream/stream-)(\d+)""").find(link)?.groupValues?.get(1) ?: return@mapNotNull null
            val raw = title.find(anchor.groupValues[2])?.groupValues?.get(1) ?: return@mapNotNull null
            val name = raw.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
            if (name.isBlank()) return@mapNotNull null
            UpstreamChannelRow(id, name, "${baseUrl.trimEnd('/')}/stream/stream-$id.php")
        }.distinctBy { it.channelId }.toList()
    }
}

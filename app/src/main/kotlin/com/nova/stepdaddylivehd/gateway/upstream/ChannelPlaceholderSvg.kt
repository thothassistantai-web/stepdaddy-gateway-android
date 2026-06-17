package com.nova.stepdaddylivehd.gateway.upstream

import java.util.Locale
import kotlin.math.abs

object ChannelPlaceholderSvg {
    fun render(channelName: String): ByteArray {
        val label = initials(channelName)
        val hue = abs(channelName.hashCode()) % 360
        val bg = "hsl($hue, 42%, 28%)"
        val fg = "hsl($hue, 55%, 78%)"
        val safeLabel = escapeXml(label)
        val svg = """
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" role="img">
            |  <rect width="128" height="128" rx="24" fill="$bg"/>
            |  <text x="64" y="72" text-anchor="middle" font-family="sans-serif" font-size="40" font-weight="700" fill="$fg">$safeLabel</text>
            |</svg>
        """.trimMargin()
        return svg.toByteArray(Charsets.UTF_8)
    }

    private fun initials(name: String): String {
        val cleaned = name
            .replace(Regex("^18\\+\\s*"), "")
            .replace(categorySuffixRe, "")
            .trim()
        if (cleaned.isEmpty()) return "TV"
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size == 1) {
            return words[0].take(3).uppercase(Locale.US)
        }
        return words.take(3).joinToString("") { word ->
            word.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        }.ifBlank { "TV" }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
}

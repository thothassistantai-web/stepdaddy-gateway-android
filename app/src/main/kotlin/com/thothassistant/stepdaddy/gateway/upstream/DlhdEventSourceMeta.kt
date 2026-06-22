package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Parsed DaddyLive event metadata stored on supplement channels as
 * `category|dateKey|timeLabel|title` (dateKey may contain `|`).
 */
data class DlhdEventSourceMeta(
    val category: String,
    val dateKey: String,
    val timeLabel: String,
    val title: String,
) {
    fun displayTitle(): String {
        val core = title.substringAfter(": ", title).trim()
        return core.ifEmpty { title.trim() }
    }

    companion object {
        fun parse(raw: String?): DlhdEventSourceMeta? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val parts = value.split('|')
            if (parts.size < 4) return null
            return DlhdEventSourceMeta(
                category = parts.first().trim(),
                dateKey = parts.subList(1, parts.size - 2).joinToString("|").trim(),
                timeLabel = parts[parts.size - 2].trim(),
                title = parts.last().trim(),
            )
        }
    }
}

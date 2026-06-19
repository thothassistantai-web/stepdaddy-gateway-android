package com.thothassistant.stepdaddy.gateway.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class GatewayLogLine(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
) {
    fun formatLine(): String {
        val time = TIME_FORMAT.format(Date(timestampMs))
        return "$time $level/$tag: $message"
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

object GatewayLogRing {
    private const val MAX_LINES = 800
    private val buffer = ArrayDeque<GatewayLogLine>(MAX_LINES)
    private val listeners = CopyOnWriteArrayList<(List<GatewayLogLine>) -> Unit>()
    private var lastAppendKey: String? = null

    val GATEWAY_TAGS = setOf(
        "ServerService",
        "GatewayStartHelper",
        "StreamRoutes",
        "GatewayServer",
        "PlaylistCache",
        "PlaylistBuilder",
        "DaddyLiveClient",
        "GatewayHealth",
        "PlayerError",
    )

    @Synchronized
    fun append(level: String, tag: String, message: String) {
        val normalizedLevel = level.uppercase(Locale.US)
        if (normalizedLevel != "ERROR" && normalizedLevel != "WARN" && normalizedLevel != "W") {
            return
        }
        val dedupeKey = "$normalizedLevel/$tag:$message"
        if (dedupeKey == lastAppendKey) return
        lastAppendKey = dedupeKey
        val line = GatewayLogLine(
            timestampMs = System.currentTimeMillis(),
            level = if (normalizedLevel == "W") "WARN" else normalizedLevel,
            tag = tag,
            message = message,
        )
        while (buffer.size >= MAX_LINES) {
            buffer.removeFirst()
        }
        buffer.addLast(line)
        val copy = buffer.toList()
        listeners.forEach { runCatching { it(copy) } }
    }

    @Synchronized
    fun snapshot(): List<GatewayLogLine> = buffer.toList()

    fun addListener(listener: (List<GatewayLogLine>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<GatewayLogLine>) -> Unit) {
        listeners.remove(listener)
    }

    fun refreshFromLogcat() {
        val process = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300"))
        }.getOrNull() ?: return
        val text = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (text.isBlank()) return

        val fresh = ArrayList<GatewayLogLine>()
        text.lineSequence().forEach { line ->
            parseLogcatLine(line)?.let { fresh.add(it) }
        }
        if (fresh.isEmpty()) return

        synchronized(this) {
            fresh.forEach { line ->
                while (buffer.size >= MAX_LINES) {
                    buffer.removeFirst()
                }
                buffer.addLast(line)
            }
            val copy = buffer.toList()
            listeners.forEach { runCatching { it(copy) } }
        }
    }

    private fun parseLogcatLine(line: String): GatewayLogLine? {
        // Typical: 06-18 19:43:57.123  1234  5678 W ServerService: message
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size < 6) return null
        val levelChar = parts.getOrNull(4)?.uppercase(Locale.US) ?: return null
        val level = when (levelChar) {
            "E" -> "ERROR"
            "W" -> "WARN"
            else -> return null
        }
        val tagMessage = parts.drop(5).joinToString(" ")
        val colon = tagMessage.indexOf(':')
        if (colon <= 0) return null
        val tag = tagMessage.substring(0, colon).trim()
        if (tag !in GATEWAY_TAGS) return null
        val message = tagMessage.substring(colon + 1).trim()
        if (message.isEmpty()) return null
        return GatewayLogLine(System.currentTimeMillis(), level, tag, message)
    }
}

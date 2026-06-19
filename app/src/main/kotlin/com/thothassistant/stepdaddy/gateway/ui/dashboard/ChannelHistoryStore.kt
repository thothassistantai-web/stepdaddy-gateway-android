package com.thothassistant.stepdaddy.gateway.ui.dashboard

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class ChannelHistoryEntry(
    val channelId: String,
    val name: String,
    val number: Int,
    val timestampMs: Long,
) {
    fun formatTimestamp(): String =
        HISTORY_TIME_FORMAT.format(Date(timestampMs))

    companion object {
        private val HISTORY_TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    }
}

class ChannelHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listeners = CopyOnWriteArrayList<(List<ChannelHistoryEntry>) -> Unit>()

    fun snapshot(): List<ChannelHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ChannelHistoryEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    fun record(channel: TuneChannel) {
        val updated = buildList {
            add(
                ChannelHistoryEntry(
                    channelId = channel.id,
                    name = channel.name,
                    number = channel.number,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            snapshot()
                .filterNot { it.channelId == channel.id }
                .take(MAX_ENTRIES - 1)
                .forEach { add(it) }
        }
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(updated)).apply()
        listeners.forEach { runCatching { it(updated) } }
    }

    fun lastChannel(): TuneChannel? {
        val entry = snapshot().firstOrNull() ?: return null
        return TuneChannel(entry.channelId, entry.name, entry.number)
    }

    fun addListener(listener: (List<ChannelHistoryEntry>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<ChannelHistoryEntry>) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        private const val PREFS = "stepdaddy_channel_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 50
    }
}

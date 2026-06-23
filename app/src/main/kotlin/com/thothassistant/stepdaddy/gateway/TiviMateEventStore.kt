package com.thothassistant.stepdaddy.gateway

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.TiviMateEvent

/**
 * In-memory ring buffer for TiViMate patch → gateway event telemetry.
 * Last [MAX_EVENTS] events are retained for the gateway process lifetime.
 */
object TiviMateEventStore {
    private const val TAG = "TiviMateEventStore"
    private const val MAX_EVENTS = 100
    private val buffer = ArrayDeque<TiviMateEvent>(MAX_EVENTS)

    @Synchronized
    fun append(event: TiviMateEvent): TiviMateEvent {
        val normalized = if (event.timestamp > 0L) {
            event
        } else {
            event.copy(timestamp = System.currentTimeMillis())
        }
        while (buffer.size >= MAX_EVENTS) {
            buffer.removeFirst()
        }
        buffer.addLast(normalized)
        Log.i(
            TAG,
            "event=${normalized.event} channelNo=${normalized.channelNo} " +
                "channelName=${normalized.channelName} ts=${normalized.timestamp}",
        )
        return normalized
    }

    @Synchronized
    fun snapshot(since: Long? = null): List<TiviMateEvent> {
        val events = buffer.toList()
        if (since == null || since <= 0L) return events
        return events.filter { it.timestamp >= since }
    }

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun lastEvent(): TiviMateEvent? = buffer.lastOrNull()
}

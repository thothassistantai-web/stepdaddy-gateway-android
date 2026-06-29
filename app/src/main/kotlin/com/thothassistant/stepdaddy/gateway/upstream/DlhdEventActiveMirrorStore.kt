package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.ConcurrentHashMap

/** Tracks the active mirror index per dlhd-event token for health reporting. */
class DlhdEventActiveMirrorStore {
    private val activeIndex = ConcurrentHashMap<String, Int>()

    fun activeIndex(eventKey: String): Int = activeIndex[eventKey.trim()].orZero()

    fun recordActive(eventKey: String, index: Int) {
        val trimmed = eventKey.trim()
        if (trimmed.isEmpty()) return
        activeIndex[trimmed] = index.coerceAtLeast(0)
    }

    fun snapshot(): Map<String, Int> = activeIndex.toMap()

    fun prune(activeEventKeys: Set<String>) {
        activeIndex.keys.retainAll(activeEventKeys.map { it.trim() }.toSet())
    }

    private fun Int?.orZero(): Int = this ?: 0
}

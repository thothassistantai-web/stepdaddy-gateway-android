package com.thothassistant.stepdaddy.gateway.upstream

import kotlin.math.min

class ChannelMirrorCooldowns(
    private val baseCooldownMs: Long = GatewayConfig.CHANNEL_MIRROR_COOLDOWN_BASE_MS,
    private val maxCooldownMs: Long = GatewayConfig.CHANNEL_MIRROR_COOLDOWN_MAX_MS,
) {
    private val failureCounts = mutableMapOf<String, Int>()
    private val retryAfterMs = mutableMapOf<String, Long>()
    private val lock = Any()

    fun clear(channelId: String, baseUrl: String) {
        val key = channelMirrorKey(channelId, baseUrl)
        if (key.isEmpty()) return
        synchronized(lock) {
            failureCounts.remove(key)
            retryAfterMs.remove(key)
        }
    }

    fun recordFailure(channelId: String, baseUrl: String) {
        val key = channelMirrorKey(channelId, baseUrl)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val nextCount = synchronized(lock) {
            val next = (failureCounts[key] ?: 0) + 1
            failureCounts[key] = next
            next
        }
        val backoff = min(
            baseCooldownMs * (1L shl min(nextCount - 1, 4)),
            maxCooldownMs,
        )
        synchronized(lock) {
            retryAfterMs[key] = now + backoff
        }
    }

    fun isCoolingDown(channelId: String, baseUrl: String): Boolean {
        val key = channelMirrorKey(channelId, baseUrl)
        if (key.isEmpty()) return false
        val retryAt = synchronized(lock) { retryAfterMs[key] } ?: return false
        if (System.currentTimeMillis() >= retryAt) {
            synchronized(lock) {
                retryAfterMs.remove(key)
                failureCounts.remove(key)
            }
            return false
        }
        return true
    }

    private fun channelMirrorKey(channelId: String, baseUrl: String): String {
        val channel = channelId.trim()
        val mirror = baseUrl.trimEnd('/')
        if (channel.isEmpty() || mirror.isEmpty()) return ""
        return "$channel|$mirror"
    }
}

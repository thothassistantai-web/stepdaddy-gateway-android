package com.nova.stepdaddylivehd.gateway.ui.player

import com.nova.stepdaddylivehd.gateway.ui.dashboard.TuneChannel

/**
 * Shared channel list navigation for compact and fullscreen players.
 */
class PlayerChannelList {
    private var channels: List<TuneChannel> = emptyList()
    private var currentIndex: Int = -1

    val currentChannel: TuneChannel?
        get() = channels.getOrNull(currentIndex)

    fun setChannels(list: List<TuneChannel>) {
        channels = list
    }

    fun tuneTo(channel: TuneChannel): TuneChannel? {
        val index = channels.indexOfFirst { it.id == channel.id }
        return if (index >= 0) {
            tuneToIndex(index)
        } else {
            channels = (channels + channel).sortedBy { it.number }
            tuneToIndex(channels.indexOfFirst { it.id == channel.id })
        }
    }

    fun tuneToIndex(index: Int): TuneChannel? {
        if (channels.isEmpty()) return null
        val safeIndex = index.coerceIn(0, channels.lastIndex)
        currentIndex = safeIndex
        return channels[safeIndex]
    }

    fun channelUp(): TuneChannel? {
        if (channels.isEmpty()) return null
        val next = if (currentIndex < 0) 0 else (currentIndex + 1) % channels.size
        return tuneToIndex(next)
    }

    fun channelDown(): TuneChannel? {
        if (channels.isEmpty()) return null
        val next = if (currentIndex < 0) {
            0
        } else if (currentIndex == 0) {
            channels.lastIndex
        } else {
            currentIndex - 1
        }
        return tuneToIndex(next)
    }
}

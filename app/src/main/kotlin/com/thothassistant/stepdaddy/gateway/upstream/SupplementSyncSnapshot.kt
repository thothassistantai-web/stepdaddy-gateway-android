package com.thothassistant.stepdaddy.gateway.upstream

/** Last successful (or partial) supplement sync counters for /health. */
data class SupplementSyncSnapshot(
    val blockedTokenProxy: Int = 0,
    val sportsChannels: Int = 0,
    val specialEventGuides: Int = 0,
    val dlhdEventStreams: Int = 0,
    val sportsEventsScanned: Int = 0,
    val iptvOrgChannels: Int = 0,
    val iptvOrgPlaylistsFetched: Int = 0,
    val iptvOrgPlaylistsFailed: Int = 0,
    val iptvOrgEntriesParsed: Int = 0,
    val ntvCxChannels: Int = 0,
    val ntvCxResolveProbeOk: Boolean = false,
    val adultSwimChannels: Int = 0,
    val adultSwimProbed: Int = 0,
    val adultSwimProbeOk: Int = 0,
    val freeTvChannels: Int = 0,
    val freeTvPlaylistsFetched: Int = 0,
    val freeTvPlaylistsFailed: Int = 0,
    val tmdbVodMovies: Int = 0,
    val tmdbVodSeries: Int = 0,
)

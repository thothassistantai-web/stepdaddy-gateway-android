package com.thothassistant.stepdaddy.gateway.upstream

object SupplementConfig {
    /** Max playable event streams (DaddyLive + TheTvApp combined). */
    const val MAX_SPECIAL_EVENT_STREAMS = 120

    /** Max upstream links kept per schedule event (primary + optional backup). */
    const val MAX_STREAM_LINKS_PER_EVENT = 2

    /** Re-fetch DaddyLive schedule + TheTvApp embeds for Special Events only. */
    const val SPECIAL_EVENTS_SYNC_INTERVAL_MS = 15 * 60_000L

    /** Drop finished events and stale guide rows between upstream syncs. */
    const val SPECIAL_EVENTS_PRUNE_INTERVAL_MS = 2 * 60_000L

    /** Tier 4: background reachability probes for active `dlhd-event:*` streams. */
    const val DLHD_EVENT_HEALTH_PROBES_ENABLED = true

    const val DLHD_EVENT_HEALTH_PROBE_INTERVAL_MS = 5 * 60_000L
    const val DLHD_EVENT_HEALTH_INITIAL_DELAY_MS = 90_000L
    const val DLHD_EVENT_HEALTH_PROBE_TIMEOUT_MS = 20_000L
    const val DLHD_EVENT_HEALTH_MAX_CONCURRENT = 3

    /** Re-fetch when a scheduled row starts within this window but has no stream row yet. */
    const val SPECIAL_EVENTS_PRE_START_WINDOW_MS = 15 * 60_000L

    /** Keep ended dlhd-event rows in playlists briefly before catalog prune removes them. */
    const val SPECIAL_EVENT_ENDED_GRACE_MS = 30 * 60_000L

    /** @deprecated Use [MAX_SPECIAL_EVENT_STREAMS]. */
    const val MAX_SPORTS_EVENTS = MAX_SPECIAL_EVENT_STREAMS

    const val MAX_JSON_BYTES = 4 * 1024 * 1024

    fun dlhdTvJsonUrl(base: String): String = "${base.trimEnd('/')}/cache/tv/tv.json"

    fun dlhdTv2JsonUrl(base: String): String = "${base.trimEnd('/')}/cache/tv2/tv2.json"

    const val SYNC_INTERVAL_MS = 6 * 3600_000L

    const val DOWNLOAD_TIMEOUT_MS = 45_000L

    const val MAX_M3U_BYTES = 8 * 1024 * 1024

    const val MAX_EPG_BYTES = 32 * 1024 * 1024

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"

    const val GROUP_PREFIX = "📡 | Extra"

    /** @deprecated Stored on disk from older builds; maps to [GroupTitleResolver.SPECIAL_EVENTS]. */
    const val LEGACY_SPORTS_GROUP_TITLE = "🏈 | Sports | TheTvApp"

}

package com.thothassistant.stepdaddy.gateway.epg

object EpgConfig {
  const val USER_AGENT =
      "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"

  val FEED_URLS = listOf(
      "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
  )

  /** Max bytes per upstream gzip feed download. */
  const val MAX_FEED_BYTES = 64 * 1024 * 1024

  /** Programme window: include past 30m through +48h from now. */
  const val PROGRAMME_PAST_MINUTES = 30
  const val PROGRAMME_FUTURE_HOURS = 48

  /** Rebuild when served cache is older than this. */
  const val STALE_REBUILD_SECONDS = 6 * 3600L

  /** Periodic background rebuild interval (12h light tier). */
  const val REBUILD_INTERVAL_MS = 12 * 3600_000L

  /** Feed disk cache TTL before re-download. */
  const val FEED_CACHE_TTL_MS = 3600_000L

  const val DOWNLOAD_TIMEOUT_MS = 60_000L

  /** Cap total feed cache on disk (~US locals + US2 + sports; trim after each build). */
  const val MAX_FEED_CACHE_BYTES = 160 * 1024 * 1024L

  const val MAPPING_ASSET = "channel_epg_map.json"
}

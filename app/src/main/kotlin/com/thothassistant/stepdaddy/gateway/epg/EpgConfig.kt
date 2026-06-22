package com.thothassistant.stepdaddy.gateway.epg

object EpgConfig {
  const val USER_AGENT =
      "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"

  val PRIMARY_FEED_URLS = listOf(
      "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
  )

  /** Plex/DistroTV + regional sports feeds — lazy-loaded when primary merge leaves gaps. */
  val GAP_FILL_FEED_URLS = listOf(
      "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_DISTROTV1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_BEIN1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz",
      "https://epgshare01.online/epgshare01/epg_ripper_NZ1.xml.gz",
  )

  /** All epgshare feeds (primary + gap-fill). Gap-fill entries are lazy-loaded. */
  val FEED_URLS = PRIMARY_FEED_URLS + GAP_FILL_FEED_URLS

  /**
   * Default XMLTV feeds passed to TiviMate when gateway EPG is disabled.
   * Matches [PRIMARY_FEED_URLS] — same trio as stepdaddy-web `EPG_URLS`.
   */
  val DEFAULT_EXTERNAL_EPG_URLS: List<String> = PRIMARY_FEED_URLS

  fun parseExternalEpgUrls(raw: String): List<String> =
      raw.split(',', '\n', ';')
          .map { it.trim() }
          .filter { it.isNotEmpty() }

  fun joinExternalEpgUrls(urls: List<String>): String =
      urls.joinToString(",")

  fun formatExternalEpgUrlsForDisplay(urls: List<String>): String =
      urls.joinToString("\n")

  /** Max bytes per upstream gzip feed download. */
  const val MAX_FEED_BYTES = 64 * 1024 * 1024

  /** Programme window: include past 30m through +48h from now. */
  const val PROGRAMME_PAST_MINUTES = 30
  const val PROGRAMME_FUTURE_HOURS = 48

  /** Rebuild when served cache is older than this. */
  const val STALE_REBUILD_SECONDS = 6 * 3600L

  /** Mark EPG stale and trigger background rebuild after this age. */
  const val STALE_SERVE_HEADER_SECONDS = 30 * 60L

  /** How often to check whether EPG needs a background rebuild. */
  const val REBUILD_CHECK_INTERVAL_MS = 30 * 60_000L

  /** Feed disk cache TTL before re-download. */
  const val FEED_CACHE_TTL_MS = 3600_000L

  const val DOWNLOAD_TIMEOUT_MS = 60_000L

  /** Cap total feed cache on disk (primary + on-demand regional gap-fill; trim after each build). */
  const val MAX_FEED_CACHE_BYTES = 320 * 1024 * 1024L

  const val MAPPING_ASSET = "channel_epg_map.json"
  const val NAME_OVERRIDES_ASSET = "epg_name_overrides.json"
  const val ID_BRIDGE_ASSET = "epg_id_bridge.json"
  const val RESEARCH_ASSET = "daddylive_epg_research.json"
}

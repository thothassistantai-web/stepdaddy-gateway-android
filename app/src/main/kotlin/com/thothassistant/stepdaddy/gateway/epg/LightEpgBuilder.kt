package com.thothassistant.stepdaddy.gateway.epg

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class LightEpgBuilder(
    private val store: EpgStore,
    private val idBridge: EpgShareIdBridge? = null,
    private val tvtvFetcher: TvtvUsEpgFetcher? = null,
    private val httpClient: OkHttpClient = defaultClient(),
) {
  fun build(
      tvgIds: Set<String>,
      supplementEpgFile: File? = null,
      supplementTvgIds: Set<String> = emptySet(),
      iptvOrgSupplementTvgIds: Set<String> = emptySet(),
      iptvOrgEpgFile: File? = null,
      sportsEpgFile: File? = null,
      sportsTvgIds: Set<String> = emptySet(),
      fastEpgFiles: List<File> = emptyList(),
      fastEpgTvgIds: Set<String> = emptySet(),
      channelNamesByTvgId: Map<String, String> = emptyMap(),
      placeholdersEnabled: Boolean = true,
      placeholderExcludeIds: Set<String> = emptySet(),
      tvtvGapFillEnabled: Boolean = true,
  ): BuildResult {
    val output = File(store.servedXml.parentFile, "epg.build.part")
    val allIds = tvgIds + supplementTvgIds + sportsTvgIds + fastEpgTvgIds
    if (allIds.isEmpty()) {
      output.writeBytes(emptyXml())
      return BuildResult(output, 0, 0)
    }
    val primaryExpansion = idBridge?.expandWantedIds(tvgIds)
        ?: EpgTvgIdMatcher.expandWantedIds(tvgIds)
    val epgshareTvgIds = playlistIdsForEpgshareMerge(tvgIds) { playlistId ->
        tvtvFetcher?.bridgeEntry(playlistId) != null
    }
    val grouped = groupTvgIdsByFeed(epgshareTvgIds)
    grouped.keys.forEach { url -> ensureFeedCached(url) }

    val windowStart = Instant.now().minusSeconds(EpgConfig.PROGRAMME_PAST_MINUTES * 60L)
    val windowEnd = Instant.now().plusSeconds(EpgConfig.PROGRAMME_FUTURE_HOURS * 3600L)

    val writtenChannelIds = linkedSetOf<String>()
    val idsWithProgrammes = linkedSetOf<String>()
    var channelCount = 0
    var programmeCount = 0
    var realProgrammeCount = 0
    var realChannelsWithProgrammes = 0
    var placeholderProgrammeCount = 0

    output.bufferedWriter(Charsets.UTF_8).use { writer ->
      writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
      writer.write("\n<tv generator-info-name=\"StepDaddy Gateway\">")

      val easternPreferredIds = tvgIds.filter { playlistId ->
          playlistId in TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS &&
              tvtvFetcher?.bridgeEntry(playlistId) != null
      }
      if (tvtvGapFillEnabled && tvtvFetcher != null && easternPreferredIds.isNotEmpty()) {
        val beforeProgrammes = programmeCount
        tvtvFetcher.mergeGapFill(
            writer = writer,
            playlistIds = easternPreferredIds,
            channelNamesByTvgId = channelNamesByTvgId,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
        )
        if (programmeCount > beforeProgrammes) {
          android.util.Log.i(
              "LightEpgBuilder",
              "tvtv.us Eastern EPG: +${programmeCount - beforeProgrammes} programmes for ${easternPreferredIds.size} ids",
          )
        }
      }

      grouped.forEach { (url, playlistIds) ->
        val cache = store.feedCacheFile(url)
        if (!cache.exists()) return@forEach
        mergeEpgshareFeed(
            writer = writer,
            cache = cache,
            playlistIds = playlistIds,
            expansion = primaryExpansion,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
        )
      }

      val epgshareGapIds = (
          tvgIds.filter { it !in idsWithProgrammes } +
              fastEpgTvgIds.filter { isHashStyleFastId(it) && it !in idsWithProgrammes }
          ).toSet()
      if (epgshareGapIds.isNotEmpty()) {
        val gapGrouped = groupTvgIdsByGapFillFeed(epgshareGapIds)
        gapGrouped.keys.forEach { url -> ensureFeedCached(url) }
        val gapExpansion = idBridge?.expandWantedIds(epgshareGapIds)
            ?: EpgTvgIdMatcher.expandWantedIds(epgshareGapIds)
        gapGrouped.forEach { (url, playlistIds) ->
          val cache = store.feedCacheFile(url)
          if (!cache.exists()) return@forEach
          mergeEpgshareFeed(
              writer = writer,
              cache = cache,
              playlistIds = playlistIds,
              expansion = gapExpansion,
              writtenChannelIds = writtenChannelIds,
              idsWithProgrammes = idsWithProgrammes,
              windowStart = windowStart,
              windowEnd = windowEnd,
              channelCountRef = { channelCount = it },
              programmeCountRef = { programmeCount = it },
              getChannelCount = { channelCount },
              getProgrammeCount = { programmeCount },
          )
        }
      }

      val tvtvGapIds = (
          tvgIds.filter { it !in idsWithProgrammes } +
              supplementTvgIds.filter { it !in idsWithProgrammes } +
              sportsTvgIds.filter { it !in idsWithProgrammes } +
              fastEpgTvgIds.filter { it !in idsWithProgrammes }
          ).toSet()
      val tvtvBridgeIds = tvtvFetcher?.let { fetcher ->
          val bridged = tvtvGapIds.filter { fetcher.bridgeEntry(it) != null }
          val prioritized = buildList {
              addAll(
                  tvgIds.filter {
                      it in bridged && it !in TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS
                  },
              )
              addAll(bridged.filter { it !in tvgIds })
          }.distinct().take(TvtvUsEpgConfig.MAX_CHANNELS_PER_BUILD)
          prioritized
      }.orEmpty()
      if (tvtvGapFillEnabled && tvtvFetcher != null && tvtvBridgeIds.isNotEmpty()) {
        val beforeProgrammes = programmeCount
        tvtvFetcher.mergeGapFill(
            writer = writer,
            playlistIds = tvtvBridgeIds,
            channelNamesByTvgId = channelNamesByTvgId,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
        )
        if (programmeCount > beforeProgrammes) {
          android.util.Log.i(
              "LightEpgBuilder",
              "tvtv.us EPG: +${programmeCount - beforeProgrammes} programmes for ${tvtvBridgeIds.size} cable ids",
          )
        }
      }

      val gapFillIds = tvgIds.filter { it !in idsWithProgrammes }.toSet()
      val supplementIds = (supplementTvgIds + gapFillIds).filter { it !in idsWithProgrammes }.toSet()
      if (supplementEpgFile != null && supplementIds.isNotEmpty()) {
        mergeSupplementEpgFile(
            writer = writer,
            file = supplementEpgFile,
            supplementIds = supplementIds,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
        )
      }

      val iptvOrgIds = iptvOrgSupplementTvgIds.filter { it !in idsWithProgrammes }.toSet()
      if (iptvOrgEpgFile != null && iptvOrgIds.isNotEmpty()) {
        val beforeProgrammes = programmeCount
        mergeSupplementEpgFile(
            writer = writer,
            file = iptvOrgEpgFile,
            supplementIds = iptvOrgIds,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
            useIdExpansion = true,
        )
        if (programmeCount > beforeProgrammes) {
          android.util.Log.i(
              "LightEpgBuilder",
              "iptv-org FAST EPG: +${programmeCount - beforeProgrammes} programmes for ${iptvOrgIds.size} ids",
          )
        }
      }

      val fastIds = fastEpgTvgIds.filter { it !in idsWithProgrammes }.toSet()
      if (fastIds.isNotEmpty()) {
        fastEpgFiles.forEach { feed ->
          mergeSupplementEpgFile(
              writer = writer,
              file = feed,
              supplementIds = fastIds,
              writtenChannelIds = writtenChannelIds,
              idsWithProgrammes = idsWithProgrammes,
              windowStart = windowStart,
              windowEnd = windowEnd,
              channelCountRef = { channelCount = it },
              programmeCountRef = { programmeCount = it },
              getChannelCount = { channelCount },
              getProgrammeCount = { programmeCount },
          )
        }
      }

      val sportsIds = sportsTvgIds.filter { it !in idsWithProgrammes }.toSet()
      if (sportsEpgFile != null && sportsIds.isNotEmpty()) {
        mergeSupplementEpgFile(
            writer = writer,
            file = sportsEpgFile,
            supplementIds = sportsIds,
            writtenChannelIds = writtenChannelIds,
            idsWithProgrammes = idsWithProgrammes,
            windowStart = windowStart,
            windowEnd = windowEnd,
            channelCountRef = { channelCount = it },
            programmeCountRef = { programmeCount = it },
            getChannelCount = { channelCount },
            getProgrammeCount = { programmeCount },
        )
      }

      realProgrammeCount = programmeCount
      realChannelsWithProgrammes = idsWithProgrammes.size
      if (placeholdersEnabled) {
        val gapIds = allIds.filter {
            it !in idsWithProgrammes && it !in placeholderExcludeIds
        }.toSet()
        if (gapIds.isNotEmpty()) {
          placeholderProgrammeCount = PlaceholderProgrammeWriter.appendPlaceholders(
              writer = writer,
              channelIds = gapIds,
              channelNames = channelNamesByTvgId,
              windowStart = windowStart,
              windowEnd = windowEnd,
              writtenChannelIds = writtenChannelIds,
              idsWithProgrammes = idsWithProgrammes,
          )
          channelCount = writtenChannelIds.size
          programmeCount = realProgrammeCount + placeholderProgrammeCount
        }
      }

      writer.write("\n</tv>\n")
    }

    store.trimFeedCache()
    return BuildResult(
        outputFile = output,
        channelCount = channelCount,
        programmeCount = programmeCount,
        channelIdsWithProgrammes = idsWithProgrammes,
        realProgrammeCount = realProgrammeCount,
        placeholderProgrammeCount = placeholderProgrammeCount,
        channelsWithRealProgrammes = realChannelsWithProgrammes,
        channelsWithPlaceholders = if (placeholderProgrammeCount > 0) {
            idsWithProgrammes.size - realChannelsWithProgrammes
        } else {
            0
        },
    )
  }

  private fun mergeEpgshareFeed(
      writer: java.io.BufferedWriter,
      cache: File,
      playlistIds: Set<String>,
      expansion: EpgTvgIdMatcher.IdExpansion,
      writtenChannelIds: MutableSet<String>,
      idsWithProgrammes: MutableSet<String>,
      windowStart: java.time.Instant,
      windowEnd: java.time.Instant,
      channelCountRef: (Int) -> Unit,
      programmeCountRef: (Int) -> Unit,
      getChannelCount: () -> Int,
      getProgrammeCount: () -> Int,
  ) {
    val lookupIds = expansion.lookupIds
    var channelCount = getChannelCount()
    var programmeCount = getProgrammeCount()

    XmltvParser.iterBlocksFromGzip(cache, "channel", "channel", "id", lookupIds).forEach channelBlock@{ block ->
      val feedId = XmltvParser.blockAttrValue(block, "id") ?: return@channelBlock
      val playlistId = EpgTvgIdMatcher.canonicalPlaylistId(expansion, feedId) ?: return@channelBlock
      if (playlistId !in playlistIds) return@channelBlock
      if (!writtenChannelIds.add(playlistId)) return@channelBlock
      val out = if (feedId != playlistId) {
        XmltvParser.rewriteIdAttributes(block, listOf("id"), playlistId)
      } else {
        block.trim()
      }
      writer.write("\n")
      writer.write(out)
      channelCount++
    }
    XmltvParser.iterBlocksFromGzip(cache, "programme", "programme", "channel", lookupIds).forEach { block ->
      val feedId = XmltvParser.blockAttrValue(block, "channel") ?: return@forEach
      val playlistId = EpgTvgIdMatcher.canonicalPlaylistId(expansion, feedId) ?: return@forEach
      if (playlistId !in playlistIds) return@forEach
      if (!XmltvParser.programmeInWindow(block, windowStart, windowEnd)) return@forEach
      val out = if (feedId != playlistId) {
        XmltvParser.rewriteIdAttributes(block, listOf("channel"), playlistId)
      } else {
        block.trim()
      }
      writer.write("\n")
      writer.write(out)
      programmeCount++
      idsWithProgrammes += playlistId
    }
    channelCountRef(channelCount)
    programmeCountRef(programmeCount)
  }

  private fun mergeSupplementEpgFile(
      writer: java.io.BufferedWriter,
      file: File,
      supplementIds: Set<String>,
      writtenChannelIds: MutableSet<String>,
      idsWithProgrammes: MutableSet<String>,
      windowStart: java.time.Instant,
      windowEnd: java.time.Instant,
      channelCountRef: (Int) -> Unit,
      programmeCountRef: (Int) -> Unit,
      getChannelCount: () -> Int,
      getProgrammeCount: () -> Int,
      useIdExpansion: Boolean = false,
  ) {
    val expansion = if (useIdExpansion) {
      EpgTvgIdMatcher.expandWantedIds(supplementIds)
    } else {
      EpgTvgIdMatcher.IdExpansion(lookupIds = supplementIds, remapToPlaylist = supplementIds.associateWith { it })
    }
    val lookupIds = expansion.lookupIds
    var channelCount = getChannelCount()
    var programmeCount = getProgrammeCount()

    XmltvParser.iterBlocksFromFile(
        file,
        "channel",
        "channel",
        "id",
        lookupIds,
    ).forEach channelBlock@{ block ->
      val feedId = XmltvParser.blockAttrValue(block, "id") ?: return@channelBlock
      val playlistId = EpgTvgIdMatcher.canonicalPlaylistId(expansion, feedId) ?: feedId
      if (playlistId !in supplementIds) return@channelBlock
      if (!writtenChannelIds.add(playlistId)) return@channelBlock
      val out = if (useIdExpansion && feedId != playlistId) {
        XmltvParser.rewriteIdAttributes(block, listOf("id"), playlistId)
      } else {
        block.trim()
      }
      writer.write("\n")
      writer.write(out)
      channelCount++
    }
    XmltvParser.iterBlocksFromFile(
        file,
        "programme",
        "programme",
        "channel",
        lookupIds,
    ).forEach { block ->
      val feedId = XmltvParser.blockAttrValue(block, "channel") ?: return@forEach
      val playlistId = EpgTvgIdMatcher.canonicalPlaylistId(expansion, feedId) ?: feedId
      if (playlistId !in supplementIds) return@forEach
      if (!XmltvParser.programmeInWindow(block, windowStart, windowEnd)) return@forEach
      val out = if (useIdExpansion && feedId != playlistId) {
        XmltvParser.rewriteIdAttributes(block, listOf("channel"), playlistId)
      } else {
        block.trim()
      }
      writer.write("\n")
      writer.write(out)
      programmeCount++
      idsWithProgrammes += playlistId
    }
    channelCountRef(channelCount)
    programmeCountRef(programmeCount)
  }

  private fun ensureFeedCached(url: String) {
    val cache = store.feedCacheFile(url)
    if (store.isFeedFresh(cache)) return
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", EpgConfig.USER_AGENT)
        .get()
        .build()
    val tmp = File(cache.parentFile, "${cache.name}.part")
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("feed_download_failed:${response.code}")
      val body = response.body ?: error("feed_empty")
      val sink = tmp.outputStream()
      val max = EpgConfig.MAX_FEED_BYTES.toLong()
      var total = 0L
      body.byteStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          total += read
          if (total > max) error("feed_exceeded_max_bytes")
          sink.write(buffer, 0, read)
        }
      }
      sink.close()
    }
    if (!tmp.renameTo(cache)) {
      cache.writeBytes(tmp.readBytes())
      tmp.delete()
    }
  }

  data class BuildResult(
      val outputFile: File,
      val channelCount: Int,
      val programmeCount: Int,
      val channelIdsWithProgrammes: Set<String> = emptySet(),
      val realProgrammeCount: Int = programmeCount,
      val placeholderProgrammeCount: Int = 0,
      val channelsWithRealProgrammes: Int = 0,
      val channelsWithPlaceholders: Int = 0,
  )

  companion object {
    fun emptyXml(): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="StepDaddy Gateway"></tv>"""
            .toByteArray(Charsets.UTF_8)

    /** Skip epgshare US2 for Eastern cable ids that have a tvtv.us East bridge row. */
    fun playlistIdsForEpgshareMerge(
        tvgIds: Set<String>,
        hasTvtvBridge: (String) -> Boolean,
    ): Set<String> =
        tvgIds.filter { playlistId ->
            playlistId !in TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS ||
                !hasTvtvBridge(playlistId)
        }.toSet()

    fun groupTvgIdsByFeed(tvgIds: Set<String>): Map<String, Set<String>> {
      val grouped = linkedMapOf<String, MutableSet<String>>()
      tvgIds.forEach { tvgId ->
        val routed = scheduleUrlsForTvgId(tvgId)
            .filter { it in EpgConfig.PRIMARY_FEED_URLS }
        val primary = routed.firstOrNull() ?: return@forEach
        grouped.getOrPut(primary) { linkedSetOf() } += tvgId
      }
      return grouped
    }

    fun groupTvgIdsByGapFillFeed(tvgIds: Set<String>): Map<String, Set<String>> {
      val grouped = linkedMapOf<String, MutableSet<String>>()
      tvgIds.forEach { tvgId ->
        val feed = gapFillUrlForTvgId(tvgId) ?: return@forEach
        grouped.getOrPut(feed) { linkedSetOf() } += tvgId
      }
      return grouped
    }

    fun isHashStyleFastId(tvgId: String): Boolean {
      if ('.' !in tvgId) return true
      return tvgId.uppercase().startsWith("USBD")
    }

    fun gapFillUrlForTvgId(tvgId: String): String? {
      val tl = tvgId.lowercase()
      if (isHashStyleFastId(tvgId) || "plex" in tl) {
        return feedUrlContaining("PLEX1")
      }
      if ("distro" in tl) {
        return feedUrlContaining("DISTROTV1")
      }
      val regional = listOf(
          listOf(".uk", ".gb", ".ie") to "UK1",
          listOf(".de") to "DE1",
          listOf(".fr") to "FR1",
          listOf(".it") to "IT1",
          listOf(".es") to "ES1",
          listOf(".ca") to "CA2",
          listOf(".au") to "AU1",
          listOf(".nz") to "NZ1",
          listOf(".tr") to "TR1",
          listOf(".ae") to "AE1",
          listOf(".br") to "BR1",
          listOf("bein") to "BEIN1",
      )
      regional.forEach { (markers, feedName) ->
        if (markers.any { tl.contains(it) }) {
          return feedUrlContaining(feedName)
        }
      }
      return EpgConfig.GAP_FILL_FEED_URLS.firstOrNull()
    }

    private fun feedUrlContaining(token: String): String? =
        EpgConfig.GAP_FILL_FEED_URLS.firstOrNull { token in it }

    fun scheduleUrlsForTvgId(tvgId: String): List<String> {
      val tl = tvgId.lowercase()
      val routed = mutableListOf<String>()
      val routes: List<Pair<List<String>, List<String>>> = listOf(
          listOf(".us2", ".us", "us_locals", "milb-", "fanduel", "draftkings") to usFeeds(),
          listOf(".uk", ".gb", ".ie") to listOf(feed("UK1"), feed("IE1")),
          listOf(".de") to listOf(feed("DE1"), feed("AT1")),
          listOf(".fr") to listOf(feed("FR1")),
          listOf(".it") to listOf(feed("IT1")),
          listOf(".es") to listOf(feed("ES1")),
          listOf(".pt") to listOf(feed("PT1")),
          listOf(".tr") to listOf(feed("TR1"), feed("TR3")),
          listOf(".ae") to listOf(feed("AE1")),
          listOf(".ba", ".hr", ".rs", ".me", ".mk", ".si") to listOf(feed("BA1"), feed("HR1"), feed("RS1")),
          listOf(".dk") to listOf(feed("DK1")),
          listOf(".no") to listOf(feed("NO1")),
          listOf(".se") to listOf(feed("SE1")),
          listOf(".fi") to listOf(feed("FI1")),
          listOf(".ca") to listOf(feed("CA2")),
          listOf(".au", ".nz") to listOf(feed("AU1"), feed("NZ1")),
          listOf(".gr") to listOf(feed("GR1")),
          listOf(".mx") to listOf(feed("MX1")),
          listOf(".br") to listOf(feed("BR1")),
          listOf(".pl") to listOf(feed("PL1")),
          listOf(".nl") to listOf(feed("NL1")),
          listOf(".in") to listOf(feed("IN1")),
          listOf(".pk") to listOf(feed("PK1")),
          listOf("bein") to listOf(feed("BEIN1")),
      )
      routes.forEach { (markers, feeds) ->
        if (markers.any { tl.contains(it) }) {
          feeds.forEach { url ->
            if (url !in routed) routed += url
          }
        }
      }
      EpgConfig.PRIMARY_FEED_URLS.forEach { url ->
        if (url !in routed) routed += url
      }
      if ("us_locals" in tl) {
        val locals = feed("US_LOCALS1")
        return listOf(locals) + routed.filter { it != locals }
      }
      return routed
    }

    private fun usFeeds(): List<String> = EpgConfig.PRIMARY_FEED_URLS

    private fun feed(name: String): String =
        "https://epgshare01.online/epgshare01/epg_ripper_${name}.xml.gz"

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(EpgConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(EpgConfig.DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
            .build()
  }
}

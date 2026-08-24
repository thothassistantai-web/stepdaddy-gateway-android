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
  /** Consecutive feed download failures in the current build (resets on success). */
  private var consecutiveFeedFailures = 0

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
      whatsOnFreeTvEpgCatalog: WhatsOnFreeTvEpgCatalog? = null,
      placeholdersEnabled: Boolean = true,
      placeholderExcludeIds: Set<String> = emptySet(),
      tvtvGapFillEnabled: Boolean = true,
      forceRefresh: Boolean = false,
  ): BuildResult {
    consecutiveFeedFailures = 0
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
    var woftvProgrammesMerged = 0
    var woftvChannelsFilled = 0

    output.bufferedWriter(Charsets.UTF_8).use { writer ->
      writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
      writer.write("\n<tv generator-info-name=\"StepDaddy Gateway\">")

      val easternPreferredIds = playlistIdsForEasternTvtvPass(tvgIds) { playlistId ->
          tvtvFetcher?.bridgeEntry(playlistId) != null
      }
      tvtvFetcher?.resetBuildState()
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
            maxChannels = TvtvUsEpgConfig.MAX_EASTERN_CHANNELS_PER_BUILD,
            passKind = TvtvUsEpgFetcher.TvtvPassKind.EASTERN,
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

      val woftvEarly = mergeWoftvGapsIfReady(
          whatsOnFreeTvEpgCatalog = whatsOnFreeTvEpgCatalog,
          writer = writer,
          allIds = allIds,
          idsWithProgrammes = idsWithProgrammes,
          fastEpgTvgIds = fastEpgTvgIds,
          iptvOrgSupplementTvgIds = iptvOrgSupplementTvgIds,
          idsWithProgrammesBeforeGapFill = null,
          channelNamesByTvgId = channelNamesByTvgId,
          windowStart = windowStart,
          windowEnd = windowEnd,
          writtenChannelIds = writtenChannelIds,
          passLabel = "pre-gap-fill",
      )
      woftvProgrammesMerged += woftvEarly.programmesWritten
      woftvChannelsFilled += woftvEarly.channelsFilled
      programmeCount += woftvEarly.programmesWritten

      val idsWithProgrammesBeforeGapFill = idsWithProgrammes.toSet()
      val epgshareGapIds = (
          tvgIds.filter { it !in idsWithProgrammes } +
              fastEpgTvgIds.filter { isHashStyleFastId(it) && it !in idsWithProgrammes }
          ).toSet()
      if (epgshareGapIds.isNotEmpty()) {
        val gapGrouped = groupTvgIdsByGapFillFeed(epgshareGapIds)
        val cacheOnlyGap = shouldUseCacheOnlyGapFill(programmeCount, forceRefresh)
        var networkBudget = gapFillNetworkAttempts(programmeCount, forceRefresh)
        if (cacheOnlyGap) {
          android.util.Log.i(
              "LightEpgBuilder",
              "EPG gap-fill cache-only ($programmeCount programmes already merged)",
          )
        } else if (forceRefresh) {
          android.util.Log.i(
              "LightEpgBuilder",
              "EPG gap-fill force refresh ($networkBudget network attempts, ${gapGrouped.size} feeds)",
          )
        }
        if (forceRefresh) {
          consecutiveFeedFailures = 0
        }
        gapGrouped.keys.forEach { url ->
          val cache = store.feedCacheFile(url)
          val hasCache = cache.exists() && cache.length() > 0L
          if (hasCache) {
            // Never block the build refreshing regional feeds that we already have.
            return@forEach
          }
          if (networkBudget <= 0) {
            android.util.Log.w(
                "LightEpgBuilder",
                "EPG gap-fill skipped (no cache, network budget exhausted): $url",
            )
            return@forEach
          }
          networkBudget--
          if (forceRefresh) consecutiveFeedFailures = 0
          ensureFeedCached(
              url,
              timeoutMs = EpgConfig.GAP_FILL_DOWNLOAD_TIMEOUT_MS,
              ignoreFailureBudget = forceRefresh,
          )
        }
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

      val tvtvBridgeIds = tvtvFetcher?.let { fetcher ->
          playlistIdsForGeneralTvtvGapFill(
              tvgIds = tvgIds,
              supplementTvgIds = supplementTvgIds,
              sportsTvgIds = sportsTvgIds,
              fastEpgTvgIds = fastEpgTvgIds,
              idsWithProgrammes = idsWithProgrammes,
              hasTvtvBridge = { fetcher.bridgeEntry(it) != null },
          )
      }.orEmpty()
      if (
          tvtvGapFillEnabled &&
              tvtvFetcher != null &&
              tvtvBridgeIds.isNotEmpty() &&
              !tvtvFetcher.isRateLimitExhausted()
      ) {
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
            maxChannels = TvtvUsEpgConfig.MAX_GENERAL_CHANNELS_PER_BUILD,
            passKind = TvtvUsEpgFetcher.TvtvPassKind.GENERAL,
        )
        if (programmeCount > beforeProgrammes) {
          android.util.Log.i(
              "LightEpgBuilder",
              "tvtv.us EPG: +${programmeCount - beforeProgrammes} programmes for ${tvtvBridgeIds.size} cable ids",
          )
        }
      } else if (tvtvGapFillEnabled && tvtvFetcher?.isRateLimitExhausted() == true) {
        android.util.Log.w(
            "LightEpgBuilder",
            "tvtv.us general gap-fill skipped (rate limit exhausted after Eastern pass)",
        )
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

      val woftvLate = mergeWoftvGapsIfReady(
          whatsOnFreeTvEpgCatalog = whatsOnFreeTvEpgCatalog,
          writer = writer,
          allIds = allIds,
          idsWithProgrammes = idsWithProgrammes,
          fastEpgTvgIds = fastEpgTvgIds,
          iptvOrgSupplementTvgIds = iptvOrgSupplementTvgIds,
          idsWithProgrammesBeforeGapFill = idsWithProgrammesBeforeGapFill,
          channelNamesByTvgId = channelNamesByTvgId,
          windowStart = windowStart,
          windowEnd = windowEnd,
          writtenChannelIds = writtenChannelIds,
          passLabel = "post-supplement",
      )
      woftvProgrammesMerged += woftvLate.programmesWritten
      woftvChannelsFilled += woftvLate.channelsFilled
      channelCount = writtenChannelIds.size
      programmeCount += woftvLate.programmesWritten
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
        woftvProgrammesMerged = woftvProgrammesMerged,
        woftvChannelsFilled = woftvChannelsFilled,
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

  private data class WoftvMergePassResult(
      val programmesWritten: Int = 0,
      val channelsFilled: Int = 0,
      val passLabel: String = "",
  )

  private fun mergeWoftvGapsIfReady(
      whatsOnFreeTvEpgCatalog: WhatsOnFreeTvEpgCatalog?,
      writer: java.io.BufferedWriter,
      allIds: Set<String>,
      idsWithProgrammes: MutableSet<String>,
      fastEpgTvgIds: Set<String>,
      iptvOrgSupplementTvgIds: Set<String>,
      idsWithProgrammesBeforeGapFill: Set<String>?,
      channelNamesByTvgId: Map<String, String>,
      windowStart: java.time.Instant,
      windowEnd: java.time.Instant,
      writtenChannelIds: MutableSet<String>,
      passLabel: String,
  ): WoftvMergePassResult {
    val catalog = whatsOnFreeTvEpgCatalog ?: return WoftvMergePassResult(passLabel = passLabel)
    if (!catalog.hasUsableIndex()) return WoftvMergePassResult(passLabel = passLabel)
    val gapIds = woftvCandidateIds(
        allIds = allIds,
        idsWithProgrammes = idsWithProgrammes,
        fastEpgTvgIds = fastEpgTvgIds,
        iptvOrgSupplementTvgIds = iptvOrgSupplementTvgIds,
    )
    val forceRetry = if (idsWithProgrammesBeforeGapFill != null) {
      woftvGapFillRetryIds(
          idsWithProgrammes = idsWithProgrammes,
          idsWithProgrammesBeforeGapFill = idsWithProgrammesBeforeGapFill,
          fastEpgTvgIds = fastEpgTvgIds,
          iptvOrgSupplementTvgIds = iptvOrgSupplementTvgIds,
      )
    } else {
      emptySet()
    }
    val attemptIds = gapIds + forceRetry
    if (attemptIds.isEmpty()) return WoftvMergePassResult(passLabel = passLabel)
    android.util.Log.i(
        "LightEpgBuilder",
        "WhatsOnFreeTV EPG ($passLabel): ${attemptIds.size} candidate ids " +
            "(${gapIds.size} gaps, ${forceRetry.size} gap-fill retries)",
    )
    val result = catalog.mergeGaps(
        writer = writer,
        gapTvgIds = attemptIds,
        channelNamesByTvgId = channelNamesByTvgId,
        windowStart = windowStart,
        windowEnd = windowEnd,
        writtenChannelIds = writtenChannelIds,
        idsWithProgrammes = idsWithProgrammes,
        forceRetryIds = forceRetry,
    )
    if (result.programmesWritten > 0) {
      android.util.Log.i(
          "LightEpgBuilder",
          "WhatsOnFreeTV EPG ($passLabel): +${result.programmesWritten} programmes " +
              "(${result.channelsFilled} channels, ${idsWithProgrammes.size} ids with guide)",
      )
    }
    return WoftvMergePassResult(
        programmesWritten = result.programmesWritten,
        channelsFilled = result.channelsFilled,
        passLabel = passLabel,
    )
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

  private fun ensureFeedCached(
      url: String,
      timeoutMs: Long = EpgConfig.DOWNLOAD_TIMEOUT_MS,
      ignoreFailureBudget: Boolean = false,
  ) {
    val cache = store.feedCacheFile(url)
    if (store.isFeedFresh(cache)) {
      consecutiveFeedFailures = 0
      return
    }
    if (!ignoreFailureBudget && consecutiveFeedFailures >= MAX_CONSECUTIVE_FEED_FAILURES) {
      if (cache.exists() && cache.length() > 0L) {
        android.util.Log.w(
            "LightEpgBuilder",
            "EPG feed skip (network budget exhausted), using stale cache: $url",
        )
      } else {
        android.util.Log.w(
            "LightEpgBuilder",
            "EPG feed skip (network budget exhausted): $url",
        )
      }
      return
    }
    val request = Request.Builder()
        .url(EpgConfig.feedDownloadUrl(url))
        .header("User-Agent", EpgConfig.USER_AGENT)
        .header("Cache-Control", "no-cache")
        .get()
        .build()
    val tmp = File(cache.parentFile, "${cache.name}.part")
    val client =
        if (timeoutMs == EpgConfig.DOWNLOAD_TIMEOUT_MS) {
          httpClient
        } else {
          httpClient
              .newBuilder()
              .connectTimeout(minOf(10_000L, timeoutMs), TimeUnit.MILLISECONDS)
              .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
              .writeTimeout(minOf(15_000L, timeoutMs), TimeUnit.MILLISECONDS)
              .callTimeout(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
              .build()
        }
    try {
      client.newCall(request).execute().use { response ->
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
      if (url.endsWith(".gz", ignoreCase = true) && !store.isValidGzipFile(tmp)) {
        error("feed_not_gzip")
      }
      if (!tmp.renameTo(cache)) {
        cache.writeBytes(tmp.readBytes())
        tmp.delete()
      }
      consecutiveFeedFailures = 0
    } catch (exc: Exception) {
      runCatching { tmp.delete() }
      consecutiveFeedFailures++
      if (cache.exists() && cache.length() > 0L) {
        android.util.Log.w(
            "LightEpgBuilder",
            "EPG feed refresh failed, using stale cache: $url (${exc.message})",
        )
        return
      }
      android.util.Log.w(
          "LightEpgBuilder",
          "EPG feed unavailable, skipping (no cache): $url (${exc.message})",
      )
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
      val woftvProgrammesMerged: Int = 0,
      val woftvChannelsFilled: Int = 0,
  )

  companion object {
    private const val MAX_CONSECUTIVE_FEED_FAILURES = 3

    fun shouldUseCacheOnlyGapFill(programmeCount: Int, forceRefresh: Boolean): Boolean =
        !forceRefresh && programmeCount >= EpgConfig.MIN_PROGRAMMES_BEFORE_CACHE_ONLY_GAP

    fun gapFillNetworkAttempts(programmeCount: Int, forceRefresh: Boolean): Int =
        when {
          shouldUseCacheOnlyGapFill(programmeCount, forceRefresh) -> 0
          forceRefresh -> EpgConfig.MAX_GAP_FILL_NETWORK_ATTEMPTS_FORCE_REFRESH
          else -> EpgConfig.MAX_GAP_FILL_NETWORK_ATTEMPTS
        }

    /** Playlist ids still missing real programmes after upstream merges. */
    fun woftvCandidateIds(
        allIds: Set<String>,
        idsWithProgrammes: Set<String>,
        fastEpgTvgIds: Set<String>,
        iptvOrgSupplementTvgIds: Set<String>,
    ): Set<String> =
        (
            allIds.filter { it !in idsWithProgrammes } +
                fastEpgTvgIds.filter { it !in idsWithProgrammes } +
                iptvOrgSupplementTvgIds.filter { it !in idsWithProgrammes }
            ).toSet()

    /**
     * FAST / iptv-org / DaddyLive hash ids that received only epgshare gap-fill
     * (e.g. PLEX1) programmes before WOFTV ran — allow a second WOFTV pass to
     * append real guide rows (thin PLEX1 must not block WOFTV for Pluto hex).
     */
    fun woftvGapFillRetryIds(
        idsWithProgrammes: Set<String>,
        idsWithProgrammesBeforeGapFill: Set<String>,
        fastEpgTvgIds: Set<String>,
        iptvOrgSupplementTvgIds: Set<String>,
    ): Set<String> {
      val gapFillOnly = idsWithProgrammes - idsWithProgrammesBeforeGapFill
      return gapFillOnly.filter { id ->
        id in fastEpgTvgIds ||
            id in iptvOrgSupplementTvgIds ||
            FastChannelContext.isHashFastGapFillRetryId(id)
      }.toSet()
    }

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

    /** Eastern premium ids first, in fixed preferred order, with dedicated rate budget. */
    fun playlistIdsForEasternTvtvPass(
        tvgIds: Set<String>,
        hasTvtvBridge: (String) -> Boolean,
    ): List<String> =
        TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS
            .filter { it in tvgIds && hasTvtvBridge(it) }
            .take(TvtvUsEpgConfig.MAX_EASTERN_CHANNELS_PER_BUILD)

    /** General tvtv gap-fill: playlist ids first, then supplements; never Eastern preferred. */
    fun playlistIdsForGeneralTvtvGapFill(
        tvgIds: Set<String>,
        supplementTvgIds: Set<String>,
        sportsTvgIds: Set<String>,
        fastEpgTvgIds: Set<String>,
        idsWithProgrammes: Set<String>,
        hasTvtvBridge: (String) -> Boolean,
    ): List<String> {
        val tvtvGapIds = (
            tvgIds.filter { it !in idsWithProgrammes } +
                supplementTvgIds.filter { it !in idsWithProgrammes } +
                sportsTvgIds.filter { it !in idsWithProgrammes } +
                fastEpgTvgIds.filter { it !in idsWithProgrammes }
            ).toSet()
        val bridged = tvtvGapIds.filter(hasTvtvBridge)
        return buildList {
            addAll(
                tvgIds.filter {
                    it in bridged && it !in TvtvUsEpgConfig.EASTERN_PREFERRED_PLAYLIST_IDS
                },
            )
            addAll(bridged.filter { it !in tvgIds })
        }.distinct().take(TvtvUsEpgConfig.MAX_GENERAL_CHANNELS_PER_BUILD)
    }

    fun groupTvgIdsByFeed(tvgIds: Set<String>): Map<String, Set<String>> {
      val grouped = linkedMapOf<String, MutableSet<String>>()
      tvgIds.forEach { tvgId ->
        val routed = scheduleUrlsForTvgId(tvgId)
            .filter { it in EpgConfig.PRIMARY_FEED_URLS }
        if (routed.isEmpty()) return@forEach
        // Assign to every primary feed the id routes to (US2 + sports + locals).
        // US-only first-feed grouping previously skipped US_LOCALS1, so bridges to
        // `*.us_locals1` never matched programmes during the primary merge.
        routed.forEach { url ->
          grouped.getOrPut(url) { linkedSetOf() } += tvgId
        }
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

    fun isHashStyleFastId(tvgId: String): Boolean = FastChannelContext.isHashStyleFastId(tvgId)

    fun gapFillUrlForTvgId(tvgId: String): String? {
      val tl = tvgId.lowercase()
      // Quality/region suffixes (@HD, @East, …) must not force PLEX1 — strip before US checks.
      val base = tvgId.substringBefore('@').lowercase()
      if (isHashStyleFastId(tvgId) || "plex" in tl) {
        return feedUrlContaining("PLEX1")
      }
      if ("distro" in tl) {
        return feedUrlContaining("DISTROTV1")
      }
      // Bare US playlist / epgshare ids must reuse primary US2 (usually already cached),
      // not GAP_FILL's first entry (PLEX1) — Eastern-preferred gaps like HBO2.us depend on this.
      if (
          base.endsWith(".us2") ||
          base.endsWith(".us") ||
          "us_locals" in tl ||
          "milb-" in tl ||
          "fanduel" in tl ||
          "draftkings" in tl
      ) {
        return EpgConfig.PRIMARY_FEED_URLS.firstOrNull()
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

    private fun defaultClient(): OkHttpClient {
      val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 6
        maxRequestsPerHost = 2
      }
      return OkHttpClient.Builder()
          .dispatcher(dispatcher)
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(EpgConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .writeTimeout(15, TimeUnit.SECONDS)
          .callTimeout(EpgConfig.DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
          .retryOnConnectionFailure(true)
          .build()
    }
  }
}

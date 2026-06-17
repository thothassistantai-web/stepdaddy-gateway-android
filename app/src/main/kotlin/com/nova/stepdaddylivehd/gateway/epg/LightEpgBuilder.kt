package com.nova.stepdaddylivehd.gateway.epg

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class LightEpgBuilder(
    private val store: EpgStore,
    private val httpClient: OkHttpClient = defaultClient(),
) {
  fun build(tvgIds: Set<String>): BuildResult {
    val output = File(store.servedXml.parentFile, "epg.build.part")
    if (tvgIds.isEmpty()) {
      output.writeBytes(emptyXml())
      return BuildResult(output, 0, 0)
    }
    val grouped = groupTvgIdsByFeed(tvgIds)
    grouped.keys.forEach { url -> ensureFeedCached(url) }

    val windowStart = Instant.now().minusSeconds(EpgConfig.PROGRAMME_PAST_MINUTES * 60L)
    val windowEnd = Instant.now().plusSeconds(EpgConfig.PROGRAMME_FUTURE_HOURS * 3600L)

    val writtenChannelIds = linkedSetOf<String>()
    var channelCount = 0
    var programmeCount = 0

    output.bufferedWriter(Charsets.UTF_8).use { writer ->
      writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
      writer.write("\n<tv generator-info-name=\"StepDaddy Gateway\">")

      grouped.forEach { (url, ids) ->
        val cache = store.feedCacheFile(url)
        if (!cache.exists()) return@forEach
        XmltvParser.iterBlocksFromGzip(cache, "channel", "channel", "id", ids).forEach channelBlock@{ block ->
          val channelId = XmltvParser.blockAttrValue(block, "id") ?: return@channelBlock
          if (!writtenChannelIds.add(channelId)) return@channelBlock
          writer.write("\n")
          writer.write(block.trim())
          channelCount++
        }
        XmltvParser.iterBlocksFromGzip(cache, "programme", "programme", "channel", ids).forEach { block ->
          if (XmltvParser.programmeInWindow(block, windowStart, windowEnd)) {
            writer.write("\n")
            writer.write(block.trim())
            programmeCount++
          }
        }
      }

      writer.write("\n</tv>\n")
    }

    store.trimFeedCache()
    return BuildResult(output, channelCount, programmeCount)
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
  )

  companion object {
    fun emptyXml(): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="StepDaddy Gateway"></tv>"""
            .toByteArray(Charsets.UTF_8)

    fun groupTvgIdsByFeed(tvgIds: Set<String>): Map<String, Set<String>> {
      val grouped = linkedMapOf<String, MutableSet<String>>()
      tvgIds.forEach { tvgId ->
        val routed = scheduleUrlsForTvgId(tvgId)
        val primary = routed.firstOrNull() ?: return@forEach
        grouped.getOrPut(primary) { linkedSetOf() } += tvgId
      }
      return grouped
    }

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
      EpgConfig.FEED_URLS.forEach { url ->
        if (url !in routed) routed += url
      }
      if ("us_locals" in tl) {
        val locals = feed("US_LOCALS1")
        return listOf(locals) + routed.filter { it != locals }
      }
      return routed
    }

    private fun usFeeds(): List<String> = EpgConfig.FEED_URLS

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

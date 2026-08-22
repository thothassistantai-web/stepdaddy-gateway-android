package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EpgMeta(
    val builtAtMs: Long = 0L,
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    val mappedTvgCount: Int = 0,
    val buildSeconds: Double = 0.0,
    val lastError: String? = null,
    val state: String = "idle",
    val realProgrammeCount: Int = 0,
    val placeholderProgrammeCount: Int = 0,
    val channelsWithProgrammes: Int = 0,
    val channelsWithRealProgrammes: Int = 0,
    val channelsWithPlaceholders: Int = 0,
    /** Last build: programmes merged from WhatsOnFreeTV JSON (GitHub). */
    val woftvProgrammesMerged: Int = 0,
    /** Last build: playlist channels that received real WOFTV programmes. */
    val woftvChannelsFilled: Int = 0,
)

class EpgStore private constructor(
    private val root: File,
) {
  constructor(context: Context) : this(File(context.filesDir, "epg").also { it.mkdirs() })

  private val json = Json { ignoreUnknownKeys = true }
  private val feedsDir = File(root, "feeds").also { it.mkdirs() }
  val servedXml: File = File(root, "epg.xml")
  /** Gzip twin of [servedXml] for TiviMate downloads (~10× smaller than raw XMLTV). */
  val servedXmlGzip: File = File(root, "epg.xml.gz")
  private val metaFile = File(root, "meta.json")

  @Volatile
  var meta: EpgMeta = loadMeta()
    private set

  fun feedCacheFile(url: String): File {
    val digest = url.sha256Hex().take(20)
    val suffix = if (url.endsWith(".gz")) ".xml.gz" else ".xml"
    return File(feedsDir, "$digest$suffix")
  }

  fun isFeedFresh(file: File): Boolean {
    if (!file.exists()) return false
    return System.currentTimeMillis() - file.lastModified() < EpgConfig.FEED_CACHE_TTL_MS
  }

  fun readServedXml(): ByteArray? {
    if (!servedXml.exists()) return null
    return runCatching { servedXml.readBytes() }.getOrNull()
  }

  fun writeServedXml(body: ByteArray, programmeCount: Int, channelCount: Int, mappedTvgCount: Int, buildSeconds: Double) {
    val tmp = File(root, "epg.xml.tmp")
    tmp.writeBytes(body)
    commitServedXml(tmp, programmeCount, channelCount, mappedTvgCount, buildSeconds)
  }

  fun writeServedXmlFromFile(
      source: File,
      programmeCount: Int,
      channelCount: Int,
      mappedTvgCount: Int,
      buildSeconds: Double,
      realProgrammeCount: Int = programmeCount,
      placeholderProgrammeCount: Int = 0,
      channelsWithProgrammes: Int = 0,
      channelsWithRealProgrammes: Int = 0,
      channelsWithPlaceholders: Int = 0,
      woftvProgrammesMerged: Int = 0,
      woftvChannelsFilled: Int = 0,
  ) {
    val tmp = File(root, "epg.xml.tmp")
    source.inputStream().use { input ->
      tmp.outputStream().use { output -> input.copyTo(output) }
    }
    commitServedXml(
        tmp,
        programmeCount,
        channelCount,
        mappedTvgCount,
        buildSeconds,
        realProgrammeCount,
        placeholderProgrammeCount,
        channelsWithProgrammes,
        channelsWithRealProgrammes,
        channelsWithPlaceholders,
        woftvProgrammesMerged,
        woftvChannelsFilled,
    )
    runCatching { source.delete() }
  }

  private fun commitServedXml(
      tmp: File,
      programmeCount: Int,
      channelCount: Int,
      mappedTvgCount: Int,
      buildSeconds: Double,
      realProgrammeCount: Int = programmeCount,
      placeholderProgrammeCount: Int = 0,
      channelsWithProgrammes: Int = 0,
      channelsWithRealProgrammes: Int = 0,
      channelsWithPlaceholders: Int = 0,
      woftvProgrammesMerged: Int = 0,
      woftvChannelsFilled: Int = 0,
  ) {
    if (!tmp.renameTo(servedXml)) {
      tmp.copyTo(servedXml, overwrite = true)
      tmp.delete()
    }
    writeGzipSibling(servedXml)
    meta = EpgMeta(
        builtAtMs = System.currentTimeMillis(),
        channelCount = channelCount,
        programmeCount = programmeCount,
        mappedTvgCount = mappedTvgCount,
        buildSeconds = buildSeconds,
        lastError = null,
        state = "ready",
        realProgrammeCount = realProgrammeCount,
        placeholderProgrammeCount = placeholderProgrammeCount,
        channelsWithProgrammes = channelsWithProgrammes,
        channelsWithRealProgrammes = channelsWithRealProgrammes,
        channelsWithPlaceholders = channelsWithPlaceholders,
        woftvProgrammesMerged = woftvProgrammesMerged,
        woftvChannelsFilled = woftvChannelsFilled,
    )
    saveMeta()
  }

  fun updateState(state: String, error: String? = null) {
    meta = meta.copy(state = state, lastError = error)
    saveMeta()
  }

  fun ageSeconds(): Long? {
    if (meta.builtAtMs <= 0L) return null
    return (System.currentTimeMillis() - meta.builtAtMs) / 1000L
  }

  fun isServeStale(): Boolean {
    val age = ageSeconds() ?: return true
    return age > EpgConfig.STALE_SERVE_HEADER_SECONDS
  }

  fun isStale(): Boolean {
    val age = ageSeconds() ?: return true
    return age > EpgConfig.STALE_REBUILD_SECONDS
  }

  /** Drop served guide so the next refresh rebuilds with corrected channel mappings. */
  fun invalidateBuild() {
    meta = EpgMeta(state = "pending", lastError = null)
    saveMeta()
    if (servedXml.isFile) servedXml.delete()
    if (servedXmlGzip.isFile) servedXmlGzip.delete()
  }

  /** Ensure gzip twin exists (lazy backfill for builds written before gzip support). */
  fun ensureGzipSibling(): File? {
    if (!servedXml.isFile || servedXml.length() <= 0L) return null
    if (servedXmlGzip.isFile &&
        servedXmlGzip.length() > 0L &&
        servedXmlGzip.lastModified() >= servedXml.lastModified()
    ) {
      return servedXmlGzip
    }
    return writeGzipSibling(servedXml)
  }

  private fun writeGzipSibling(xmlFile: File): File? {
    if (!xmlFile.isFile || xmlFile.length() <= 0L) return null
    return runCatching {
      val tmp = File(root, "epg.xml.gz.tmp")
      java.util.zip.GZIPOutputStream(tmp.outputStream().buffered()).use { gz ->
        xmlFile.inputStream().buffered().use { input -> input.copyTo(gz) }
      }
      if (!tmp.renameTo(servedXmlGzip)) {
        tmp.copyTo(servedXmlGzip, overwrite = true)
        tmp.delete()
      }
      servedXmlGzip
    }.getOrNull()
  }

  fun trimFeedCache() {
    val files = feedsDir.listFiles()?.filter { it.isFile }.orEmpty()
    var total = files.sumOf { it.length() }
    if (total <= EpgConfig.MAX_FEED_CACHE_BYTES) return
    val primaryNames =
        EpgConfig.PRIMARY_FEED_URLS.map { feedCacheFile(it).name }.toSet()
    files
        .filter { it.name !in primaryNames }
        .sortedBy { it.lastModified() }
        .forEach { file ->
          if (total <= EpgConfig.MAX_FEED_CACHE_BYTES) return
          total -= file.length()
          file.delete()
        }
  }

  /** Reject Cloudflare HTML error pages masquerading as gzip downloads. */
  fun isValidGzipFile(file: File): Boolean {
    if (!file.isFile || file.length() < 2L) return false
    return runCatching {
      file.inputStream().use { input ->
        input.read() == 0x1f && input.read() == 0x8b
      }
    }.getOrDefault(false)
  }

  private fun loadMeta(): EpgMeta {
    if (!metaFile.exists()) return EpgMeta()
    return runCatching { json.decodeFromString<EpgMeta>(metaFile.readText()) }.getOrElse { EpgMeta() }
  }

  private fun saveMeta() {
    metaFile.writeText(json.encodeToString(meta))
  }

  private fun String.sha256Hex(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  companion object {
    fun forTest(root: File): EpgStore {
      root.mkdirs()
      return EpgStore(root)
    }
  }
}

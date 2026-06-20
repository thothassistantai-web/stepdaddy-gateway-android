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
)

class EpgStore(context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val root = File(context.filesDir, "epg").also { it.mkdirs() }
  private val feedsDir = File(root, "feeds").also { it.mkdirs() }
  val servedXml: File = File(root, "epg.xml")
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
  ) {
    val tmp = File(root, "epg.xml.tmp")
    source.inputStream().use { input ->
      tmp.outputStream().use { output -> input.copyTo(output) }
    }
    commitServedXml(tmp, programmeCount, channelCount, mappedTvgCount, buildSeconds)
    runCatching { source.delete() }
  }

  private fun commitServedXml(
      tmp: File,
      programmeCount: Int,
      channelCount: Int,
      mappedTvgCount: Int,
      buildSeconds: Double,
  ) {
    if (!tmp.renameTo(servedXml)) {
      tmp.copyTo(servedXml, overwrite = true)
      tmp.delete()
    }
    meta = EpgMeta(
        builtAtMs = System.currentTimeMillis(),
        channelCount = channelCount,
        programmeCount = programmeCount,
        mappedTvgCount = mappedTvgCount,
        buildSeconds = buildSeconds,
        lastError = null,
        state = "ready",
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

  fun trimFeedCache() {
    val files = feedsDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
    var total = files.sumOf { it.length() }
    if (total <= EpgConfig.MAX_FEED_CACHE_BYTES) return
    files.drop(1).forEach { file ->
      if (total <= EpgConfig.MAX_FEED_CACHE_BYTES) return
      total -= file.length()
      file.delete()
    }
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
}

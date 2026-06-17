package com.nova.stepdaddylivehd.gateway.epg

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * Low-memory streaming extraction of channel/programme blocks from gzipped XMLTV.
 */
object XmltvParser {
  private const val CHUNK_SIZE = 256 * 1024

  fun iterBlocksFromGzip(
      file: File,
      startTag: String,
      endTag: String,
      attrName: String,
      wantedIds: Set<String>,
  ): Sequence<String> = sequence {
    if (!file.exists() || wantedIds.isEmpty()) return@sequence
    GZIPInputStream(file.inputStream()).use { gzip ->
      yieldAll(iterBlocksFromStream(gzip, startTag, endTag, attrName, wantedIds))
    }
  }

  fun iterBlocksFromStream(
      input: InputStream,
      startTag: String,
      endTag: String,
      attrName: String,
      wantedIds: Set<String>,
  ): Sequence<String> = sequence {
    val startMarker = "<$startTag ".toByteArray(Charsets.UTF_8)
    val endMarker = "</$endTag>".toByteArray(Charsets.UTF_8)
    val attrPrefix = "$attrName=\"".toByteArray(Charsets.UTF_8)
    val wanted = wantedIds.toSet()
    var buf = ByteArray(0)

    val chunk = ByteArray(CHUNK_SIZE)
    while (true) {
      val read = input.read(chunk)
      if (read <= 0) break
      buf = buf + chunk.copyOf(read)
      while (true) {
        val start = buf.indexOf(startMarker)
        if (start < 0) {
          buf = if (buf.size > 256) buf.copyOfRange(buf.size - 256, buf.size) else buf
          break
        }
        val end = buf.indexOf(endMarker, start)
        if (end < 0) {
          buf = buf.copyOfRange(start, buf.size)
          break
        }
        val blockEnd = end + endMarker.size
        val block = buf.copyOfRange(start, blockEnd)
        buf = buf.copyOfRange(blockEnd, buf.size)
        val attrPos = block.indexOf(attrPrefix)
        if (attrPos < 0) continue
        val valueStart = attrPos + attrPrefix.size
        val valueEnd = block.indexOf('"'.code.toByte(), valueStart)
        if (valueEnd < 0) continue
        val value = block.copyOfRange(valueStart, valueEnd).toString(Charsets.UTF_8)
        if (value in wanted) {
          yield(block.toString(Charsets.UTF_8))
        }
      }
    }
  }

  fun programmeInWindow(block: String, windowStart: Instant, windowEnd: Instant): Boolean {
    val startRaw = attrValue(block, "start") ?: return false
    val stopRaw = attrValue(block, "stop") ?: return false
    val start = parseXmltvInstant(startRaw) ?: return false
    val stop = parseXmltvInstant(stopRaw) ?: return false
    return stop.isAfter(windowStart) && start.isBefore(windowEnd)
  }

  fun parseXmltvInstant(raw: String): Instant? {
    val value = raw.trim()
    val tzSuffix = Regex("""\s[+-]\d{4}$""").find(value)?.value?.trim()
    val digits = value.takeWhile { it.isDigit() }
    return runCatching {
      val ldt = when (digits.length) {
        14 -> LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        12 -> LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        else -> return null
      }
      if (tzSuffix != null) {
        val offset = ZoneOffset.of(tzSuffix)
        ldt.atOffset(offset).toInstant()
      } else {
        ldt.toInstant(ZoneOffset.UTC)
      }
    }.getOrNull()
  }

  fun blockAttrValue(block: String, name: String): String? = attrValue(block, name)

  private fun attrValue(block: String, name: String): String? {
    val needle = "$name=\""
    val start = block.indexOf(needle)
    if (start < 0) return null
    val valueStart = start + needle.length
    val valueEnd = block.indexOf('"', valueStart)
    if (valueEnd < 0) return null
    return block.substring(valueStart, valueEnd)
  }

  private fun ByteArray.indexOf(target: ByteArray, fromIndex: Int = 0): Int {
    if (target.isEmpty() || fromIndex >= size) return -1
    outer@ for (i in fromIndex..size - target.size) {
      for (j in target.indices) {
        if (this[i + j] != target[j]) continue@outer
      }
      return i
    }
    return -1
  }

  private fun ByteArray.indexOf(byte: Byte, fromIndex: Int = 0): Int {
    for (i in fromIndex until size) {
      if (this[i] == byte) return i
    }
    return -1
  }
}

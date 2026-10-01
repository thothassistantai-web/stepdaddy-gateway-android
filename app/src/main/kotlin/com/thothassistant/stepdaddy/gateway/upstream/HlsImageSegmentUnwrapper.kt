package com.thothassistant.stepdaddy.gateway.upstream

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * DaddyLive CDN (edge.*.sbs) wraps MPEG-TS fragments inside PNG/WebP containers
 * (TPIX pixel payload, IEND trailer, or EXIF). Browser Clappr unwraps these;
 * ExoPlayer/TiviMate cannot — returning raw PNG causes ParserException.
 */
object HlsImageSegmentUnwrapper {
    private val TSGZ = byteArrayOf(84, 73, 75, 84, 73, 75, 84, 83, 71, 90)
    private val TRAW = byteArrayOf(84, 73, 75, 84, 73, 75, 82, 65, 87)
    private val TPIX = byteArrayOf(84, 73, 75, 84, 73, 75, 80, 88)

    fun maybeUnwrap(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        if (looksLikeMpegTs(bytes)) return bytes
        return unwrap(bytes) ?: bytes
    }

    fun looksWrapped(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return true
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) {
            return true
        }
        return indexOf(bytes, TRAW) >= 0 || indexOf(bytes, TSGZ) >= 0
    }

    private fun unwrap(bytes: ByteArray): ByteArray? {
        webpExifTs(bytes)?.let { return it }
        pngIendTs(bytes)?.let { return it }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            unwrapPixels(bytes)?.let { return it }
        }
        val traw = indexOf(bytes, TRAW)
        if (traw >= 0) {
            val ts = bytes.copyOfRange(traw + TRAW.size, bytes.size)
            if (ts.isNotEmpty() && ts[0] == 0x47.toByte()) return ts
        }
        val tsgz = indexOf(bytes, TSGZ)
        if (tsgz >= 0) {
            runCatching { gunzip(bytes.copyOfRange(tsgz + TSGZ.size, bytes.size)) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it[0] == 0x47.toByte() }
                ?.let { return it }
        }
        var i = 0
        while (i + 188 < bytes.size) {
            if (bytes[i] == 0x47.toByte() && bytes[i + 188] == 0x47.toByte()) {
                return bytes.copyOfRange(i, bytes.size)
            }
            i++
        }
        return null
    }

    private fun looksLikeMpegTs(bytes: ByteArray): Boolean {
        if (bytes.size < 188) return false
        if (bytes[0] != 0x47.toByte()) return false
        var syncs = 0
        var offset = 0
        while (offset + 188 <= bytes.size && syncs < 3) {
            if (bytes[offset] == 0x47.toByte()) syncs++ else return false
            offset += 188
        }
        return syncs >= 2
    }

    private fun webpExifTs(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16) return null
        if (!bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) return null
        if (!bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return null
        var off = 12
        val dv = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (off + 8 <= bytes.size) {
            val tag = String(bytes, off, 4, Charsets.US_ASCII)
            val n = dv.getInt(off + 4)
            off += 8
            if (n < 0 || off + n > bytes.size) return null
            if (tag == "EXIF") {
                val data = bytes.copyOfRange(off, off + n)
                if (data.size >= 188 && data[0] == 0x47.toByte() && data[188] == 0x47.toByte()) {
                    return data
                }
                return null
            }
            off += n + (n and 1)
        }
        return null
    }

    private fun pngIendTs(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) return null
        var off = 8
        val dv = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        while (off + 8 <= bytes.size) {
            val len = dv.getInt(off)
            if (len < 0 || len > bytes.size - off - 12) return null
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            off += 8 + len + 4
            if (type == "IEND") {
                if (off < bytes.size &&
                    bytes[off] == 0x47.toByte() &&
                    off + 188 < bytes.size &&
                    bytes[off + 188] == 0x47.toByte()
                ) {
                    return bytes.copyOfRange(off, bytes.size)
                }
                return null
            }
        }
        return null
    }

    private fun unwrapPixels(bytes: ByteArray): ByteArray? {
        val rgb = pngRgb(bytes) ?: return null
        if (rgb.size < 12) return null
        for (k in TPIX.indices) {
            if (rgb[k] != TPIX[k]) return null
        }
        val n = ByteBuffer.wrap(rgb, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (n <= 0 || 12 + n > rgb.size) return null
        val gz = rgb.copyOfRange(12, 12 + n)
        if (gz.size < 2 || gz[0] != 0x1f.toByte() || gz[1] != 0x8b.toByte()) return null
        val ts = runCatching { gunzip(gz) }.getOrNull() ?: return null
        if (ts.isEmpty() || ts[0] != 0x47.toByte()) return null
        return ts
    }

    private fun pngRgb(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) return null
        var off = 8
        var width = 0
        var height = 0
        var depth = 0
        var colorType = 0
        var interlace = 0
        val idats = ArrayList<ByteArray>()
        val dv = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        while (off + 8 <= bytes.size) {
            val len = dv.getInt(off)
            if (len < 0 || len > bytes.size - off - 12) return null
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(off + 8, off + 8 + len)
            when (type) {
                "IHDR" -> {
                    val hd = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    width = hd.int
                    height = hd.int
                    depth = data[8].toInt() and 0xff
                    colorType = data[9].toInt() and 0xff
                    interlace = data[12].toInt() and 0xff
                }
                "IDAT" -> idats += data
                "IEND" -> {
                    off = bytes.size
                }
            }
            if (type == "IEND") break
            off += 12 + len
        }
        if (width <= 0 || height <= 0 || depth != 8 || interlace != 0) return null
        if (colorType != 2 && colorType != 6) return null
        val zlen = idats.sumOf { it.size }
        val zbuf = ByteArray(zlen)
        var zoff = 0
        for (part in idats) {
            System.arraycopy(part, 0, zbuf, zoff, part.size)
            zoff += part.size
        }
        val raw = inflateZlib(zbuf) ?: return null
        val bpp = if (colorType == 6) 4 else 3
        val stride = width * bpp
        val rgb = ByteArray(width * height * 3)
        var src = 0
        var dst = 0
        var prev = ByteArray(stride)
        for (y in 0 until height) {
            if (src + 1 + stride > raw.size) return null
            val filter = raw[src].toInt() and 0xff
            src++
            val row = raw.copyOfRange(src, src + stride)
            src += stride
            val recon = ByteArray(stride)
            for (i in 0 until stride) {
                val a = if (i >= bpp) recon[i - bpp].toInt() and 0xff else 0
                val b = prev[i].toInt() and 0xff
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                var v = row[i].toInt() and 0xff
                when (filter) {
                    1 -> v = (v + a) and 0xff
                    2 -> v = (v + b) and 0xff
                    3 -> v = (v + ((a + b) shr 1)) and 0xff
                    4 -> v = (v + paeth(a, b, c)) and 0xff
                    0 -> Unit
                    else -> return null
                }
                recon[i] = v.toByte()
            }
            if (colorType == 2) {
                System.arraycopy(recon, 0, rgb, dst, stride)
                dst += stride
            } else {
                var i = 0
                while (i < stride) {
                    rgb[dst++] = recon[i]
                    rgb[dst++] = recon[i + 1]
                    rgb[dst++] = recon[i + 2]
                    i += 4
                }
            }
            prev = recon
        }
        return rgb
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun inflateZlib(data: ByteArray): ByteArray? =
        runCatching {
            InflaterInputStream(ByteArrayInputStream(data), Inflater()).use { it.readBytes() }
        }.getOrNull()

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

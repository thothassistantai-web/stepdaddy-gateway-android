package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class HlsImageSegmentUnwrapperTest {
    @Test
    fun maybeUnwrap_passthroughMpegTs() {
        val ts = ByteArray(188 * 3) { i -> if (i % 188 == 0) 0x47 else 0x00 }
        val out = HlsImageSegmentUnwrapper.maybeUnwrap(ts)
        assertTrue(out === ts || out.contentEquals(ts))
        assertEquals(0x47.toByte(), out[0])
    }

    @Test
    fun maybeUnwrap_pixelsPayload() {
        // Build a tiny RGB PNG whose pixels encode TPIX + gzip(TS).
        val tsPacket = ByteArray(188) { i -> if (i == 0) 0x47 else (i % 250).toByte() }
        val gz = gzip(tsPacket)
        val payload = ByteArray(8 + 4 + gz.size)
        // TPIX magic
        payload[0] = 84; payload[1] = 73; payload[2] = 75; payload[3] = 84
        payload[4] = 73; payload[5] = 75; payload[6] = 80; payload[7] = 88
        ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(gz.size)
        System.arraycopy(gz, 0, payload, 12, gz.size)

        // Pad to complete pixels for a small image (width*height*3 >= payload)
        val width = 16
        val height = ((payload.size + width * 3 - 1) / (width * 3)).coerceAtLeast(1)
        val rgb = ByteArray(width * height * 3)
        System.arraycopy(payload, 0, rgb, 0, payload.size)
        val png = encodeRgbPng(width, height, rgb)

        assertTrue(HlsImageSegmentUnwrapper.looksWrapped(png))
        val out = HlsImageSegmentUnwrapper.maybeUnwrap(png)
        assertEquals(0x47.toByte(), out[0])
        assertEquals(188, out.size)
        assertEquals(tsPacket.contentToString(), out.contentToString())
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun encodeRgbPng(width: Int, height: Int, rgb: ByteArray): ByteArray {
        val bpp = 3
        val stride = width * bpp
        val raw = ByteArray((stride + 1) * height)
        var src = 0
        var dst = 0
        for (y in 0 until height) {
            raw[dst++] = 0 // filter None
            System.arraycopy(rgb, src, raw, dst, stride)
            src += stride
            dst += stride
        }
        val compressed = deflate(raw)
        val ihdr = ByteArray(13)
        ByteBuffer.wrap(ihdr).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", compressed)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(data.size + 64)
        val n = deflater.deflate(buf)
        deflater.end()
        return buf.copyOf(n)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        out.write(len)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }
}

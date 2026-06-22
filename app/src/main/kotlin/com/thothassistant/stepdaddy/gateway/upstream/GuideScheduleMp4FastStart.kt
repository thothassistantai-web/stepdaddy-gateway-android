package com.thothassistant.stepdaddy.gateway.upstream

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Moves the `moov` atom before `mdat` so ExoPlayer/TiviMate can start progressive MP4 immediately. */
object GuideScheduleMp4FastStart {
    fun apply(file: File) {
        if (!file.isFile || file.length() < 32L) return
        runCatching {
            val atoms = readTopLevelAtoms(file) ?: return
            val moov = atoms.firstOrNull { it.type == "moov" } ?: return
            val mdat = atoms.firstOrNull { it.type == "mdat" } ?: return
            if (moov.start < mdat.start) return

            val moovBytes = ByteArray(moov.size.toInt())
            RandomAccessFile(file, "r").use { input ->
                input.seek(moov.start)
                input.readFully(moovBytes)
            }

            val beforeMoov = moov.start
            val afterMoov = moov.start + moov.size
            val tailSize = file.length() - afterMoov
            val tail = ByteArray(tailSize.toInt())
            if (tail.isNotEmpty()) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(afterMoov)
                    input.readFully(tail)
                }
            }

            val tmp = File(file.parentFile, "${file.name}.faststart.part")
            RandomAccessFile(tmp, "rw").use { out ->
                out.write(readRange(file, 0, beforeMoov))
                out.write(moovBytes)
                if (tail.isNotEmpty()) out.write(tail)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private data class Atom(val type: String, val start: Long, val size: Long)

    private fun readTopLevelAtoms(file: File): List<Atom>? {
        val atoms = mutableListOf<Atom>()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            while (offset + 8 <= raf.length()) {
                raf.seek(offset)
                val header = ByteArray(8)
                if (raf.read(header) < 8) break
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                var size = buffer.int.toLong() and 0xFFFFFFFFL
                val type = String(header, 4, 4, Charsets.US_ASCII)
                var headerSize = 8L
                if (size == 1L) {
                    val ext = ByteArray(8)
                    if (raf.read(ext) < 8) break
                    size = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                    headerSize = 16L
                }
                if (size < headerSize) break
                atoms += Atom(type = type, start = offset, size = size)
                offset += size
            }
        }
        return atoms.ifEmpty { null }
    }

    private fun readRange(file: File, start: Long, end: Long): ByteArray {
        val size = (end - start).toInt()
        if (size <= 0) return ByteArray(0)
        val bytes = ByteArray(size)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            input.readFully(bytes)
        }
        return bytes
    }
}

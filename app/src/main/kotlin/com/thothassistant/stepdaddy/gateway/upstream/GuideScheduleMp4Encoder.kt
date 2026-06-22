package com.thothassistant.stepdaddy.gateway.upstream

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/** Encodes a static schedule bitmap as a short MP4 loop for IPTV players. */
object GuideScheduleMp4Encoder {
    private const val TAG = "GuideScheduleMp4Encoder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FRAME_RATE = 2
    private const val BITRATE = 1_500_000
    private const val DURATION_SEC = 30

    fun encode(bitmap: Bitmap, output: File): Boolean = runCatching {
        val width = bitmap.width and -2
        val height = bitmap.height and -2
        val frame = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        output.parentFile?.mkdirs()
        val tmp = File(output.parentFile, "${output.name}.part")
        if (tmp.exists()) tmp.delete()

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerState = MuxerState(muxer)
        val nv12 = bitmapToNv12(frame, width, height)
        if (frame !== bitmap) frame.recycle()

        val totalFrames = DURATION_SEC * FRAME_RATE
        for (frameIndex in 0 until totalFrames) {
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                encoder.getInputBuffer(inputIndex)?.let { buffer ->
                    buffer.clear()
                    buffer.put(nv12)
                    val pts = frameIndex * 1_000_000L / FRAME_RATE
                    encoder.queueInputBuffer(inputIndex, 0, nv12.size, pts, 0)
                }
            }
            drainEncoder(encoder, muxerState, endOfStream = false)
        }

        val eosIndex = encoder.dequeueInputBuffer(10_000)
        if (eosIndex >= 0) {
            encoder.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoder(encoder, muxerState, endOfStream = true)

        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()

        if (!tmp.renameTo(output)) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
        GuideScheduleMp4FastStart.apply(output)
        output.length() > 0L
    }.getOrElse { exc ->
        Log.w(TAG, "Guide MP4 encode failed", exc)
        false
    }

    private class MuxerState(private val muxer: MediaMuxer) {
        var trackIndex: Int = -1
        var started: Boolean = false

        fun addTrack(format: MediaFormat) {
            if (!started) {
                trackIndex = muxer.addTrack(format)
                muxer.start()
                started = true
            }
        }

        fun writeSampleData(buffer: java.nio.ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo) {
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxerState: MuxerState,
        endOfStream: Boolean,
    ) {
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerState.addTrack(encoder.outputFormat)
                }
                outputIndex >= 0 -> {
                    if (muxerState.started) {
                        val encoded = encoder.getOutputBuffer(outputIndex)
                        if (encoded != null && bufferInfo.size > 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxerState.writeSampleData(encoded, bufferInfo)
                        }
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    private fun bitmapToNv12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}

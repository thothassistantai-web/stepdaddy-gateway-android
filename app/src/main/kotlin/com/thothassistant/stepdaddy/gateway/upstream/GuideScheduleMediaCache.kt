package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.security.MessageDigest

class GuideScheduleMediaCache(context: Context) {
    private val dir = File(context.filesDir, "guide_media").also { it.mkdirs() }
    private val fallbackAsset = "guide/guide_fallback.mp4"

    fun getOrCreateMp4(
        context: Context,
        slug: String,
        contentKey: String,
        bitmapFactory: () -> Bitmap,
    ): File? {
        val safeKey = contentKey.take(16).ifEmpty { "0" }
        val cached = File(dir, "$slug-$safeKey.mp4")
        if (cached.isFile && cached.length() > 0L) {
            return cached
        }
        dir.listFiles()?.filter { it.name.startsWith("$slug-") && it.name.endsWith(".mp4") }
            ?.forEach { it.delete() }

        val bitmap = bitmapFactory()
        val encoded = GuideScheduleMp4Encoder.encode(bitmap, cached)
        bitmap.recycle()
        if (encoded && cached.isFile && cached.length() > 0L) {
            return cached
        }
        cached.delete()
        return copyFallback(context, slug)
    }

    private fun copyFallback(context: Context, slug: String): File? = runCatching {
        val out = File(dir, "$slug-fallback.mp4")
        if (out.isFile && out.length() > 0L) return out
        context.assets.open(fallbackAsset).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out.takeIf { it.length() > 0L }
    }.getOrElse { exc ->
        Log.w(TAG, "Guide fallback MP4 unavailable for $slug", exc)
        null
    }

    companion object {
        private const val TAG = "GuideScheduleMediaCache"

        /** Bump when guide slate layout/theme changes so cached MP4s regenerate. */
        private const val RENDER_REVISION = 4

        fun contentKey(events: List<SpecialEventsMerger.GuideEventRow>, syncedAtMs: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(RENDER_REVISION.toString().toByteArray())
            digest.update(syncedAtMs.toString().toByteArray())
            events.take(32).forEach { row ->
                digest.update(row.title.toByteArray())
                digest.update(row.startMs.toString().toByteArray())
            }
            return digest.digest().take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

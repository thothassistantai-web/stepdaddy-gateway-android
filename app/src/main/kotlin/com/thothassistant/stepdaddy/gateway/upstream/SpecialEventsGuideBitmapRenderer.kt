package com.thothassistant.stepdaddy.gateway.upstream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders a guide schedule card as a bitmap for IPTV video slates. */
object SpecialEventsGuideBitmapRenderer {
    private val UK = ZoneId.of("Europe/London")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private const val WIDTH = 1280
    private const val HEIGHT = 720

    fun render(
        category: String,
        emoji: String,
        events: List<SpecialEventsMerger.GuideEventRow>,
        nowMs: Long = System.currentTimeMillis(),
    ): Bitmap {
        val model = SpecialEventsGuideSchedule.buildViewModel(category, emoji, events, nowMs)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(15, 20, 25))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 238, 245)
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(139, 156, 179)
            textSize = 28f
        }
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 210, 225)
            textSize = 30f
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(91, 159, 212)
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 238, 245)
            textSize = 28f
        }
        val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(61, 214, 140)
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 135, 155)
            textSize = 24f
        }

        var y = 72f
        canvas.drawText("🎟️ Special Events", 64f, y, subtitlePaint)
        y += 56f
        canvas.drawText("$emoji ${model.category}", 64f, y, titlePaint)
        y += 44f
        canvas.drawText("Schedule times UK (GMT/BST)", 64f, y, subtitlePaint)
        y += 52f
        canvas.drawText(model.statusLine, 64f, y, statusPaint)
        y += 48f

        if (model.all.isEmpty()) {
            canvas.drawText("Check back after the next schedule refresh.", 64f, y + 20f, subtitlePaint)
        } else {
            val now = Instant.ofEpochMilli(nowMs)
            model.all.forEach { row ->
                val start = Instant.ofEpochMilli(row.startMs)
                val stop = Instant.ofEpochMilli(row.stopMs)
                val isLive = !now.isBefore(start) && now.isBefore(stop)
                val timeLabel = "${start.atZone(UK).format(TIME_FMT)} – ${stop.atZone(UK).format(TIME_FMT)}"
                if (isLive) {
                    canvas.drawText("● LIVE", 64f, y, livePaint)
                }
                canvas.drawText(timeLabel, if (isLive) 170f else 64f, y, timePaint)
                y += 34f
                val title = ellipsize(row.title, rowPaint, WIDTH - 128f)
                canvas.drawText(title, 64f, y, rowPaint)
                y += 42f
                if (y > HEIGHT - 80f) return@forEach
            }
        }

        canvas.drawText("Select a stream below this guide in the playlist to watch.", 64f, HEIGHT - 36f, footerPaint)
        return bitmap
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}

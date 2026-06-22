package com.thothassistant.stepdaddy.gateway.upstream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.Instant

/** Renders a guide schedule card as a bitmap for IPTV video slates. */
object SpecialEventsGuideBitmapRenderer {
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val PAD = 56f
    private const val FOOTER_H = 52f
    private const val COL_GAP = 40f

    fun render(
        category: String,
        emoji: String,
        events: List<SpecialEventsMerger.GuideEventRow>,
        nowMs: Long = System.currentTimeMillis(),
    ): Bitmap {
        val model = SpecialEventsGuideSchedule.buildViewModel(category, emoji, events, nowMs, maxRows = 24)
        val theme = SpecialEventsGuideTheme.forCategory(category, emoji)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, theme)
        drawHeaderPanel(canvas, theme, model)

        val contentTop = 248f
        val contentBottom = HEIGHT - FOOTER_H - 8f
        val footerPaint = footerPaint()
        canvas.drawRect(0f, HEIGHT - FOOTER_H, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            color = Color.argb(230, 6, 10, 18)
        })
        canvas.drawText(
            "Select a stream below this guide in the playlist to watch.",
            PAD,
            HEIGHT - 18f,
            footerPaint,
        )

        if (model.all.isEmpty()) {
            canvas.drawText(
                "Check back after the next schedule refresh.",
                PAD,
                contentTop + 24f,
                mutedPaint(),
            )
            return bitmap
        }

        val now = Instant.ofEpochMilli(nowMs)
        val rows = model.all.map { row -> row to isLive(row, now) }
        val maxRowsPerColumn = maxRowsThatFit(contentTop, contentBottom)
        val useTwoColumns = rows.size > maxRowsPerColumn
        val leftRows = if (useTwoColumns) {
            rows.take((rows.size + 1) / 2)
        } else {
            rows.take(maxRowsPerColumn)
        }
        val rightRows = if (useTwoColumns) {
            rows.drop(leftRows.size).take(maxRowsPerColumn)
        } else {
            emptyList()
        }
        val shown = leftRows.size + rightRows.size
        val hidden = rows.size - shown

        val colWidth = if (useTwoColumns) {
            (WIDTH - PAD * 2f - COL_GAP) / 2f
        } else {
            WIDTH - PAD * 2f
        }
        drawColumn(canvas, leftRows, PAD, contentTop, contentBottom, colWidth)
        if (useTwoColumns) {
            val rightX = PAD + colWidth + COL_GAP
            drawColumn(canvas, rightRows, rightX, contentTop, contentBottom, colWidth)
            canvas.drawLine(
                PAD + colWidth + COL_GAP / 2f,
                contentTop,
                PAD + colWidth + COL_GAP / 2f,
                contentBottom,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(50, 255, 255, 255)
                    strokeWidth = 1f
                },
            )
        }
        if (hidden > 0) {
            canvas.drawText(
                "+ $hidden more event${if (hidden == 1) "" else "s"} in the playlist",
                PAD,
                contentBottom - 6f,
                mutedPaint().apply { textSize = 22f },
            )
        }
        return bitmap
    }

    private fun drawBackground(canvas: Canvas, theme: SpecialEventsGuideTheme) {
        val bg = Paint()
        bg.shader = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            theme.gradientTop,
            theme.gradientBottom,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            WIDTH * 0.78f,
            HEIGHT * 0.32f,
            WIDTH * 0.55f,
            Color.argb(
                90,
                SpecialEventsGuideTheme.channelRed(theme.accent),
                SpecialEventsGuideTheme.channelGreen(theme.accent),
                SpecialEventsGuideTheme.channelBlue(theme.accent),
            ),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), glow)

        val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 320f
            alpha = 34
            color = Color.WHITE
        }
        canvas.drawText(theme.watermarkEmoji, WIDTH - 360f, HEIGHT - 120f, watermark)

        canvas.drawRect(0f, 0f, 10f, HEIGHT.toFloat(), Paint().apply { color = theme.accent })
    }

    private fun drawHeaderPanel(
        canvas: Canvas,
        theme: SpecialEventsGuideTheme,
        model: SpecialEventsGuideSchedule.ViewModel,
    ) {
        val panel = RectF(PAD - 12f, 36f, WIDTH - PAD + 12f, 228f)
        canvas.drawRoundRect(panel, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.panel
        })

        var y = 78f
        canvas.drawText("🎟️ Special Events", PAD, y, mutedPaint())
        y += 54f
        canvas.drawText("${model.emoji} ${model.category}", PAD, y, titlePaint())
        y += 40f
        canvas.drawText(GuideScheduleDisplayZone.label, PAD, y, mutedPaint())
        y += 44f
        canvas.drawText(model.statusLine, PAD, y, statusPaint())
    }

    private fun drawColumn(
        canvas: Canvas,
        rows: List<Pair<SpecialEventsMerger.GuideEventRow, Boolean>>,
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
    ) {
        if (rows.isEmpty()) return
        val rowHeight = rowHeightFor(rows.size, top, bottom)
        var y = top
        rows.forEach { (row, isLive) ->
            if (y + rowHeight > bottom) return@forEach
            drawEventRow(canvas, row, isLive, x, y, width, rowHeight)
            y += rowHeight
        }
    }

    private fun drawEventRow(
        canvas: Canvas,
        row: SpecialEventsMerger.GuideEventRow,
        isLive: Boolean,
        x: Float,
        y: Float,
        width: Float,
        rowHeight: Float,
    ) {
        val start = Instant.ofEpochMilli(row.startMs)
        val stop = Instant.ofEpochMilli(row.stopMs)
        val timeLabel = GuideScheduleDisplayZone.formatWindow(start, stop)
        val compact = rowHeight < 58f
        val timeSize = if (compact) 22f else 24f
        val titleSize = if (compact) 23f else 26f

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 190, 245)
            textSize = timeSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(61, 214, 140)
            textSize = if (compact) 20f else 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 238, 245)
            textSize = titleSize
        }

        var timeX = x
        if (isLive) {
            canvas.drawText("● LIVE", x, y + timeSize, livePaint)
            timeX = x + 96f
        }
        canvas.drawText(timeLabel, timeX, y + timeSize, timePaint)
        val titleY = y + timeSize + (if (compact) 24f else 30f)
        canvas.drawText(ellipsize(row.title, rowPaint, width), x, titleY, rowPaint)
    }

    private fun maxRowsThatFit(top: Float, bottom: Float): Int {
        val available = bottom - top
        return ((available / 58f).toInt()).coerceIn(4, 8)
    }

    private fun rowHeightFor(rowCount: Int, top: Float, bottom: Float): Float {
        val available = bottom - top - 28f
        val perRow = available / rowCount.coerceAtLeast(1)
        return perRow.coerceIn(48f, 72f)
    }

    private fun isLive(row: SpecialEventsMerger.GuideEventRow, now: Instant): Boolean {
        val start = Instant.ofEpochMilli(row.startMs)
        val stop = Instant.ofEpochMilli(row.stopMs)
        return !now.isBefore(start) && now.isBefore(stop)
    }

    private fun titlePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 248, 252)
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun mutedPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 168, 194)
        textSize = 26f
    }

    private fun statusPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 220, 235)
        textSize = 28f
    }

    private fun footerPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 145, 168)
        textSize = 22f
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

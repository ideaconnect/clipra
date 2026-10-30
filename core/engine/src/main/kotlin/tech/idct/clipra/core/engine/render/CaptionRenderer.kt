package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.CaptionStyles
import java.util.Locale
import kotlin.math.min

/**
 * Draws caption clips (#45) into frame-sized bitmaps, as design/canvas/CaptionsEdit.dc.html
 * styles them. Words wrap within the title-safe width, shrinking to two lines when the caption
 * asks for it. The spoken word ([current]) is highlighted by style: a yellow word (Clean), a
 * yellow box (Bold pop), a yellow underline (Boxed), or yellow with the words still to come
 * dimmed (Karaoke). Minimal has no highlight.
 */
object CaptionRenderer {
    /** Captions sit within this share of the frame from the top and bottom edges. */
    private const val EDGE = 0.12f
    private const val LINE_HEIGHT = 1.25f
    private const val MIN_SHRINK = 0.4f
    private const val SHRINK_STEP = 0.9f

    fun render(
        context: Context,
        caption: CaptionSpec,
        current: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (caption.words.isEmpty()) return bitmap
        val canvas = Canvas(bitmap)
        val look = CaptionStyles.look(caption.style)
        val texts = caption.words.map {
            if (look.uppercase) it.text.uppercase(Locale.getDefault()) else it.text
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = TextRenderer.typeface(context, look.font)
            fontVariationSettings = TextRenderer.variation(look.font, look.weight)
        }
        val wrap = width * TextRenderer.SAFE_WIDTH
        val base = look.size * min(width, height) * caption.scale
        var size = base
        var lines = layout(texts, paint.apply { textSize = size }, wrap)
        while (caption.maxTwoLines && lines.size > 2 && size > base * MIN_SHRINK) {
            size *= SHRINK_STEP
            lines = layout(texts, paint.apply { textSize = size }, wrap)
        }
        val lineHeight = size * LINE_HEIGHT
        val block = lines.size * lineHeight
        val top = when (caption.position) {
            CaptionPosition.Top -> height * EDGE
            CaptionPosition.Middle -> (height - block) / 2
            CaptionPosition.Bottom -> height * (1 - EDGE) - block
        }
        val space = paint.measureText(" ")
        val metrics = paint.fontMetrics
        lines.forEachIndexed { row, line ->
            val lineWidth = line.sumOf { paint.measureText(texts[it]).toDouble() }.toFloat() +
                space * (line.size - 1)
            var x = (width - lineWidth) / 2
            val lineTop = top + row * lineHeight
            val baseline = lineTop + (lineHeight - (metrics.descent - metrics.ascent)) / 2 -
                metrics.ascent
            if (look.box) {
                val padX = size * 0.5f
                val padY = size * 0.15f
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CaptionStyles.BOX.toInt() }
                val rect = RectF(
                    x - padX,
                    lineTop - padY,
                    x + lineWidth + padX,
                    lineTop + lineHeight + padY,
                )
                canvas.drawRoundRect(
                    rect,
                    size * 0.15f,
                    size * 0.15f,
                    box,
                )
            }
            for (index in line) {
                val text = texts[index]
                val wordWidth = paint.measureText(text)
                val place = Place(x, baseline, wordWidth, size)
                drawWord(canvas, paint, look, caption, index, current, text, place)
                x += wordWidth + space
            }
        }
        return bitmap
    }

    private fun drawWord(
        canvas: Canvas,
        paint: TextPaint,
        look: CaptionStyles.Look,
        caption: CaptionSpec,
        index: Int,
        current: Int,
        text: String,
        place: Place,
    ) {
        val (x, baseline, wordWidth, size) = place
        val spoken = caption.highlight && index == current
        var color = CaptionStyles.WHITE
        var alpha = 1f
        var shadow = look.shadowAlpha > 0
        when (caption.style) {
            CaptionStyle.Clean -> if (spoken) color = CaptionStyles.ACCENT

            CaptionStyle.BoldPop -> if (spoken) {
                val padX = size * 0.12f
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = CaptionStyles.ACCENT.toInt()
                }
                val metrics = paint.fontMetrics
                val rect = RectF(
                    x - padX,
                    baseline + metrics.ascent * 0.85f,
                    x + wordWidth + padX,
                    baseline + metrics.descent * 0.8f,
                )
                canvas.drawRoundRect(
                    rect,
                    size * 0.1f,
                    size * 0.1f,
                    box,
                )
                color = CaptionStyles.ON_ACCENT
                shadow = false
            }

            CaptionStyle.Boxed -> if (spoken) {
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = CaptionStyles.ACCENT.toInt()
                }
                val y = baseline + size * 0.12f
                canvas.drawRect(x, y, x + wordWidth, y + size * 0.12f, line)
            }

            CaptionStyle.Karaoke -> if (caption.highlight) {
                if (index == current) color = CaptionStyles.ACCENT
                if (current >= 0 && index > current) alpha = CaptionStyles.UPCOMING_ALPHA
            }

            CaptionStyle.Minimal -> Unit
        }
        paint.color = color.toInt()
        paint.alpha = (alpha * 255).toInt()
        if (shadow) {
            val shadowColor = ((look.shadowAlpha * alpha * 255).toInt() shl 24)
            paint.setShadowLayer(look.shadowBlur * size, 0f, look.shadowY * size, shadowColor)
        } else {
            paint.clearShadowLayer()
        }
        canvas.drawText(text, x, baseline, paint)
    }

    /** Where a word goes: its left edge, baseline, width and the font size, in pixels. */
    private data class Place(val x: Float, val baseline: Float, val width: Float, val size: Float)

    /** Word indices per line, wrapped within [wrap] pixels. */
    private fun layout(texts: List<String>, paint: TextPaint, wrap: Float): List<List<Int>> {
        val space = paint.measureText(" ")
        val lines = mutableListOf(mutableListOf<Int>())
        var used = 0f
        texts.forEachIndexed { index, text ->
            val w = paint.measureText(text)
            val line = lines.last()
            if (line.isNotEmpty() && used + space + w > wrap) {
                lines += mutableListOf(index)
                used = w
            } else {
                used += if (line.isEmpty()) w else space + w
                line += index
            }
        }
        return lines
    }
}

package tech.idct.clipra.core.engine.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.StaticLayout
import android.text.TextPaint
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextSpec
import kotlin.math.max

/**
 * Draws a laid-out text in its effect (#32), as the design's CSS draws it (Text board): text
 * shadows, the last listed first, as copies of the letters moved and blurred; strokes over the
 * letters; boxes and marker bands behind each line; a gradient across the text. Sizes are in
 * ems of the text (the design's pixels at its 30 px preview), so an effect scales with it.
 */
internal class TextEffectPainter(
    private val layout: StaticLayout,
    private val paint: TextPaint,
    private val spec: TextSpec,
    private val card: Boolean,
) {
    private val em = paint.textSize
    private val text = spec.color.toInt()

    fun draw(canvas: Canvas) {
        when (spec.effect) {
            "outline" -> {
                // The letters over a stroke twice as wide: the same line outside them, and no
                // lines inside where the font's outlines overlap.
                stroke(canvas, ORANGE, 3f)
                fill(canvas, text)
            }

            "shadow" -> {
                shadow(canvas, 0f, 8f, 18f, 0x8C000000.toInt())
                shadow(canvas, 0f, 3f, 0f, 0x59000000)
                fill(canvas, text)
            }

            "glow" -> {
                shadow(canvas, 0f, 0f, 16f, 0xB3FFD23F.toInt())
                shadow(canvas, 0f, 0f, 6f, 0xF2FFD23F.toInt())
                fill(canvas, WHITE)
            }

            "neon" -> {
                shadow(canvas, 0f, 0f, 22f, 0xFFB000FF.toInt())
                shadow(canvas, 0f, 0f, 10f, PINK)
                shadow(canvas, 0f, 0f, 3f, PINK)
                fill(canvas, 0xFFFFE9F7.toInt())
            }

            "box" -> {
                // White on a yellow card, where yellow would vanish.
                val box = if (card && spec.background == TextColors.YELLOW) WHITE else YELLOW
                lines(canvas, box, padding = 0.18f * DESIGN_EM, radius = 4f, fromTop = 0f)
                fill(canvas, INK)
            }

            "gradient" -> gradient(canvas)

            "extrude" -> {
                shadow(canvas, 5f, 6f, 8f, 0x80000000.toInt())
                shadow(canvas, 4f, 4f, 0f, 0xFF846300.toInt())
                shadow(canvas, 3f, 3f, 0f, 0xFF9C7600.toInt())
                shadow(canvas, 2f, 2f, 0f, 0xFFB38800.toInt())
                shadow(canvas, 1f, 1f, 0f, 0xFFC99A00.toInt())
                fill(canvas, YELLOW)
            }

            "glitch" -> {
                shadow(canvas, 2f, 0f, 0f, 0xFFFF2D55.toInt())
                shadow(canvas, -2f, 0f, 0f, 0xFF00E5FF.toInt())
                fill(canvas, text)
            }

            "hollow" -> {
                // A stroke twice as wide with the letters cut out of it, on a layer of its own
                // so the card shows through.
                val layer = canvas.saveLayer(null, null)
                stroke(canvas, text, 3f)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                fill(canvas, text)
                paint.xfermode = null
                canvas.restoreToCount(layer)
            }

            "retro" -> {
                shadow(canvas, 6f, 6f, 0f, 0xFF3D5AFE.toInt())
                shadow(canvas, 3f, 3f, 0f, ORANGE)
                fill(canvas, YELLOW)
            }

            "marker" -> {
                lines(
                    canvas,
                    0xD9FF5A36.toInt(),
                    padding = 0.1f * DESIGN_EM,
                    radius = 0f,
                    fromTop = 0.58f,
                )
                fill(canvas, text)
            }

            else -> fill(canvas, text)
        }
    }

    /** [px] of the design's 30 px text, in pixels of this text. */
    private fun px(px: Float): Float = px / DESIGN_EM * em

    private fun fill(canvas: Canvas, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        layout.draw(canvas)
    }

    private fun stroke(canvas: Canvas, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(width)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
    }

    /** A CSS text shadow: the letters in [color], moved and blurred by [blur] (its radius). */
    private fun shadow(canvas: Canvas, dx: Float, dy: Float, blur: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        if (blur > 0f) {
            // CSS blurs with a sigma of half the radius; Skia takes sigma = 0.577 r + 0.5.
            val sigma = px(blur) / 2
            paint.maskFilter = BlurMaskFilter(
                max(0.5f, (sigma - 0.5f) / 0.57735f),
                BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.save()
        canvas.translate(px(dx), px(dy))
        layout.draw(canvas)
        canvas.restore()
        paint.maskFilter = null
    }

    /** Behind each line: a box from [fromTop] of its height down, [padding] wider each side. */
    private fun lines(canvas: Canvas, color: Int, padding: Float, radius: Float, fromTop: Float) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val pad = px(padding)
        val corner = px(radius)
        for (line in 0 until layout.lineCount) {
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            val rect = RectF(
                layout.getLineLeft(line) - pad,
                top + (bottom - top) * fromTop,
                layout.getLineRight(line) + pad,
                bottom,
            )
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
        }
    }

    /** The letters filled with the design's yellow-to-orange, across the text. */
    private fun gradient(canvas: Canvas) {
        val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
        val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        paint.style = Paint.Style.FILL
        paint.color = WHITE
        paint.shader = LinearGradient(left, 0f, right, 0f, YELLOW, ORANGE, Shader.TileMode.CLAMP)
        layout.draw(canvas)
        paint.shader = null
    }

    private companion object {
        /** The design draws its text effects on 30 px text. */
        const val DESIGN_EM = 30f

        const val WHITE = 0xFFFFFFFF.toInt()
        const val YELLOW = 0xFFFFD23F.toInt()
        const val ORANGE = 0xFFFF5A36.toInt()
        const val PINK = 0xFFFF4FD8.toInt()
        const val INK = 0xFF141210.toInt()
    }
}

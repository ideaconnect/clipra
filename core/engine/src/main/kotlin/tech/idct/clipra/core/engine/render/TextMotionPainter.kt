package tech.idct.clipra.core.engine.render

import android.graphics.Canvas
import android.text.StaticLayout
import tech.idct.clipra.core.model.Animations
import kotlin.math.min

/**
 * Text in and out (#33): the letters of a laid-out text at a moment of their animation, drawn
 * through [draw] (the text in its effect, #32). Whole-text animations move, scale and fade the
 * text; Typewriter shows it letter by letter; Wave lifts each letter in turn; Glitch jitters it
 * in bands. Distances are in fractions of the frame or ems, so they look the same at any size.
 */
internal class TextMotionPainter(
    private val layout: StaticLayout,
    private val em: Float,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val draw: (Canvas) -> Unit,
) {
    fun draw(canvas: Canvas, motion: Animations.TextMotion) {
        val p = motion.progress
        if (!motion.exit) {
            when (motion.id) {
                "typewriter" -> return typewriter(canvas, p)
                "wave" -> return wave(canvas, p)
                "glitch" -> return glitch(canvas, p)
            }
        }
        var alpha = 1f
        var dx = 0f
        var dy = 0f
        var scale = 1f
        if (!motion.exit) {
            val e = Animations.easeOut(p)
            when (motion.id) {
                "fade_up" -> {
                    alpha = e
                    dy = RISE * frameHeight * (1 - e)
                }

                "pop" -> {
                    scale = Animations.backOut(p).coerceAtLeast(0f)
                    alpha = min(1f, p * 3)
                }

                "slide" -> {
                    dx = -SLIDE * frameWidth * (1 - e)
                    alpha = min(1f, p * 2)
                }

                "bounce" -> dy = -DROP * frameHeight * (1 - Animations.bounceOut(p))
            }
        } else {
            val e = Animations.easeIn(p)
            when (motion.id) {
                "fade" -> alpha = 1 - p

                "pop" -> {
                    scale = 1 - e
                    alpha = 1 - e
                }

                "slide" -> {
                    dx = SLIDE * frameWidth * e
                    alpha = 1 - e
                }
            }
        }
        if (alpha <= 0f || scale <= 0f) return
        val layer = canvas.saveLayerAlpha(null, (alpha * 255).toInt())
        canvas.translate(dx, dy)
        canvas.scale(scale, scale, layout.width / 2f, layout.height / 2f)
        draw(canvas)
        canvas.restoreToCount(layer)
    }

    /** The first letters, more each frame; a line shows up to the last letter typed. */
    private fun typewriter(canvas: Canvas, p: Float) {
        val shown = (p * layout.text.length).toInt()
        if (shown <= 0) return
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            if (shown <= start) break
            val end = layout.getLineEnd(line)
            val right = if (shown >= end) {
                layout.getLineRight(line) + em
            } else {
                layout.getPrimaryHorizontal(shown)
            }
            canvas.save()
            canvas.clipRect(
                layout.getLineLeft(line) - em,
                layout.getLineTop(line) - em,
                right,
                layout.getLineBottom(line) + em,
            )
            draw(canvas)
            canvas.restore()
        }
    }

    /** Each letter rises and fades in, a little after the one before. */
    private fun wave(canvas: Canvas, p: Float) {
        val count = layout.text.length.coerceAtLeast(1)
        for (line in 0 until layout.lineCount) {
            val top = layout.getLineTop(line) - em
            val bottom = layout.getLineBottom(line) + em
            for (i in layout.getLineStart(line) until layout.getLineEnd(line)) {
                if (layout.text[i].isWhitespace()) continue
                val own = ((p * (1 + SPREAD) - SPREAD * i / count)).coerceIn(0f, 1f)
                if (own <= 0f) continue
                val left = layout.getPrimaryHorizontal(i)
                val right = if (i + 1 < layout.getLineEnd(line)) {
                    layout.getPrimaryHorizontal(i + 1)
                } else {
                    layout.getLineRight(line)
                }
                val layer = canvas.saveLayerAlpha(null, (own * 255).toInt())
                canvas.translate(0f, WAVE * em * (1 - Animations.easeOut(own)))
                canvas.clipRect(minOf(left, right), top, maxOf(left, right), bottom)
                draw(canvas)
                canvas.restoreToCount(layer)
            }
        }
    }

    /** Bands of the text jump sideways and flicker, settling by the end. */
    private fun glitch(canvas: Canvas, p: Float) {
        if (p >= SETTLED) {
            draw(canvas)
            return
        }
        val step = (p * STEPS).toInt()
        val band = layout.height / BANDS.toFloat()
        for (b in 0 until BANDS) {
            val noise = hash(step * BANDS + b)
            if (noise < FLICKER) continue
            canvas.save()
            canvas.translate((hash(step * 31 + b * 7) - 0.5f) * JITTER * em * (1 - p), 0f)
            canvas.clipRect(-frameWidth.toFloat(), b * band, frameWidth * 2f, (b + 1) * band)
            draw(canvas)
            canvas.restore()
        }
    }

    /** A steady pseudo-random number in 0 to 1 for [n], so a frame always glitches the same. */
    private fun hash(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    private companion object {
        /** Fractions of the frame: how far text rises, slides and drops. */
        const val RISE = 0.08f
        const val SLIDE = 0.6f
        const val DROP = 0.35f

        /** Wave: how far behind the last letter starts, and how far a letter rises, in ems. */
        const val SPREAD = 1.2f
        const val WAVE = 0.6f

        /** Glitch: bands, steps a second of its run, how many flicker, and how far they jump. */
        const val BANDS = 4
        const val STEPS = 12
        const val FLICKER = 0.2f
        const val JITTER = 0.5f
        const val SETTLED = 0.85f
    }
}

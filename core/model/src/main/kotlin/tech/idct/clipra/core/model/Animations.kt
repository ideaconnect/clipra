package tech.idct.clipra.core.model

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Animations (#33): text in and out (spec section 10), clips in, out and in a loop (section 5),
 * as a [Clip]'s [Animation]. Each is a function of time, so preview and export, which draw every
 * frame from the same values, show it identically.
 *
 * Clip animations move, scale, turn and fade the whole clip ([motion]). Text animations move the
 * letters but not a text card's colour, so the text renderer draws them ([textMotion]).
 */
object Animations {
    /** Text in, after None (spec section 10). */
    val textIn = listOf("typewriter", "fade_up", "pop", "slide", "bounce", "wave", "glitch")

    /** Text out, after None. */
    val textOut = listOf("fade", "pop", "slide")

    /** Clip in, out and loop, after None (spec section 5). */
    val clipIn = listOf("fade", "zoom_in", "slide_up", "spin")
    val clipOut = listOf("fade", "zoom_out", "slide_down")
    val clipLoop = listOf("ken_burns", "shake", "pulse")

    /** How long an animation in or out lasts: 0.6 s, or half a shorter clip. */
    fun lengthUs(clipDurationUs: Long): Long = min(LENGTH_US, clipDurationUs / 2).coerceAtLeast(1)

    /** A clip's place and opacity at a moment, its animations applied. */
    data class Motion(val transform: Transform, val opacity: Float)

    /**
     * The clip's [base] transform and full opacity, [offsetUs] into a clip [durationUs] long,
     * with its animation in, out and loop applied.
     */
    fun motion(animation: Animation?, base: Transform, offsetUs: Long, durationUs: Long): Motion {
        if (animation == null) return Motion(base, 1f)
        var t = base
        var opacity = 1f
        val length = lengthUs(durationUs)
        val enter = animation.enter
        if (enter != null && offsetUs < length) {
            val p = (offsetUs.toFloat() / length).coerceIn(0f, 1f)
            val e = easeOut(p)
            when (enter) {
                "fade" -> opacity *= p

                "zoom_in" -> {
                    t = t.copy(scale = t.scale * lerp(ZOOM_FROM, 1f, e))
                    opacity *= e
                }

                "slide_up" -> t = t.copy(y = t.y + SLIDE * (1 - e))

                "spin" -> {
                    t = t.copy(
                        rotation = t.rotation - SPIN * (1 - e),
                        scale = t.scale * lerp(0.5f, 1f, e),
                    )
                    opacity *= e
                }
            }
        }
        val exit = animation.exit
        val exitFrom = durationUs - length
        if (exit != null && offsetUs > exitFrom) {
            val q = ((offsetUs - exitFrom).toFloat() / length).coerceIn(0f, 1f)
            val e = easeIn(q)
            when (exit) {
                "fade" -> opacity *= 1 - q

                "zoom_out" -> {
                    t = t.copy(scale = t.scale * lerp(1f, ZOOM_FROM, e))
                    opacity *= 1 - e
                }

                "slide_down" -> t = t.copy(y = t.y + SLIDE * e)
            }
        }
        val seconds = offsetUs / 1_000_000f
        when (animation.loop) {
            // A slow push in and pan over the whole clip.
            "ken_burns" -> {
                val k = (offsetUs.toFloat() / durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
                t = t.copy(scale = t.scale * (1 + KEN_BURNS_ZOOM * k), x = t.x + KEN_BURNS_PAN * k)
            }

            "shake" -> t = t.copy(
                x = t.x + SHAKE * sin(2 * PI * 7 * seconds).toFloat(),
                y = t.y + SHAKE * 0.8f * sin(2 * PI * 9 * seconds + 1).toFloat(),
                rotation = t.rotation + SHAKE_TURN * sin(2 * PI * 5 * seconds).toFloat(),
            )

            "pulse" -> {
                val beat = sin(2 * PI * PULSE_HZ * seconds).toFloat()
                t = t.copy(scale = t.scale * (1 + PULSE * beat))
            }
        }
        return Motion(t, opacity.coerceIn(0f, 1f))
    }

    /** What a text clip's letters are doing at a moment, or null when they are still. */
    data class TextMotion(val id: String, val progress: Float, val exit: Boolean)

    /** The text animation [offsetUs] into a clip [durationUs] long, if one is playing. */
    fun textMotion(animation: Animation?, offsetUs: Long, durationUs: Long): TextMotion? {
        if (animation == null) return null
        val length = lengthUs(durationUs)
        animation.enter?.takeIf { offsetUs < length }?.let {
            return TextMotion(it, (offsetUs.toFloat() / length).coerceIn(0f, 1f), exit = false)
        }
        val exitFrom = durationUs - length
        animation.exit?.takeIf { offsetUs >= exitFrom }?.let {
            val progress = ((offsetUs - exitFrom).toFloat() / length).coerceIn(0f, 1f)
            return TextMotion(it, progress, exit = true)
        }
        return null
    }

    fun easeOut(p: Float): Float = 1 - (1 - p).pow(3)

    fun easeIn(p: Float): Float = p.pow(3)

    /** Past the target and back, as a pop. */
    fun backOut(p: Float): Float {
        val c = 1.70158f
        val x = p - 1
        return 1 + (c + 1) * x * x * x + c * x * x
    }

    /** Falling and bouncing to rest. */
    fun bounceOut(p: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            p < 1 / d -> n * p * p
            p < 2 / d -> (p - 1.5f / d).let { n * it * it + 0.75f }
            p < 2.5f / d -> (p - 2.25f / d).let { n * it * it + 0.9375f }
            else -> (p - 2.625f / d).let { n * it * it + 0.984375f }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    const val LENGTH_US = 600_000L
    private const val ZOOM_FROM = 0.6f
    private const val SLIDE = 0.6f
    private const val SPIN = 180f
    private const val KEN_BURNS_ZOOM = 0.12f
    private const val KEN_BURNS_PAN = 0.03f
    private const val SHAKE = 0.012f
    private const val SHAKE_TURN = 0.8f
    private const val PULSE = 0.04f
    private const val PULSE_HZ = 1.2f
}

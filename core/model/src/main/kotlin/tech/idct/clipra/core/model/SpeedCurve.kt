package tech.idct.clipra.core.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Speed (#22): how the timeline time of a clip maps to the time of its media. A constant speed
 * plays [Speed.rate] seconds of media per second. A ramp changes the speed along the media,
 * through the speeds of its preset (or the clip's custom ones), evenly spaced over it and joined
 * by straight lines. It plays in [Speed.rampSteps] even steps, each at one speed, so picture and
 * sound agree and each step is long enough for the sound to keep its pitch.
 *
 * A clip plays its window of the ramp ([Speed.rampFrom] to [Speed.rampTo]), and the steps sit
 * at fixed places in the whole ramp, so the parts of a split or trimmed clip play every moment
 * at the same speed as before. Reversed clips play their media from the end.
 */
object SpeedCurve {
    /** The constant speeds offered (spec section 5). */
    val constants = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 4.0)

    /** The ramp presets and their speeds, start to end (spec section 5). */
    val ramps: Map<String, List<Float>> = linkedMapOf(
        "montage" to listOf(1f, 2f, 0.5f, 2f, 1f),
        "hero" to listOf(1f, 1f, 0.25f, 1f, 1f),
        "bullet" to listOf(1f, 0.1f, 0.1f, 0.1f, 1f),
        "flash_in" to listOf(4f, 2f, 1f, 1f, 1f),
        "flash_out" to listOf(1f, 1f, 1f, 2f, 4f),
    )

    /** A Custom ramp starts slow in the middle; its three speeds are the user's to change. */
    const val CUSTOM = "custom"
    val customDefault = listOf(1f, 0.5f, 1f)
    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 4f

    /**
     * How many steps a ramp over [sourceSpanUs] of media plays in: about a tenth of a second of
     * media each, longer than the stretch the pitch keeping needs to find the sound's pitch.
     */
    fun stepsFor(sourceSpanUs: Long): Int =
        (sourceSpanUs / STEP_US).toInt().coerceIn(MIN_STEPS, MAX_STEPS)

    /** The ramp's speeds, or null for a constant speed. */
    fun points(speed: Speed): List<Float>? = when (speed.ramp) {
        null -> null
        CUSTOM -> speed.custom ?: customDefault
        else -> ramps[speed.ramp]
    }

    /** The speed at [u], a fraction of the whole ramp (0 at its start, 1 at its end). */
    fun speedAt(speed: Speed, u: Float): Double {
        val points = points(speed) ?: return speed.rate
        if (points.size == 1) return points[0].toDouble()
        val position = u.coerceIn(0f, 1f) * (points.size - 1)
        val i = position.toInt().coerceAtMost(points.size - 2)
        val t = position - i
        return (points[i] + (points[i + 1] - points[i]) * t).toDouble()
    }

    /** How long [sourceSpanUs] of media plays on the timeline. */
    fun timelineDuration(speed: Speed, sourceSpanUs: Long): Long = if (speed.ramp == null) {
        if (speed.rate <= 0) 0 else (sourceSpanUs / speed.rate).roundToLong()
    } else {
        steps(speed, sourceSpanUs).sumOf { it.timelineUs }.roundToLong()
    }

    /**
     * The media offset (from the clip's in point, in media time) that plays [timelineUs] into
     * a clip playing [sourceSpanUs] of media; for a reversed clip, counted from its out point.
     */
    fun sourceOffset(speed: Speed, sourceSpanUs: Long, timelineUs: Long): Long {
        val forward = forwardOffset(speed, sourceSpanUs, timelineUs)
        return if (speed.reverse) sourceSpanUs - forward else forward
    }

    /** The media offset [timelineUs] into the clip plays, in playing order (reverse ignored). */
    fun forwardOffset(speed: Speed, sourceSpanUs: Long, timelineUs: Long): Long {
        if (speed.ramp == null) return (timelineUs * speed.rate).roundToLong()
        val steps = steps(speed, sourceSpanUs)
        if (steps.isEmpty()) return 0
        // Before the start or past the end (a transition's tail): on at the nearest speed.
        if (timelineUs <= 0) return (timelineUs * steps.first().speed).roundToLong()
        var at = 0.0
        for (step in steps) {
            if (timelineUs < at + step.timelineUs) {
                return (step.fromUs + (timelineUs - at) * step.speed).roundToLong()
            }
            at += step.timelineUs
        }
        return (sourceSpanUs + (timelineUs - at) * steps.last().speed).roundToLong()
    }

    /** The timeline offset into the clip at which [sourceUs] of its media (forward) plays. */
    fun timelineOffset(speed: Speed, sourceSpanUs: Long, sourceUs: Long): Long {
        if (speed.ramp == null) {
            return if (speed.rate <= 0) 0 else (sourceUs / speed.rate).roundToLong()
        }
        val steps = steps(speed, sourceSpanUs)
        if (steps.isEmpty()) return 0
        if (sourceUs <= 0) return (sourceUs / steps.first().speed).roundToLong()
        var at = 0.0
        for (step in steps) {
            if (sourceUs <
                step.toUs
            ) {
                return (at + (sourceUs - step.fromUs) / step.speed).roundToLong()
            }
            at += step.timelineUs
        }
        return (at + (sourceUs - sourceSpanUs) / steps.last().speed).roundToLong()
    }

    /** The part of the ramp from [fromSourceUs] to [toSourceUs] of a clip's media. */
    fun window(speed: Speed, sourceSpanUs: Long, fromSourceUs: Long, toSourceUs: Long): Speed {
        if (speed.ramp == null || sourceSpanUs <= 0) return speed
        val width = speed.rampTo - speed.rampFrom
        val from = speed.rampFrom + width * fromSourceUs / sourceSpanUs
        val to = speed.rampFrom + width * toSourceUs / sourceSpanUs
        return speed.copy(rampFrom = from, rampTo = to)
    }

    /** The speed [sourceUs] of the clip's media (forward, from its in point) plays at. */
    fun mediaSpeedAt(speed: Speed, sourceSpanUs: Long, sourceUs: Long): Double {
        if (speed.ramp == null) return speed.rate
        val steps = steps(speed, sourceSpanUs)
        return (steps.firstOrNull { sourceUs < it.toUs } ?: steps.lastOrNull())?.speed ?: 1.0
    }

    /** Where in the media (as [mediaSpeedAt] counts) the speed next changes, or null. */
    fun nextSpeedChangeUs(speed: Speed, sourceSpanUs: Long, sourceUs: Long): Long? {
        if (speed.ramp == null) return null
        val steps = steps(speed, sourceSpanUs)
        val current = steps.indexOfFirst { sourceUs < it.toUs }
        if (current < 0 || current == steps.lastIndex) return null
        // Rounded up, so the moment it gives already plays at the next speed.
        return maxOf(ceil(steps[current].toUs).toLong(), sourceUs + 1)
    }

    /** One step of a clip's ramp: media from [fromUs] to [toUs] at [speed]. */
    private class Step(val fromUs: Double, val toUs: Double, val speed: Double) {
        val timelineUs: Double get() = (toUs - fromUs) / speed
    }

    /** The steps of the ramp in the clip's window, over its [sourceSpanUs] of media. */
    private fun steps(speed: Speed, sourceSpanUs: Long): List<Step> {
        val from = speed.rampFrom.toDouble()
        val to = speed.rampTo.toDouble()
        if (sourceSpanUs <= 0 || to <= from) return emptyList()
        val count = speed.rampSteps.coerceIn(1, MAX_STEPS)
        val scale = sourceSpanUs / (to - from)
        val steps = ArrayList<Step>()
        for (k in floor(from * count).toInt() until ceil(to * count).toInt()) {
            val u0 = maxOf(from, k.toDouble() / count)
            val u1 = minOf(to, (k + 1).toDouble() / count)
            val start = (u0 - from) * scale
            val end = if (u1 >= to) sourceSpanUs.toDouble() else (u1 - from) * scale
            // A sliver left by rounding the window's ends plays nothing.
            if (end - start < 0.5) continue
            val middle = ((k + 0.5) / count).toFloat()
            steps += Step(start, end, speedAt(speed, middle).coerceAtLeast(MIN_SPEED.toDouble()))
        }
        return steps
    }

    /** About a tenth of a second of media per step, between these counts. */
    private const val STEP_US = 100_000L
    private const val MIN_STEPS = 4
    private const val MAX_STEPS = 256
}

/**
 * The time in its media asset that plays [offsetUs] into the clip (#22), at its speed, forwards
 * or reversed; null without media.
 */
fun Clip.mediaTimeAt(offsetUs: Long): Long? {
    val media = source ?: return null
    val span = media.outPointUs - media.inPointUs
    return media.inPointUs + SpeedCurve.sourceOffset(speed, span, offsetUs)
}

/**
 * When [mediaUs] of the clip's media asset plays on the timeline (#22), or null when the clip
 * has no media or holds a frame. Times outside the clip's media fall outside the clip.
 */
fun Clip.timelineAt(mediaUs: Long): Long? {
    val media = source ?: return null
    if (speed.ramp == null && speed.rate <= 0) return null
    val span = media.outPointUs - media.inPointUs
    val forward = if (speed.reverse) media.outPointUs - mediaUs else mediaUs - media.inPointUs
    return startUs + SpeedCurve.timelineOffset(speed, span, forward)
}

/**
 * The stretch of its media asset the clip plays from [fromUs] to [toUs] of it (#22), earliest
 * first whichever way it plays; null without media.
 */
fun Clip.mediaRangeOf(fromUs: Long, toUs: Long): LongRange? {
    val a = mediaTimeAt(fromUs) ?: return null
    val b = mediaTimeAt(toUs) ?: return null
    return minOf(a, b)..maxOf(a, b)
}

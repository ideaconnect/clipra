package tech.idct.clipra.core.model

import kotlin.math.abs

/**
 * Keyframes (#21): a property's value at moments of a clip, in clip time (from the clip's
 * start). Between two keyframes the value moves from one to the other, shaped by the easing of
 * the earlier one (linear or ease in-out); before the first and after the last it holds. A
 * property without keyframes has its plain value.
 *
 * Preview and export both draw with these functions, so a keyframed clip moves the same in both.
 */
object Keyframes {
    /** How near a time a keyframe counts as at it: under one frame at 50 fps. */
    const val SNAP_US = 20_000L

    fun transform(a: Transform, b: Transform, t: Float) = a.copy(
        x = lerp(a.x, b.x, t),
        y = lerp(a.y, b.y, t),
        scale = lerp(a.scale, b.scale, t),
        rotation = lerp(a.rotation, b.rotation, t),
        cropLeft = lerp(a.cropLeft, b.cropLeft, t),
        cropTop = lerp(a.cropTop, b.cropTop, t),
        cropRight = lerp(a.cropRight, b.cropRight, t),
        cropBottom = lerp(a.cropBottom, b.cropBottom, t),
    )

    fun opacity(a: Float, b: Float, t: Float) = lerp(a, b, t)

    fun mask(a: Mask, b: Mask, t: Float) = a.copy(
        feather = lerp(a.feather, b.feather, t),
        cornerRadius = lerp(a.cornerRadius, b.cornerRadius, t),
        x = lerp(a.x, b.x, t),
        y = lerp(a.y, b.y, t),
        width = lerp(a.width, b.width, t),
        height = lerp(a.height, b.height, t),
        rotation = lerp(a.rotation, b.rotation, t),
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** The value at [timeUs] (clip time), interpolated between keyframes with [lerp]. */
fun <T> Keyframed<T>.at(timeUs: Long, lerp: (T, T, Float) -> T): T {
    if (keyframes.isEmpty()) return value
    val first = keyframes.first()
    val last = keyframes.last()
    if (timeUs <= first.timeUs) return first.value
    if (timeUs >= last.timeUs) return last.value
    val next = keyframes.indexOfFirst { it.timeUs > timeUs }
    val from = keyframes[next - 1]
    val to = keyframes[next]
    val progress = (timeUs - from.timeUs).toFloat() / (to.timeUs - from.timeUs)
    return lerp(from.value, to.value, Transitions.ease(from.easing, progress))
}

/** The keyframe at [timeUs], within [Keyframes.SNAP_US], if there is one. */
fun <T> Keyframed<T>.keyframeAt(timeUs: Long): Keyframe<T>? =
    keyframes.filter { abs(it.timeUs - timeUs) <= Keyframes.SNAP_US }
        .minByOrNull { abs(it.timeUs - timeUs) }

/**
 * Sets the value at [timeUs]: the plain value while there are no keyframes, else the keyframe
 * there, added if there is none (so editing an animated clip animates it further).
 */
fun <T> Keyframed<T>.set(timeUs: Long, value: T): Keyframed<T> =
    if (keyframes.isEmpty()) copy(value = value) else withKeyframe(timeUs, value)

/** Adds a keyframe at [timeUs], or replaces the one there, keeping its easing. */
fun <T> Keyframed<T>.withKeyframe(timeUs: Long, value: T, easing: Easing? = null): Keyframed<T> {
    val existing = keyframeAt(timeUs)
    val time = existing?.timeUs ?: timeUs
    val keyframe = Keyframe(time, value, easing ?: existing?.easing ?: Easing.EaseInOut)
    return copy(
        keyframes = (keyframes - listOfNotNull(existing).toSet() + keyframe).sortedBy {
            it.timeUs
        },
    )
}

/** Removes the keyframe at [timeUs]; the last one leaves its value as the plain value. */
fun <T> Keyframed<T>.withoutKeyframe(timeUs: Long): Keyframed<T> {
    val existing = keyframeAt(timeUs) ?: return this
    val rest = keyframes - existing
    return if (rest.isEmpty()) Keyframed(existing.value) else copy(keyframes = rest)
}

/** Moves the keyframe at [fromUs] to [toUs]; one already at [toUs] gives way. */
fun <T> Keyframed<T>.movedKeyframe(fromUs: Long, toUs: Long): Keyframed<T> {
    val moving = keyframeAt(fromUs) ?: return this
    val others = (keyframes - moving).filter { abs(it.timeUs - toUs) > Keyframes.SNAP_US }
    return copy(keyframes = (others + moving.copy(timeUs = toUs)).sortedBy { it.timeUs })
}

/** Every keyframe [byUs] later (earlier when negative), for trims and splits. */
fun <T> Keyframed<T>.shifted(byUs: Long): Keyframed<T> = if (keyframes.isEmpty()) {
    this
} else {
    copy(
        keyframes = keyframes.map {
            it.copy(
                timeUs =
                it.timeUs + byUs,
            )
        },
    )
}

/** The clip's place in the frame at [clipTimeUs], its keyframes applied. */
fun Clip.transformAt(clipTimeUs: Long): Transform = transform.at(clipTimeUs, Keyframes::transform)

/** The clip's opacity at [clipTimeUs], its keyframes applied. */
fun Clip.opacityAt(clipTimeUs: Long): Float = opacity.at(clipTimeUs, Keyframes::opacity)

/** The clip's mask as keyframes, with the plain mask as the value. */
val Clip.maskKeyframed: Keyframed<Mask>?
    get() = mask?.let { Keyframed(it, maskKeyframes) }

/** The clip's mask at [clipTimeUs], its keyframes applied. */
fun Clip.maskAt(clipTimeUs: Long): Mask? = maskKeyframed?.at(clipTimeUs, Keyframes::mask)

/** Where the clip has keyframes of any property, in clip time, for the markers. */
val Clip.keyframeTimes: List<Long>
    get() = (
        transform.keyframes.map { it.timeUs } + opacity.keyframes.map { it.timeUs } +
            maskKeyframes.map { it.timeUs }
        ).distinct().sorted()

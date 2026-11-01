package tech.idct.clipra.core.model

import kotlin.math.exp
import kotlin.math.hypot

/** A face's box in a clip's picture: its centre and size, in fractions of the picture. */
data class FaceBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** What was found at [timeUs] of a clip (clip time): a face, or null where there was none. */
data class FaceSample(val timeUs: Long, val box: FaceBox?)

/**
 * Follow face (#52): from the face found in frames along a clip to a mask that follows it.
 * - [track] fills the frames without a face and smooths the path, so the mask doesn't jitter;
 * - [maskOver] puts the mask over the whole head, not just the face's box;
 * - [keyframes] turns the path into mask keyframes, which stay editable.
 */
object FaceTracking {
    /** How often the tracker looks: ten times a second of the clip. */
    const val SAMPLE_US = 100_000L

    /** Keyframes go in five times a second. */
    const val KEYFRAME_US = 200_000L

    /**
     * The face to follow among the faces seen at each sample ([seen], in order; the surest
     * first): from sample [anchor], the face nearest [near] (where the mask was put, so putting
     * it on a face picks that face; without it, the surest there), then from sample to sample
     * the face nearest the one before, forwards to the end and back to the start. Null where
     * no face was seen.
     */
    fun follow(seen: List<List<FaceBox>>, anchor: Int = 0, near: FaceBox? = null): List<FaceBox?> {
        val picked = arrayOfNulls<FaceBox>(seen.size)
        if (seen.isEmpty()) return picked.toList()
        val start = anchor.coerceIn(0, seen.lastIndex)
        fun along(indices: IntProgression, from: FaceBox?) {
            var last = from
            for (i in indices) {
                val faces = seen[i]
                val before = last
                val box = if (before == null) {
                    faces.firstOrNull()
                } else {
                    faces.minByOrNull { hypot(it.x - before.x, it.y - before.y) }
                }
                if (box != null) {
                    picked[i] = box
                    last = box
                }
            }
        }
        along(start until seen.size, near)
        along(start - 1 downTo 0, picked[start] ?: near)
        return picked.toList()
    }

    /**
     * The face's path: gaps between faces filled in a straight line, the ends held, then
     * smoothed over about [SMOOTH_SAMPLES] samples either side. Null when no face was found.
     */
    fun track(samples: List<FaceSample>): List<Pair<Long, FaceBox>>? {
        val found = samples.indices.filter { samples[it].box != null }
        if (found.isEmpty()) return null
        val filled = samples.indices.map { i ->
            samples[i].box ?: run {
                val before = found.lastOrNull { it < i }
                val after = found.firstOrNull { it > i }
                when {
                    before == null -> samples[after!!].box!!

                    after == null -> samples[before].box!!

                    else -> lerp(
                        samples[before].box!!,
                        samples[after].box!!,
                        (i - before).toFloat() / (after - before),
                    )
                }
            }
        }
        return samples.indices.map { i ->
            var weights = 0f
            var x = 0f
            var y = 0f
            var w = 0f
            var h = 0f
            for (j in i - SMOOTH_SAMPLES..i + SMOOTH_SAMPLES) {
                if (j !in filled.indices) continue
                val d = (j - i).toFloat() / SIGMA
                val weight = exp(-0.5f * d * d)
                val box = filled[j]
                x += box.x * weight
                y += box.y * weight
                w += box.width * weight
                h += box.height * weight
                weights += weight
            }
            samples[i].timeUs to FaceBox(x / weights, y / weights, w / weights, h / weights)
        }
    }

    /**
     * [mask] over the head around [face]: the face detector's box covers eyes to chin, so the
     * mask is wider and taller, and a little higher, to take in the forehead and hair. Its
     * shape and settings stay; a mask without a shape becomes an oval.
     */
    fun maskOver(face: FaceBox, mask: Mask): Mask = mask.copy(
        shape = if (mask.shape == MaskShape.None) MaskShape.Oval else mask.shape,
        followFace = true,
        x = face.x.coerceIn(0f, 1f),
        y = (face.y - RAISE * face.height).coerceIn(0f, 1f),
        width = (face.width * WIDER).coerceIn(MIN_SIZE, 1f),
        height = (face.height * TALLER).coerceIn(MIN_SIZE, 1f),
        rotation = 0f,
    )

    /** The path as mask keyframes every [KEYFRAME_US], straight between them. */
    fun keyframes(path: List<Pair<Long, FaceBox>>, mask: Mask): List<Keyframe<Mask>> {
        var next = Long.MIN_VALUE
        val keyframes = ArrayList<Keyframe<Mask>>()
        for ((time, box) in path) {
            if (time < next) continue
            keyframes += Keyframe(time, maskOver(box, mask), Easing.Linear)
            next = time + KEYFRAME_US
        }
        return keyframes
    }

    private fun lerp(a: FaceBox, b: FaceBox, t: Float) = FaceBox(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.width + (b.width - a.width) * t,
        a.height + (b.height - a.height) * t,
    )

    private const val SMOOTH_SAMPLES = 3
    private const val SIGMA = 1.5f
    private const val WIDER = 1.5f
    private const val TALLER = 1.9f
    private const val RAISE = 0.15f
    private const val MIN_SIZE = 0.02f
}

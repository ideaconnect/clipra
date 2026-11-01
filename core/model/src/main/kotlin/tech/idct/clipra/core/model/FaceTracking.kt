package tech.idct.clipra.core.model

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

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
     * no face was seen, or only faces too far from it ([reach]) or too unlike it in size
     * ([SIZE_CHANGE]) to be the same one.
     */
    fun follow(seen: List<List<FaceBox>>, anchor: Int = 0, near: FaceBox? = null): List<FaceBox?> {
        val picked = arrayOfNulls<FaceBox>(seen.size)
        if (seen.isEmpty()) return picked.toList()
        val start = anchor.coerceIn(0, seen.lastIndex)
        fun along(indices: IntProgression, from: FaceBox?, fromFace: Boolean) {
            var last = from
            // Sizes are compared with a face seen, not with the mask, which can be any size.
            var sized = fromFace
            var missed = 0
            for (i in indices) {
                val faces = seen[i]
                val before = last
                val box = if (before == null) {
                    faces.firstOrNull()
                } else {
                    val reach = reach(before, missed)
                    faces.map { it to hypot(it.x - before.x, it.y - before.y) }
                        .filter { (face, distance) ->
                            distance <= reach &&
                                (!sized || face.width / before.width in SIZE_RANGE)
                        }
                        .minByOrNull { (_, distance) -> distance }?.first
                }
                if (box != null) {
                    picked[i] = box
                    last = box
                    sized = true
                    missed = 0
                } else {
                    missed++
                }
            }
        }
        along(start until seen.size, near, fromFace = false)
        val first = picked[start]
        along(start - 1 downTo 0, first ?: near, fromFace = first != null)
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

    /**
     * How far (in fractions of the picture) the face [last] seen can be from there after
     * [missed] samples without it: [REACH_FACES] of its widths, a little more for each sample
     * it was missing, up to [MAX_REACH] times that. Further away it is someone else: in a street
     * the face detector finds other people, and a lost face mustn't jump to them (#182).
     */
    private fun reach(last: FaceBox, missed: Int): Float {
        val base = maxOf(last.width * REACH_FACES, MIN_REACH)
        return base * minOf(1f + missed * REACH_GROWTH, MAX_REACH)
    }

    /** A square of a picture, in pixels, to look for a small face in (#182). */
    data class Tile(val x: Int, val y: Int, val side: Int)

    /**
     * Squares over a [width] × [height] picture in which the face under [mask] (a fraction of
     * the picture, as wide as the face or [WIDER] when Follow face put it there) is big enough
     * for the face detector, which sees all it is given at [DETECTOR_SIDE] px: neighbours overlap
     * by at least a quarter, so a face on an edge is whole in one. None when the face is big
     * enough in the whole picture. In street footage the faces are a few dozen pixels of a 720p
     * frame, too small for the whole picture.
     */
    fun tiles(width: Int, height: Int, mask: FaceBox): List<Tile> {
        val face = mask.width / WIDER * width
        if (face * DETECTOR_SIDE / maxOf(width, height) >= FACE_PX) return emptyList()
        val side = (face * DETECTOR_SIDE / FACE_PX).roundToInt()
            .coerceIn(maxOf(width, height) / MAX_TILES_ACROSS, minOf(width, height))
        fun starts(length: Int): List<Int> {
            val count = ceil((length - side) / (side * TILE_STEP)).toInt() + 1
            if (count == 1) return listOf(0)
            return (0 until count).map { (it.toLong() * (length - side) / (count - 1)).toInt() }
        }
        return starts(height).flatMap { y -> starts(width).map { x -> Tile(x, y, side) } }
    }

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

    private const val REACH_FACES = 2.5f
    private const val MIN_REACH = 0.05f
    private const val REACH_GROWTH = 0.1f
    private const val MAX_REACH = 1.5f

    /** A face seen again is at most this much bigger or smaller (#182). */
    private const val SIZE_CHANGE = 2f
    private val SIZE_RANGE = 1f / SIZE_CHANGE..SIZE_CHANGE

    /** The face detector's input side, and how wide a face it finds well (#182). */
    private const val DETECTOR_SIDE = 128
    private const val FACE_PX = 20

    /** Tiles are at least a quarter of the long side: 15 at most in 16:9, 25 in a square. */
    private const val MAX_TILES_ACROSS = 4

    /** A tile starts at most three quarters of its side after the one before. */
    private const val TILE_STEP = 0.75f
}

package tech.idct.clipra.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Auto-reframe (#53): a copy of a project in another ratio, its shots cropped to follow what
 * matters in them. A clip that covers the frame gets a crop path: transform keyframes that keep
 * its subject (a person, a face, or the middle) where the new frame wants it, as far as the
 * picture still covers the frame, smoothed for the camera motion asked for. The path stays
 * editable as keyframes. Titles can be kept inside the new frame.
 */
object Reframe {
    /** What the crop follows. */
    enum class Follow { Subject, Face, Fixed }

    /** How often a clip is looked at: ten times a second, as for Follow face. */
    const val SAMPLE_US = FaceTracking.SAMPLE_US

    /** The board's camera motion: 30 of 100, nearer smooth than quick. */
    const val DEFAULT_MOTION = 30

    /** A box in fractions of the frame: what a title covers. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The new formats offered: the board's 9:16, 1:1 and 4:5, then 16:9; never the project's. */
    fun ratios(project: Project): List<AspectRatio> = listOf(
        AspectRatio.Portrait9x16,
        AspectRatio.Square1x1,
        AspectRatio.Portrait4x5,
        AspectRatio.Landscape16x9,
    ) - project.format.ratio

    /**
     * Whether [clip] gets a crop path: a video or photo covering the frame, not moved by
     * keyframes, not turned and not in a mosaic cell. Other clips keep their place.
     */
    fun follows(clip: Clip): Boolean {
        val transform = clip.transform.value
        return (clip.type == ClipType.Video || clip.type == ClipType.Photo) &&
            clip.source != null &&
            clip.cell == null &&
            clip.transform.keyframes.isEmpty() &&
            transform.fit == Fit.Fill &&
            transform.rotation == 0f &&
            transform.scale >= 1f
    }

    /** Where the new frame puts the subject: a face a little above the middle, else the middle. */
    fun aim(follow: Follow): Point = if (follow == Follow.Face) FACE_AIM else Point(0.5f, 0.5f)

    /**
     * The subject's path along a clip from [samples]: clip time, and where the subject is in
     * fractions of the picture, or null where none was seen. Each gap takes the nearest sighting,
     * then the path is smoothed for [motion] (0 smooth to 100 quick): the smoother, the more the
     * crop moves like a steady camera. A subject moving steadily is followed exactly up to the
     * clip's ends. With [leash] (fractions of the picture), the smoothed path never strays
     * further than that from the subject, so it stays in frame. Null when it was never seen.
     */
    fun path(
        samples: List<Pair<Long, Point?>>,
        motion: Int,
        leash: Point? = null,
    ): List<Pair<Long, Point>>? {
        if (samples.none { it.second != null }) return null
        // The nearest sighting before and after each sample.
        val before = arrayOfNulls<Int>(samples.size)
        val after = arrayOfNulls<Int>(samples.size)
        var last: Int? = null
        for (i in samples.indices) {
            if (samples[i].second != null) last = i
            before[i] = last
        }
        last = null
        for (i in samples.indices.reversed()) {
            if (samples[i].second != null) last = i
            after[i] = last
        }
        val filled = samples.indices.map { i ->
            val a = before[i]
            val b = after[i]
            val nearest = when {
                a == null -> b!!
                b == null -> a
                i - a <= b - i -> a
                else -> b
            }
            samples[nearest].second!!
        }
        val sigma = sigmaUs(motion).toFloat() / SAMPLE_US
        val reach = ceil(sigma * 3).toInt()
        val weights = FloatArray(reach * 2 + 1) { exp(-0.5f * sq((it - reach) / sigma)) }
        return samples.indices.map { i ->
            var smooth = localLine(filled, i, reach, weights)
            if (leash != null) {
                val subject = filled[i]
                smooth = Point(
                    smooth.x.coerceIn(subject.x - leash.x, subject.x + leash.x),
                    smooth.y.coerceIn(subject.y - leash.y, subject.y + leash.y),
                )
            }
            samples[i].first to smooth
        }
    }

    /**
     * The path at [i] by a straight line fitted to its neighbours, nearer ones counting more
     * ([weights], Gaussian): in the middle of a clip that is the Gaussian average; at its ends,
     * where neighbours are only on one side, the line keeps a steady move going instead of
     * dragging the crop towards where the subject will be.
     */
    private fun localLine(points: List<Point>, i: Int, reach: Int, weights: FloatArray): Point {
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxt = 0.0
        var syt = 0.0
        for (k in -reach..reach) {
            val p = points.getOrNull(i + k) ?: continue
            val w = weights[k + reach].toDouble()
            s0 += w
            s1 += w * k
            s2 += w * k * k
            sx += w * p.x
            sy += w * p.y
            sxt += w * p.x * k
            syt += w * p.y * k
        }
        val det = s0 * s2 - s1 * s1
        if (det < 1e-9 * s0 * s0) return Point((sx / s0).toFloat(), (sy / s0).toFloat())
        return Point(
            ((s2 * sx - s1 * sxt) / det).toFloat(),
            ((s2 * sy - s1 * syt) / det).toFloat(),
        )
    }

    /**
     * How far, in fractions of the [source] picture, the crop's middle may be from the subject
     * and still show it with a margin: [KEEP_IN] of half the window the [frame] cuts from the
     * picture as [transform] places it.
     */
    fun leash(transform: Transform, frame: Size, source: Size): Point {
        val corners = ClipGeometry.corners(transform.copy(x = 0f, y = 0f), frame, source)
        val width = corners.maxOf { it.x } - corners.minOf { it.x }
        val height = corners.maxOf { it.y } - corners.minOf { it.y }
        return Point(
            frame.width / width / 2 * KEEP_IN,
            frame.height / height / 2 * KEEP_IN,
        )
    }

    /** How far the smoothing reaches (a Gaussian's sigma): 2 s at smooth, 0.15 s at quick. */
    fun sigmaUs(motion: Int): Long {
        val quick = motion.coerceIn(0, 100) / 100f
        return (SMOOTHEST_US + (QUICKEST_US - SMOOTHEST_US) * quick).toLong()
    }

    /**
     * [transform] moved so the picture point [point] (fractions of the [source] picture) shows at
     * [aim] (fractions of the [frame]), as near as the picture still covering the frame allows.
     */
    fun centreOn(
        transform: Transform,
        frame: Size,
        source: Size,
        point: Point,
        aim: Point = Point(0.5f, 0.5f),
    ): Transform {
        val still = transform.copy(x = 0f, y = 0f)
        val at = ClipGeometry.toFrame(still, frame, source, point.x, point.y)
        val corners = ClipGeometry.corners(still, frame, source)
        val x = shift(
            wanted = aim.x - at.x / frame.width,
            low = corners.minOf { it.x } / frame.width,
            high = corners.maxOf { it.x } / frame.width,
        )
        val y = shift(
            wanted = aim.y - at.y / frame.height,
            low = corners.minOf { it.y } / frame.height,
            high = corners.maxOf { it.y } / frame.height,
        )
        return transform.copy(x = x, y = y)
    }

    /**
     * [wanted], limited so a picture spanning [low] to [high] (fractions of the frame, before the
     * move) still covers 0 to 1. A picture too small to cover stays where it is.
     */
    private fun shift(wanted: Float, low: Float, high: Float): Float {
        val most = -low
        val least = 1f - high
        if (least > most) return 0f
        return wanted.coerceIn(least, most)
    }

    /**
     * The crop path of [clip] in a [frame] of the new ratio, following [path]: the clip's
     * transform at its start, and keyframes where it moves. Keyframes are as few as keep the
     * path within [TOLERANCE] of the frame; a crop that hardly moves has none.
     */
    fun cropPath(
        clip: Clip,
        frame: Size,
        source: Size,
        path: List<Pair<Long, Point>>,
        aim: Point,
    ): Keyframed<Transform> {
        val base = clip.transform.value
        val moves = path.map { (time, point) ->
            time to centreOn(base, frame, source, point, aim)
        }
        // Nothing to follow: the middle of the picture in the middle of the frame.
        if (moves.isEmpty()) return Keyframed(centreOn(base, frame, source, MIDDLE))
        val first = moves.first().second
        if (moves.all { near(it.second, first) }) return Keyframed(first)
        val kept = simplify(moves)
        return Keyframed(
            first,
            kept.map { (time, transform) -> Keyframe(time, transform, Easing.Linear) },
        )
    }

    private fun near(a: Transform, b: Transform) =
        abs(a.x - b.x) <= TOLERANCE && abs(a.y - b.y) <= TOLERANCE

    /** The fewest moves that keep the rest within [TOLERANCE] (Ramer-Douglas-Peucker). */
    private fun simplify(moves: List<Pair<Long, Transform>>): List<Pair<Long, Transform>> {
        val keep = BooleanArray(moves.size)
        keep[0] = true
        keep[moves.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to moves.lastIndex)
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            if (to - from < 2) continue
            val (t0, a) = moves[from]
            val (t1, b) = moves[to]
            var worst = -1
            var worstOff = TOLERANCE
            for (i in from + 1 until to) {
                val (t, m) = moves[i]
                val f = (t - t0).toFloat() / (t1 - t0)
                val off = hypot(m.x - (a.x + (b.x - a.x) * f), m.y - (a.y + (b.y - a.y) * f))
                if (off > worstOff) {
                    worst = i
                    worstOff = off
                }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack.addLast(from to worst)
            stack.addLast(worst to to)
        }
        return moves.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * [transform] of a title moved just enough that its [box] (fractions of the frame, where the
     * title shows with no move or scale) is inside the title-safe area; one too big to fit is
     * centred.
     */
    fun inside(transform: Transform, box: Box): Transform {
        val s = transform.scale
        fun fit(low: Float, high: Float, shift: Float): Float {
            val from = 0.5f + (low - 0.5f) * s + shift
            val to = 0.5f + (high - 0.5f) * s + shift
            return when {
                to - from > 1f - 2 * SAFE_MARGIN -> shift + 0.5f - (from + to) / 2
                from < SAFE_MARGIN -> shift + SAFE_MARGIN - from
                to > 1f - SAFE_MARGIN -> shift - (to - (1f - SAFE_MARGIN))
                else -> shift
            }
        }
        return transform.copy(
            x = fit(box.left, box.right, transform.x),
            y = fit(box.top, box.bottom, transform.y),
        )
    }

    /**
     * [project] in [ratio]. Clips that [follows] covers follow their subject in [samples] (see
     * [path]), smoothed for [motion] and kept in frame; those without one are centred. With
     * [boxes] (where each title shows in the new frame), titles that don't move by keyframes are
     * kept inside it.
     */
    fun apply(
        project: Project,
        ratio: AspectRatio,
        follow: Follow,
        samples: Map<String, List<Pair<Long, Point?>>>,
        motion: Int,
        boxes: Map<String, Box>?,
    ): Project {
        val format = project.format.copy(ratio = ratio)
        val frame = format.outputSize
        val aim = aim(follow)
        return project.copy(
            format = format,
            layers = project.layers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        val source = clip.source?.let { project.asset(it.mediaId) }?.displaySize
                        val box = boxes?.get(clip.id)
                        when {
                            follows(clip) && source != null && source.width > 0 -> {
                                val leash = leash(clip.transform.value, frame, source)
                                val path = samples[clip.id]?.let { path(it, motion, leash) }
                                clip.copy(
                                    transform = cropPath(clip, frame, source, path.orEmpty(), aim),
                                )
                            }

                            box != null && clip.transform.keyframes.isEmpty() -> clip.copy(
                                transform = Keyframed(inside(clip.transform.value, box)),
                            )

                            else -> clip
                        }
                    },
                )
            },
        )
    }

    private fun sq(v: Float) = v * v

    private val MIDDLE = Point(0.5f, 0.5f)

    /** A face sits a little above the middle, leaving room for the shoulders below. */
    private val FACE_AIM = Point(0.5f, 0.42f)

    private const val SMOOTHEST_US = 2_000_000f
    private const val QUICKEST_US = 150_000f

    /** How far, in fractions of the frame, the keyframes may stray from the path. */
    private const val TOLERANCE = 0.004f

    /** The title-safe margin, as the Text board's guide draws it. */
    const val SAFE_MARGIN = 0.05f

    /** The subject stays within this share of half the window: a tenth of it is margin. */
    private const val KEEP_IN = 0.8f
}

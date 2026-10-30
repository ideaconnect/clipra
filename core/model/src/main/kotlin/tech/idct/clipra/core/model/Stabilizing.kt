package tech.idct.clipra.core.model

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The camera's motion into the frame at [timeUs] (media time) from the one before (#54): moved
 * by [dx], [dy] (fractions of the picture's width, for both, so turns stay true), turned by
 * [angle] (radians) and scaled by [scale], about the picture's centre.
 */
data class CameraStep(
    val timeUs: Long,
    val dx: Float,
    val dy: Float,
    val angle: Float,
    val scale: Float,
)

/**
 * How a frame is moved onto the steadied path (#54): moved by [dx], [dy] (fractions of the
 * picture's width), turned by [angle] and scaled by [scale] about its centre, then zoomed by
 * the clip's one zoom.
 */
data class Correction(val dx: Float, val dy: Float, val angle: Float, val scale: Float) {
    companion object {
        val NONE = Correction(0f, 0f, 0f, 1f)
    }
}

/**
 * A clip's stabilization, worked out (#54): a correction per frame of its media, and the one
 * zoom that keeps every corrected frame covering the picture, so no edge ever shows.
 */
data class Stabilized(val timesUs: List<Long>, val corrections: List<Correction>, val zoom: Float) {
    /** The share of the picture the zoom crops away, as the Stabilize sheet shows it. */
    val crop: Float get() = 1f - 1f / zoom

    /** The correction at [mediaUs]: the frame's, or between frames by straight lines. */
    fun at(mediaUs: Long): Correction {
        if (timesUs.isEmpty()) return Correction.NONE
        val i = timesUs.binarySearch(mediaUs)
        if (i >= 0) return corrections[i]
        val after = -i - 1
        if (after == 0) return corrections.first()
        if (after == timesUs.size) return corrections.last()
        val t = (mediaUs - timesUs[after - 1]).toFloat() / (timesUs[after] - timesUs[after - 1])
        val a = corrections[after - 1]
        val b = corrections[after]
        return Correction(
            a.dx + (b.dx - a.dx) * t,
            a.dy + (b.dy - a.dy) * t,
            a.angle + (b.angle - a.angle) * t,
            a.scale + (b.scale - a.scale) * t,
        )
    }
}

/**
 * Video stabilization's arithmetic (#54), the classical way: the camera's path is the sum of its
 * steps; a Gaussian smooths it (wider with more strength), so shake goes and pans stay; each
 * frame moves by the difference; one zoom per clip hides the edges. Strength trades smoothness
 * against crop: when the zoom would go past [MAX_ZOOM], the corrections are scaled back.
 */
object Stabilizing {
    /** The most a clip is zoomed in (crops a third of the picture's width at most). */
    const val MAX_ZOOM = 1.5f

    /**
     * Steadies [steps] (in media order, the first frame's step ignored) at [strength] (0 to 100)
     * for a picture [aspect] (width over height) wide.
     */
    fun stabilize(steps: List<CameraStep>, strength: Int, aspect: Float): Stabilized {
        val times = steps.map { it.timeUs }
        if (steps.size < 2 || strength <= 0) {
            return Stabilized(times, steps.map { Correction.NONE }, 1f)
        }
        // The path: where the camera has got to at each frame.
        val n = steps.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val a = FloatArray(n)
        val s = FloatArray(n)
        for (i in 1 until n) {
            x[i] = x[i - 1] + steps[i].dx
            y[i] = y[i - 1] + steps[i].dy
            a[i] = a[i - 1] + steps[i].angle
            s[i] = s[i - 1] + ln(steps[i].scale.coerceAtLeast(MIN_SCALE))
        }
        val frameUs = ((times.last() - times.first()) / (n - 1)).coerceAtLeast(1)
        val sigma = sigmaUs(strength) / frameUs
        val sx = smooth(x, sigma)
        val sy = smooth(y, sigma)
        val sa = smooth(a, sigma)
        val ss = smooth(s, sigma)
        val full = List(n) { i ->
            Correction(sx[i] - x[i], sy[i] - y[i], sa[i] - a[i], exp(ss[i] - s[i]))
        }
        // Back off until the zoom it needs is within reach.
        var share = 1f
        var corrections = full
        var zoom = zoomFor(corrections, aspect)
        while (zoom > MAX_ZOOM && share > 0.05f) {
            share *= 0.85f
            corrections = full.map { it.scaled(share) }
            zoom = zoomFor(corrections, aspect)
        }
        return Stabilized(times, corrections, zoom.coerceAtMost(MAX_ZOOM))
    }

    /** How wide the smoothing is at [strength]: a tenth of a second up to two seconds. */
    fun sigmaUs(strength: Int): Float = 100_000f + strength.coerceIn(0, 100) / 100f * 1_900_000f

    /**
     * Where the output point ([qx], [qy]) (fractions of the width from the centre, y down)
     * reads from in the frame, under [correction] and [zoom]: the inverse of moving the frame.
     */
    fun source(correction: Correction, zoom: Float, qx: Float, qy: Float): Pair<Float, Float> {
        val ux = qx / zoom - correction.dx
        val uy = qy / zoom - correction.dy
        val c = cos(-correction.angle)
        val sn = sin(-correction.angle)
        return ((c * ux - sn * uy) / correction.scale) to ((sn * ux + c * uy) / correction.scale)
    }

    /**
     * Where the frame's point ([px], [py]) shows in the steadied picture, the other way from
     * [source]: turned and scaled, moved, then zoomed.
     */
    fun picture(correction: Correction, zoom: Float, px: Float, py: Float): Pair<Float, Float> {
        val c = cos(correction.angle)
        val sn = sin(correction.angle)
        val ux = (c * px - sn * py) * correction.scale + correction.dx
        val uy = (sn * px + c * py) * correction.scale + correction.dy
        return (ux * zoom) to (uy * zoom)
    }

    /**
     * A face found in the frame as shot (#52), where it shows once the clip is steadied, for a
     * picture [aspect] wide.
     */
    fun steady(box: FaceBox, correction: Correction, zoom: Float, aspect: Float): FaceBox {
        val (qx, qy) = picture(correction, zoom, box.x - 0.5f, (box.y - 0.5f) / aspect)
        val grown = zoom * correction.scale
        return FaceBox(qx + 0.5f, qy * aspect + 0.5f, box.width * grown, box.height * grown)
    }

    /**
     * The least zoom at which every corrected frame still covers the whole picture: each corner
     * of the picture must read from inside the frame.
     */
    fun zoomFor(corrections: List<Correction>, aspect: Float): Float {
        val hw = 0.5f
        val hh = 0.5f / aspect
        var zoom = 1f
        for (correction in corrections) {
            // Where the centre reads from, and where each corner does at zoom 1, past it.
            val (ax, ay) = source(correction, Float.POSITIVE_INFINITY, 0f, 0f)
            for ((qx, qy) in listOf(-hw to -hh, hw to -hh, -hw to hh, hw to hh)) {
                val (px, py) = source(correction, 1f, qx, qy)
                val bx = px - ax
                val by = py - ay
                zoom = maxOf(zoom, needed(ax, bx, hw), needed(ay, by, hh))
            }
        }
        return zoom
    }

    /** The zoom at which a + b / zoom stays within ±[half]. */
    private fun needed(a: Float, b: Float, half: Float): Float = when {
        b > 0f -> if (half - a <= 0f) Float.MAX_VALUE else b / (half - a)
        b < 0f -> if (half + a <= 0f) Float.MAX_VALUE else -b / (half + a)
        else -> 1f
    }

    /** [values] smoothed by a Gaussian [sigma] samples wide, the ends mirrored. */
    private fun smooth(values: FloatArray, sigma: Float): FloatArray {
        if (sigma < 0.5f) return values.copyOf()
        val radius = (3 * sigma).toInt().coerceAtLeast(1)
        val weights = FloatArray(2 * radius + 1) { k ->
            val d = (k - radius) / sigma
            exp(-0.5f * d * d)
        }
        val n = values.size
        return FloatArray(n) { i ->
            var sum = 0f
            var total = 0f
            for (k in -radius..radius) {
                var j = i + k
                // Mirrored about the ends, so the path keeps its direction there.
                if (j < 0) j = -j
                if (j >= n) j = 2 * (n - 1) - j
                j = j.coerceIn(0, n - 1)
                val w = weights[k + radius]
                val mirrored = when {
                    i + k < 0 -> 2 * values[0] - values[j]
                    i + k >= n -> 2 * values[n - 1] - values[j]
                    else -> values[j]
                }
                sum += mirrored * w
                total += w
            }
            sum / total
        }
    }

    private fun Correction.scaled(share: Float) = Correction(
        dx * share,
        dy * share,
        angle * share,
        exp(ln(scale) * share),
    )

    private const val MIN_SCALE = 0.5f
}

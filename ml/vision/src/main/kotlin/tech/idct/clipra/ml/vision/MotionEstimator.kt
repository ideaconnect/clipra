package tech.idct.clipra.ml.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** A grey picture: [values] from 0 to 1, row by row from the top-left. */
class Gray(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(values.size == width * height)
    }

    /** The value at ([x], [y]), between pixels by bilinear interpolation, held at the edges. */
    fun at(x: Float, y: Float): Float = bilinear(values, width, height, x, y)

    /** Half the size, each pixel the mean of four. */
    fun half(): Gray {
        val w = width / 2
        val h = height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = 2 * y * width + 2 * x
                val sum = values[i] + values[i + 1] + values[i + width] + values[i + width + 1]
                out[y * w + x] = sum / 4
            }
        }
        return Gray(w, h, out)
    }

    companion object {
        /** [values] (row by row, [width] × [height]) at ([x], [y]), held at the edges. */
        fun bilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
            val cx = x.coerceIn(0f, width - 1.001f)
            val cy = y.coerceIn(0f, height - 1.001f)
            val x0 = cx.toInt()
            val y0 = cy.toInt()
            val fx = cx - x0
            val fy = cy - y0
            val i = y0 * width + x0
            val top = values[i] + (values[i + 1] - values[i]) * fx
            val bottom = values[i + width] + (values[i + width + 1] - values[i + width]) * fx
            return top + (bottom - top) * fy
        }

        /** [pixels] (ARGB) as grey, by luma. */
        fun of(pixels: IntArray, width: Int, height: Int): Gray = Gray(
            width,
            height,
            FloatArray(width * height) { i ->
                val p = pixels[i]
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            },
        )
    }
}

/**
 * The camera's motion from one frame to the next (#54), about the frame's centre: the earlier
 * frame maps onto the later one by scaling by [scale], turning by [angle] (radians, clockwise as
 * y goes down) and then moving by [dx], [dy] (pixels of the analysed picture).
 */
data class Motion(val dx: Float, val dy: Float, val angle: Float, val scale: Float) {
    companion object {
        val NONE = Motion(0f, 0f, 0f, 1f)
    }
}

/**
 * Camera motion between frames, the classical way, with no model (#54): Shi-Tomasi corners in
 * the earlier frame, followed into the later one by pyramidal Lucas-Kanade optical flow, and a
 * shift, turn and scale fitted to them by RANSAC, so that people and things moving across the
 * picture don't count. Plain Kotlin on small grey pictures; one instance per thread.
 */
class MotionEstimator(
    private val maxCorners: Int = MAX_CORNERS,
    private val random: Random = Random(54),
) {
    /**
     * A picture made ready for tracking: its pyramid, each level's slopes, and (made when it is
     * first the earlier picture) its corners. Along a clip, each frame is prepared once and is
     * the later picture, then the earlier one.
     */
    class Prepared internal constructor(
        val picture: Gray,
        internal val levels: List<Gray>,
        internal val slopes: List<Array<FloatArray>>,
    ) {
        internal var corners: List<FloatArray>? = null
    }

    /** [picture] made ready for [estimate]. */
    fun prepare(picture: Gray): Prepared {
        val levels = arrayListOf(picture)
        while (levels.size < LEVELS && levels.last().width >= 2 * MIN_LEVEL_SIDE) {
            levels += levels.last().half()
        }
        return Prepared(picture, levels, levels.map(::gradients))
    }

    /** The motion from [previous] to [next] (the same size); null when too little was trackable. */
    fun estimate(previous: Gray, next: Gray): Motion? = estimate(prepare(previous), prepare(next))

    /** The motion from [previous] to [next]; null when too little was trackable. */
    fun estimate(previous: Prepared, next: Prepared): Motion? {
        val corners = previous.corners ?: corners(previous).also { previous.corners = it }
        if (corners.size < MIN_POINTS) return null
        val pairs = ArrayList<FloatArray>(corners.size)
        for (corner in corners) {
            val moved = track(corner, previous, next) ?: continue
            pairs += floatArrayOf(corner[0], corner[1], moved[0], moved[1])
        }
        if (pairs.size < MIN_POINTS) return null
        return fit(pairs, previous.picture.width / 2f, previous.picture.height / 2f)
    }

    /** The picture's slopes across and down, by central differences. */
    private fun gradients(picture: Gray): Array<FloatArray> {
        val w = picture.width
        val h = picture.height
        val v = picture.values
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                gx[i] = (v[i + 1] - v[i - 1]) / 2
                gy[i] = (v[i + w] - v[i - w]) / 2
            }
        }
        return arrayOf(gx, gy)
    }

    /**
     * Shi-Tomasi corners: where the smaller eigenvalue of the slopes' structure over a small
     * window is high (window sums from integral images), the best in each cell of a grid, so
     * they spread over the picture.
     */
    private fun corners(prepared: Prepared): List<FloatArray> {
        val picture = prepared.picture
        val w = picture.width
        val h = picture.height
        val (gx, gy) = prepared.slopes[0]
        // Integral images of the slopes' products, one row and one column larger.
        val stride = w + 1
        val ixx = DoubleArray(stride * (h + 1))
        val iyy = DoubleArray(stride * (h + 1))
        val ixy = DoubleArray(stride * (h + 1))
        for (y in 0 until h) {
            var rxx = 0.0
            var ryy = 0.0
            var rxy = 0.0
            for (x in 0 until w) {
                val i = y * w + x
                rxx += gx[i] * gx[i]
                ryy += gy[i] * gy[i]
                rxy += gx[i] * gy[i]
                val at = (y + 1) * stride + x + 1
                ixx[at] = ixx[at - stride] + rxx
                iyy[at] = iyy[at - stride] + ryy
                ixy[at] = ixy[at - stride] + rxy
            }
        }
        fun box(sums: DoubleArray, x: Int, y: Int): Float {
            val x0 = x - CORNER_WINDOW
            val y0 = y - CORNER_WINDOW
            val x1 = x + CORNER_WINDOW + 1
            val y1 = y + CORNER_WINDOW + 1
            val sum = sums[y1 * stride + x1] - sums[y0 * stride + x1] -
                sums[y1 * stride + x0] + sums[y0 * stride + x0]
            return sum.toFloat()
        }
        val margin = WINDOW + 2
        val strength = FloatArray(w * h)
        var strongest = 0f
        for (y in margin until h - margin) {
            for (x in margin until w - margin) {
                val xx = box(ixx, x, y)
                val yy = box(iyy, x, y)
                val xy = box(ixy, x, y)
                val half = (xx - yy) / 2
                val min = (xx + yy) / 2 - sqrt(half * half + xy * xy)
                strength[y * w + x] = min
                if (min > strongest) strongest = min
            }
        }
        if (strongest <= 0f) return emptyList()
        val best = ArrayList<FloatArray>()
        for (cy in margin until h - margin step CELL) {
            for (cx in margin until w - margin step CELL) {
                var top = 0f
                var at = -1
                for (y in cy until minOf(cy + CELL, h - margin)) {
                    for (x in cx until minOf(cx + CELL, w - margin)) {
                        val s = strength[y * w + x]
                        if (s > top) {
                            top = s
                            at = y * w + x
                        }
                    }
                }
                if (at >= 0 && top > strongest * QUALITY) {
                    best += floatArrayOf((at % w).toFloat(), (at / w).toFloat(), top)
                }
            }
        }
        return best.sortedByDescending { it[2] }.take(maxCorners)
    }

    /**
     * Where [point] of the earlier picture went in the later one; null when it was lost. At each
     * level the window is read at whole pixels around the point (how far it moved doesn't
     * depend on where in the pixel it sits), and the later picture between pixels.
     */
    private fun track(point: FloatArray, from: Prepared, to: Prepared): FloatArray? {
        var dx = 0f
        var dy = 0f
        val n = (2 * WINDOW + 1) * (2 * WINDOW + 1)
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val values = FloatArray(n)
        for (level in from.levels.indices.reversed()) {
            val prev = from.levels[level]
            val next = to.levels[level]
            val w = prev.width
            val cx = (point[0] / (1 shl level)).roundToInt()
                .coerceIn(WINDOW + 1, prev.width - WINDOW - 2)
            val cy = (point[1] / (1 shl level)).roundToInt()
                .coerceIn(WINDOW + 1, prev.height - WINDOW - 2)
            val (ix, iy) = from.slopes[level]
            // The window's structure, from the earlier picture.
            var xx = 0f
            var yy = 0f
            var xy = 0f
            var k = 0
            for (oy in -WINDOW..WINDOW) {
                for (ox in -WINDOW..WINDOW) {
                    val i = (cy + oy) * w + cx + ox
                    sx[k] = ix[i]
                    sy[k] = iy[i]
                    values[k] = prev.values[i]
                    xx += sx[k] * sx[k]
                    yy += sy[k] * sy[k]
                    xy += sx[k] * sy[k]
                    k++
                }
            }
            val det = xx * yy - xy * xy
            if (det < MIN_DET) return null
            var vx = 0f
            var vy = 0f
            for (iteration in 0 until ITERATIONS) {
                var bx = 0f
                var by = 0f
                k = 0
                val qx = cx + dx + vx
                val qy = cy + dy + vy
                for (oy in -WINDOW..WINDOW) {
                    for (ox in -WINDOW..WINDOW) {
                        val diff = values[k] - next.at(qx + ox, qy + oy)
                        bx += diff * sx[k]
                        by += diff * sy[k]
                        k++
                    }
                }
                val ex = (yy * bx - xy * by) / det
                val ey = (xx * by - xy * bx) / det
                vx += ex
                vy += ey
                if (abs(ex) < EPSILON && abs(ey) < EPSILON) break
            }
            dx += vx
            dy += vy
            if (level > 0) {
                dx *= 2
                dy *= 2
            }
        }
        val x = point[0] + dx
        val y = point[1] + dy
        val last = from.picture
        if (x < 0 || y < 0 || x > last.width - 1 || y > last.height - 1) return null
        return floatArrayOf(x, y)
    }

    /**
     * The shift, turn and scale about ([cx], [cy]) that best maps each pair's first point onto
     * its second: RANSAC on two pairs at a time, then least squares over the pairs that agree.
     */
    private fun fit(pairs: List<FloatArray>, cx: Float, cy: Float): Motion? {
        var bestInliers: List<FloatArray> = emptyList()
        repeat(RANSAC_ROUNDS) {
            val a = pairs[random.nextInt(pairs.size)]
            val b = pairs[random.nextInt(pairs.size)]
            if (a === b) return@repeat
            val model = solve(listOf(a, b), cx, cy) ?: return@repeat
            val inliers = pairs.filter { error(model, it, cx, cy) < INLIER_PX }
            if (inliers.size > bestInliers.size) bestInliers = inliers
        }
        if (bestInliers.size < MIN_POINTS) return null
        val model = solve(bestInliers, cx, cy) ?: return null
        val (a, b, tx, ty) = model
        return Motion(tx, ty, atan2(b, a), hypot(a, b))
    }

    /** Least squares for p' - c = [a -b; b a](p - c) + t; null when degenerate. */
    private fun solve(pairs: List<FloatArray>, cx: Float, cy: Float): FloatArray? {
        // Normal equations, with centred coordinates.
        var n = 0.0
        var sx = 0.0
        var sy = 0.0
        var su = 0.0
        var sv = 0.0
        var sxx = 0.0
        var sxu = 0.0
        var syv = 0.0
        var sxv = 0.0
        var syu = 0.0
        for (p in pairs) {
            val x = (p[0] - cx).toDouble()
            val y = (p[1] - cy).toDouble()
            val u = (p[2] - cx).toDouble()
            val v = (p[3] - cy).toDouble()
            n++
            sx += x
            sy += y
            su += u
            sv += v
            sxx += x * x + y * y
            sxu += x * u
            syv += y * v
            sxv += x * v
            syu += y * u
        }
        val mx = sx / n
        val my = sy / n
        val mu = su / n
        val mv = sv / n
        val spread = sxx / n - mx * mx - my * my
        if (spread < 1e-9) return null
        val a = ((sxu + syv) / n - (mx * mu + my * mv)) / spread
        val b = ((sxv - syu) / n - (mx * mv - my * mu)) / spread
        val tx = mu - (a * mx - b * my)
        val ty = mv - (b * mx + a * my)
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    private fun error(model: FloatArray, pair: FloatArray, cx: Float, cy: Float): Float {
        val (a, b, tx, ty) = model
        val x = pair[0] - cx
        val y = pair[1] - cy
        val u = a * x - b * y + tx + cx
        val v = b * x + a * y + ty + cy
        return hypot(u - pair[2], v - pair[3])
    }

    private companion object {
        const val MAX_CORNERS = 120
        const val MIN_POINTS = 8
        const val LEVELS = 3
        const val MIN_LEVEL_SIDE = 24
        const val WINDOW = 5
        const val CORNER_WINDOW = 2
        const val CELL = 16
        const val QUALITY = 0.001f
        const val ITERATIONS = 10
        const val EPSILON = 0.005f
        const val MIN_DET = 1e-7f
        const val RANSAC_ROUNDS = 120
        const val INLIER_PX = 1.5f
    }
}

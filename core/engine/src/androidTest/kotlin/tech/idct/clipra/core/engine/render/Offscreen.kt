package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
import kotlin.math.abs

/** Compositor frames drawn offscreen, and how alike two frames are, for golden-image tests. */
object Offscreen {
    /** The frame of [timeline] at [timeUs]. */
    fun render(context: Context, timeline: RenderTimeline, timeUs: Long): Bitmap =
        StillRenderer.renderOnce(context, timeline, timeUs)

    /** The share of pixels within [tolerance] on every channel. */
    fun match(expected: Bitmap, actual: Bitmap, tolerance: Int): Double {
        val count = expected.width * expected.height
        val a = pixels(expected)
        val b = pixels(actual)
        var close = 0
        for (i in 0 until count) {
            val near = abs(Color.red(a[i]) - Color.red(b[i])) <= tolerance &&
                abs(Color.green(a[i]) - Color.green(b[i])) <= tolerance &&
                abs(Color.blue(a[i]) - Color.blue(b[i])) <= tolerance
            if (near) close++
        }
        return close.toDouble() / count
    }

    /**
     * How far apart the colours of two frames are, wherever each colour sits (#200): for the
     * whole frame and for the worst cell of a [columns] by [rows] grid, the earth mover's
     * distance between the histograms of each channel, the worst channel, in levels (0 to 255).
     * Two draws of the same noise come out close; a missing, weaker or misplaced effect does not.
     */
    fun likeness(expected: Bitmap, actual: Bitmap, columns: Int = 3, rows: Int = 4): Likeness {
        require(expected.width == actual.width && expected.height == actual.height) {
            "${actual.width}x${actual.height} frame, ${expected.width}x${expected.height} golden"
        }
        val width = expected.width
        val height = expected.height
        val a = pixels(expected)
        val b = pixels(actual)
        var region = 0.0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = distance(
                    a,
                    b,
                    width,
                    column * width / columns,
                    row * height / rows,
                    (column + 1) * width / columns,
                    (row + 1) * height / rows,
                )
                region = maxOf(region, cell)
            }
        }
        return Likeness(distance(a, b, width, 0, 0, width, height), region)
    }

    /** The worst channel's earth mover's distance between [a] and [b] in a rectangle, in levels. */
    private fun distance(
        a: IntArray,
        b: IntArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        val count = (right - left) * (bottom - top)
        var worst = 0.0
        for (shift in intArrayOf(16, 8, 0)) {
            // a counts up and b counts down, so the running sum is the gap between the two
            // cumulative histograms; in one dimension its area is the earth mover's distance.
            val histogram = IntArray(256)
            for (y in top until bottom) {
                for (x in left until right) {
                    val i = y * stride + x
                    histogram[(a[i] shr shift) and 0xFF]++
                    histogram[(b[i] shr shift) and 0xFF]--
                }
            }
            var gap = 0
            var area = 0L
            for (level in 0 until 255) {
                gap += histogram[level]
                area += abs(gap)
            }
            worst = maxOf(worst, area.toDouble() / count)
        }
        return worst
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}

/**
 * What [Offscreen.likeness] found, in levels: [frame], the colour distance of the whole frame,
 * and [region], that of the grid cell furthest from the golden.
 */
data class Likeness(val frame: Double, val region: Double) {
    fun within(limit: Likeness) = frame <= limit.frame && region <= limit.region

    override fun toString() = "frame %.2f, region %.2f".format(Locale.ROOT, frame, region)
}

/**
 * How far frames with random parts may drift from their goldens. The shaders' random numbers
 * come from `sin()`, which every GPU rounds its own way, so another GPU draws the same effect
 * with another pattern (#200). These frames are compared with [Offscreen.likeness] instead of
 * pixel by pixel. The limits sit at least 1.7 times above the worst drift of Galaxy S9
 * (Mali-G72) frames and of frames drawn with nine other hash functions, which change the
 * pattern as another GPU does, and a third above the drift of Shake's largest jitter.
 */
enum class Randomness(val limit: Likeness) {
    /** Per-pixel noise (grain), or thin lines that rounding draws or leaves out. */
    Fine(Likeness(frame = 2.0, region = 5.0)),

    /** Random offsets and shapes: glitch bands, the shake, the ragged edges of burn and ink. */
    Coarse(Likeness(frame = 8.0, region = 45.0)),
}

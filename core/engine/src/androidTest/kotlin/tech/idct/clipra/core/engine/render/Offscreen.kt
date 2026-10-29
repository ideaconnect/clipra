package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** Compositor frames drawn offscreen, and how alike two frames are, for golden-image tests. */
object Offscreen {
    /** The frame of [timeline] at [timeUs]. */
    fun render(context: Context, timeline: RenderTimeline, timeUs: Long): Bitmap =
        StillRenderer.renderOnce(context, timeline, timeUs)

    /** The share of pixels within [tolerance] on every channel. */
    fun match(expected: Bitmap, actual: Bitmap, tolerance: Int): Double {
        val count = expected.width * expected.height
        val a = IntArray(count).also {
            expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height)
        }
        val b = IntArray(count).also {
            actual.getPixels(it, 0, actual.width, 0, 0, actual.width, actual.height)
        }
        var close = 0
        for (i in 0 until count) {
            val near = abs(Color.red(a[i]) - Color.red(b[i])) <= tolerance &&
                abs(Color.green(a[i]) - Color.green(b[i])) <= tolerance &&
                abs(Color.blue(a[i]) - Color.blue(b[i])) <= tolerance
            if (near) close++
        }
        return close.toDouble() / count
    }
}

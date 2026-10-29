package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.BlendMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Golden-image tests for the 16 blend modes (#20). A clip with gradients in every channel is
 * blended at 75% opacity over a backdrop with other gradients; the compositor's frame must match
 * a reference computed here, pixel by pixel, from the W3C Compositing and Blending formulas (a
 * separate implementation from the shader). The frame is as large as the pictures, so each pixel
 * samples exactly one texel. Preview and export run this same compositor, so they blend alike.
 */
@RunWith(AndroidJUnit4::class)
class BlendGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backdrop = pattern { x, y -> Color.rgb(level(x), level(y), (x + y) * 255 / 126) }

    // Smooth gradients: Dodge and Burn divide by the clip's values, so sharp steps between
    // neighbouring pixels would magnify sub-texel sampling differences.
    private val source = pattern { x, y ->
        Color.rgb(level(SIZE - 1 - x), (x + 2 * y) * 255 / 189, level(SIZE - 1 - y) / 2 + 64)
    }

    @Test
    fun everyModeMatchesTheReference() {
        val failures = BlendMode.entries.mapNotNull { mode ->
            val rendered = Offscreen.render(context, timeline(mode), 500_000)
            val expected = reference(mode)
            val matched = Offscreen.match(expected, rendered, TOLERANCE)
            Log.i(TAG, "$mode: ${"%.4f".format(matched)} of pixels match")
            if (matched < SHARE) {
                save("$mode-expected", expected)
                save("$mode-actual", rendered)
                logMismatches(mode, expected, rendered)
                "$mode (${"%.4f".format(matched)})"
            } else {
                null
            }
        }
        assertTrue("modes off the reference: $failures", failures.isEmpty())
    }

    @Test
    fun previewSizeMatchesExportSize() {
        // The same stack at export size (1080 × 1920) and preview size (540 × 960).
        for (mode in listOf(BlendMode.Overlay, BlendMode.Burn, BlendMode.Hue)) {
            val export = Offscreen.render(context, sized(timeline(mode), 1080, 1920), 500_000)
            val preview = Offscreen.render(context, sized(timeline(mode), 540, 960), 500_000)
            val scaled = Bitmap.createScaledBitmap(export, 540, 960, true)
            val matched = Offscreen.match(scaled, preview, 6)
            Log.i(TAG, "$mode preview vs export: ${"%.4f".format(matched)}")
            assertTrue("$mode preview vs export: $matched", matched >= 0.99)
        }
    }

    private fun sized(timeline: RenderTimeline, width: Int, height: Int) =
        timeline.copy(width = width, height = height)

    @Test
    fun modesDiffer() {
        // Guards the test itself: each mode draws something of its own.
        val frames = BlendMode.entries.associateWith {
            Offscreen.render(context, timeline(it), 500_000)
        }
        val modes = BlendMode.entries
        for (i in modes.indices) {
            for (j in i + 1 until modes.size) {
                val same = Offscreen.match(frames.getValue(modes[i]), frames.getValue(modes[j]), 2)
                assertTrue("${modes[i]} and ${modes[j]} look the same", same < 0.99)
            }
        }
    }

    /** The first pixels off the reference, with the inputs, to see which case of a formula. */
    private fun logMismatches(mode: BlendMode, expected: Bitmap, actual: Bitmap) {
        var shown = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val e = expected.getPixel(x, y)
                val a = actual.getPixel(x, y)
                val off = abs(Color.red(e) - Color.red(a)) > TOLERANCE ||
                    abs(Color.green(e) - Color.green(a)) > TOLERANCE ||
                    abs(Color.blue(e) - Color.blue(a)) > TOLERANCE
                if (off && shown++ < 8) {
                    Log.w(
                        TAG,
                        "$mode at $x,$y: backdrop ${hex(backdrop.getPixel(x, y))} clip " +
                            "${hex(source.getPixel(x, y))} expected ${hex(e)} drawn ${hex(a)}",
                    )
                }
            }
        }
    }

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    private fun timeline(mode: BlendMode) = RenderTimeline(
        width = SIZE,
        height = SIZE,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(listOf(RenderClip("backdrop", 0, 1_000_000, RenderSource.Image(backdrop)))),
            RenderLayer(
                listOf(
                    RenderClip(
                        "clip",
                        0,
                        1_000_000,
                        RenderSource.Image(source),
                        blend = mode,
                        opacity = OPACITY,
                    ),
                ),
            ),
        ),
    )

    private fun reference(mode: BlendMode): Bitmap {
        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val b = rgb(backdrop.getPixel(x, y))
                val s = rgb(source.getPixel(x, y))
                val blended = blend(mode, b, s)
                val mixed = DoubleArray(3) {
                    b[it] +
                        (blended[it].coerceIn(0.0, 1.0) - b[it]) * OPACITY
                }
                output.setPixel(
                    x,
                    y,
                    Color.rgb(
                        (mixed[0] * 255).roundToInt(),
                        (mixed[1] * 255).roundToInt(),
                        (mixed[2] * 255).roundToInt(),
                    ),
                )
            }
        }
        return output
    }

    private fun blend(mode: BlendMode, b: DoubleArray, s: DoubleArray): DoubleArray {
        fun each(f: (Double, Double) -> Double) = DoubleArray(3) { f(b[it], s[it]) }
        return when (mode) {
            BlendMode.Normal -> s

            BlendMode.Multiply -> each { cb, cs -> cb * cs }

            BlendMode.Screen -> each(::screen)

            BlendMode.Overlay -> each { cb, cs -> hardLight(cs, cb) }

            BlendMode.SoftLight -> each(::softLight)

            BlendMode.HardLight -> each(::hardLight)

            BlendMode.Darken -> each(::min)

            BlendMode.Lighten -> each(::max)

            BlendMode.Dodge -> each { cb, cs ->
                when {
                    cb == 0.0 -> 0.0
                    cs == 1.0 -> 1.0
                    else -> min(1.0, cb / (1 - cs))
                }
            }

            BlendMode.Burn -> each { cb, cs ->
                when {
                    cb == 1.0 -> 1.0
                    cs == 0.0 -> 0.0
                    else -> 1 - min(1.0, (1 - cb) / cs)
                }
            }

            BlendMode.Add -> each { cb, cs -> min(1.0, cb + cs) }

            BlendMode.Difference -> each { cb, cs -> abs(cb - cs) }

            BlendMode.Exclusion -> each { cb, cs -> cb + cs - 2 * cb * cs }

            BlendMode.Hue -> setLum(setSat(s, sat(b)), lum(b))

            BlendMode.Color -> setLum(s, lum(b))

            BlendMode.Luminosity -> setLum(b, lum(s))
        }
    }

    private fun screen(b: Double, s: Double) = b + s - b * s

    private fun hardLight(b: Double, s: Double) = if (s <= 0.5) b * 2 * s else screen(b, 2 * s - 1)

    private fun softLight(b: Double, s: Double): Double {
        if (s <= 0.5) return b - (1 - 2 * s) * b * (1 - b)
        val d = if (b <= 0.25) ((16 * b - 12) * b + 4) * b else sqrt(b)
        return b + (2 * s - 1) * (d - b)
    }

    private fun lum(c: DoubleArray) = 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]

    private fun clipColor(c: DoubleArray): DoubleArray {
        val l = lum(c)
        val n = c.min()
        val x = c.max()
        var result = c
        if (n < 0) result = DoubleArray(3) { l + (result[it] - l) * l / (l - n) }
        if (x > 1) result = DoubleArray(3) { l + (result[it] - l) * (1 - l) / (x - l) }
        return result
    }

    private fun setLum(c: DoubleArray, l: Double): DoubleArray {
        val d = l - lum(c)
        return clipColor(DoubleArray(3) { c[it] + d })
    }

    private fun sat(c: DoubleArray) = c.max() - c.min()

    /** The spec's SetSat: the largest channel becomes [s], the smallest 0, the middle scales. */
    private fun setSat(c: DoubleArray, s: Double): DoubleArray {
        val order = c.indices.sortedBy { c[it] }
        val (low, mid, high) = order
        val result = DoubleArray(3)
        if (c[high] > c[low]) {
            result[mid] = (c[mid] - c[low]) * s / (c[high] - c[low])
            result[high] = s
        }
        return result
    }

    private fun rgb(color: Int) = doubleArrayOf(
        Color.red(color) / 255.0,
        Color.green(color) / 255.0,
        Color.blue(color) / 255.0,
    )

    private fun pattern(color: (Int, Int) -> Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) bitmap.setPixel(x, y, color(x, y))
        }
        return bitmap
    }

    private fun level(step: Int) = step * 255 / (SIZE - 1)

    private fun save(name: String, bitmap: Bitmap) {
        File(context.cacheDir, "blend-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TAG = "BlendGolden"
        const val SIZE = 64
        const val OPACITY = 0.75f

        // 8-bit canvases round each step, so a few levels apart still match.
        const val TOLERANCE = 3
        const val SHARE = 0.995
    }
}

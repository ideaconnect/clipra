package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.Transform
import kotlin.math.abs

/**
 * Adjustment clips (#23) in the compositor, as preview and export draw them: one on L5 that
 * takes the colour out greys L1 to L4 under it, and L6 above it keeps its colour.
 */
@RunWith(AndroidJUnit4::class)
class AdjustmentRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun solid(color: Int): Bitmap = Bitmap.createBitmap(
        IntArray(SIDE * SIDE) { color },
        SIDE,
        SIDE,
        Bitmap.Config.ARGB_8888,
    )

    /** [color] filling a quarter of the frame, its middle moved by ([x], [y]) of the frame. */
    private fun quarter(id: String, color: Int, x: Float, y: Float) = RenderLayer(
        listOf(
            RenderClip(
                id,
                0,
                1_000_000,
                RenderSource.Image(solid(color)),
                transform = Transform(x = x, y = y, scale = 0.5f),
            ),
        ),
    )

    private fun frame(adjustment: RenderClip?): Bitmap = Offscreen.render(
        context,
        RenderTimeline(
            width = SIDE,
            height = SIDE,
            frameRate = 30,
            durationUs = 1_000_000,
            background = Color.BLACK,
            layers = listOf(
                // L1 red fills the frame; L2 to L4 green, blue and yellow in three quarters.
                RenderLayer(
                    listOf(RenderClip("red", 0, 1_000_000, RenderSource.Image(solid(RED)))),
                ),
                quarter("green", GREEN, -0.25f, -0.25f),
                quarter("blue", BLUE, 0.25f, -0.25f),
                quarter("yellow", YELLOW, -0.25f, 0.25f),
                RenderLayer(listOfNotNull(adjustment)),
                // L6 magenta in the last quarter, above the adjustment.
                quarter("magenta", MAGENTA, 0.25f, 0.25f),
            ),
        ),
        500_000,
    )

    private fun grey(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return abs(r - g) <= 6 && abs(g - b) <= 6 && abs(r - b) <= 6
    }

    @Test
    fun anAdjustmentOnL5GreysTheLayersBelowItButNotL6() {
        val before = frame(null)
        val after = frame(
            RenderClip(
                "fx",
                0,
                1_000_000,
                RenderSource.Adjustment,
                color = ColorGrade(saturation = -100f),
            ),
        )
        // The four quarters' middles: L2, L3, L4 below the adjustment, L6 above it.
        val below = listOf(SIDE / 4 to SIDE / 4, SIDE * 3 / 4 to SIDE / 4, SIDE / 4 to SIDE * 3 / 4)
        for ((x, y) in below) {
            assertTrue("before $x,$y", !grey(before.getPixel(x, y)))
            val c = after.getPixel(x, y)
            assertTrue("after $x,$y #%06X".format(c and 0xFFFFFF), grey(c))
        }
        // L6, above the adjustment, keeps its colour.
        val top = after.getPixel(SIDE * 3 / 4, SIDE * 3 / 4)
        assertTrue("L6 #%06X".format(top and 0xFFFFFF), !grey(top))
        assertTrue(abs(Color.red(top) - Color.red(MAGENTA)) <= 8)
        assertTrue(abs(Color.blue(top) - Color.blue(MAGENTA)) <= 8)
    }

    @Test
    fun aHalfFadedAdjustmentGreysHalfWay() {
        val half = frame(
            RenderClip(
                "fx",
                0,
                1_000_000,
                RenderSource.Adjustment,
                color = ColorGrade(saturation = -100f),
                opacity = 0.5f,
            ),
        )
        val c = half.getPixel(SIDE / 4, SIDE / 4)
        // Green, halfway to its grey: still green, less so.
        assertTrue("#%06X".format(c and 0xFFFFFF), Color.green(c) > Color.red(c) + 40)
        assertTrue("#%06X".format(c and 0xFFFFFF), Color.red(c) > 40)
    }

    private companion object {
        const val SIDE = 64
        val RED = Color.rgb(229, 72, 46)
        val GREEN = Color.rgb(23, 181, 90)
        val BLUE = Color.rgb(47, 79, 224)
        val YELLOW = Color.rgb(250, 210, 60)
        val MAGENTA = Color.rgb(220, 60, 200)
    }
}

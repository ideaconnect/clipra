package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Correction
import tech.idct.clipra.core.model.Stabilized
import kotlin.math.abs

/**
 * Stabilization (#54) in the compositor, as preview and export draw it: a frame moves by its
 * correction (right and down as the numbers say) and the clip's zoom fills the picture from its
 * middle.
 */
@RunWith(AndroidJUnit4::class)
class StabilizeRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Four colours, one per quarter: red top-left, green top-right, blue and white below. */
    private val quarters: Bitmap = Bitmap.createBitmap(
        IntArray(SIDE * SIDE) { i ->
            val left = i % SIDE < SIDE / 2
            val top = i / SIDE < SIDE / 2
            when {
                top && left -> RED
                top -> GREEN
                left -> BLUE
                else -> Color.WHITE
            }
        },
        SIDE,
        SIDE,
        Bitmap.Config.ARGB_8888,
    )

    private fun frame(correction: Correction, zoom: Float): Bitmap = Offscreen.render(
        context,
        RenderTimeline(
            width = SIDE,
            height = SIDE,
            frameRate = 30,
            durationUs = 1_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "shaky",
                            0,
                            1_000_000,
                            RenderSource.Image(quarters),
                            stabilized = Stabilized(listOf(0L), listOf(correction), zoom),
                        ),
                    ),
                ),
            ),
        ),
        500_000,
    )

    private fun assertColor(expected: Int, actual: Int, where: String) {
        val near = abs(Color.red(expected) - Color.red(actual)) <= 8 &&
            abs(Color.green(expected) - Color.green(actual)) <= 8 &&
            abs(Color.blue(expected) - Color.blue(actual)) <= 8
        assertTrue("$where #%06X".format(actual and 0xFFFFFF), near)
    }

    @Test
    fun aFrameMovesByItsCorrection() {
        // A tenth of the width to the right: the line between red and green moves right.
        val right = frame(Correction(0.1f, 0f, 0f, 1f), zoom = 1.25f)
        assertColor(RED, right.getPixel(SIDE / 2 + 4, 8), "right of the middle, top")
        // A tenth down: the line between red and blue moves down.
        val down = frame(Correction(0f, 0.1f, 0f, 1f), zoom = 1.25f)
        assertColor(RED, down.getPixel(8, SIDE / 2 + 4), "below the middle, left")
        // Without a correction the quarters stay where they were.
        val still = frame(Correction.NONE, zoom = 1f)
        assertColor(GREEN, still.getPixel(SIDE / 2 + 4, 8), "still, top right")
        assertColor(BLUE, still.getPixel(8, SIDE / 2 + 4), "still, bottom left")
    }

    @Test
    fun theZoomFillsThePictureFromTheMiddle() {
        val zoomed = frame(Correction.NONE, zoom = 2f)
        // Each corner shows its quarter, nearer the middle; no black edge anywhere.
        assertColor(RED, zoomed.getPixel(1, 1), "top left")
        assertColor(GREEN, zoomed.getPixel(SIDE - 2, 1), "top right")
        assertColor(BLUE, zoomed.getPixel(1, SIDE - 2), "bottom left")
        assertColor(Color.WHITE, zoomed.getPixel(SIDE - 2, SIDE - 2), "bottom right")
        // A small turn with a zoom that covers it leaves no black at the corners.
        val turned = frame(Correction(0f, 0f, 0.1f, 1f), zoom = 1.25f)
        for ((x, y) in listOf(0 to 0, SIDE - 1 to 0, 0 to SIDE - 1, SIDE - 1 to SIDE - 1)) {
            val c = turned.getPixel(x, y)
            assertTrue("corner $x,$y", Color.red(c) + Color.green(c) + Color.blue(c) > 100)
        }
    }

    private companion object {
        const val SIDE = 64
        val RED = Color.rgb(229, 72, 46)
        val GREEN = Color.rgb(23, 121, 90)
        val BLUE = Color.rgb(47, 79, 224)
    }
}

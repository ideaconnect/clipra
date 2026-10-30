package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.CutoutMasks
import tech.idct.clipra.core.model.Behind
import tech.idct.clipra.core.model.Cutout
import java.io.File
import kotlin.math.abs

/**
 * The person cutout (#50) in the compositor, as preview and export draw it: with a mask that
 * says the top half is a person, the top shows the clip and the bottom what Behind says. The
 * same at preview and export sizes.
 */
@RunWith(AndroidJUnit4::class)
class CutoutRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** One frame: a person in the top half of the picture. */
    private val masks: CutoutMasks by lazy {
        val file = File(context.cacheDir, "cutout-render-test.masks").apply { delete() }
        val size = CutoutMasks.SIZE
        CutoutMasks.Writer(file).apply {
            add(0, ByteArray(size * size) { if (it / size < size / 2) -1 else 0 })
            finish()
        }
        CutoutMasks.open(file)!!
    }

    /** The clip: white on the left half, black on the right. */
    private fun picture(side: Int): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            Canvas(this).drawRect(
                0f,
                0f,
                side / 2f,
                side.toFloat(),
                Paint().apply { color = Color.WHITE },
            )
        }

    private fun solid(side: Int, color: Int): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun timeline(side: Int, cutout: Cutout) = RenderTimeline(
        width = side,
        height = side,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                listOf(RenderClip("below", 0, 1_000_000, RenderSource.Image(solid(side, RED)))),
            ),
            RenderLayer(
                listOf(
                    RenderClip(
                        "person",
                        0,
                        1_000_000,
                        RenderSource.Image(picture(side)),
                        cutout = RenderCutout(cutout, masks),
                    ),
                ),
            ),
        ),
    )

    private fun frame(cutout: Cutout, side: Int = SIDE) =
        Offscreen.render(context, timeline(side, cutout), 500_000)

    private fun assertColor(expected: Int, actual: Int, where: String) {
        val near = abs(Color.red(expected) - Color.red(actual)) <= 6 &&
            abs(Color.green(expected) - Color.green(actual)) <= 6 &&
            abs(Color.blue(expected) - Color.blue(actual)) <= 6
        assertTrue("$where #%06X".format(actual and 0xFFFFFF), near)
    }

    @Test
    fun theLayerBelowShowsBehindThePerson() {
        val crisp = Cutout(Behind.LayerBelow, edgeRefine = 1f, hairDetail = 0f)
        for (cutout in listOf(crisp, crisp.copy(behind = Behind.Clear))) {
            val frame = frame(cutout)
            // The person, upright: the top half.
            assertColor(Color.WHITE, frame.getPixel(8, 8), "top left")
            assertColor(Color.BLACK, frame.getPixel(SIDE - 8, 8), "top right")
            // Below it, the red layer under the clip.
            assertColor(RED, frame.getPixel(8, SIDE - 8), "bottom left")
            assertColor(RED, frame.getPixel(SIDE - 8, SIDE - 8), "bottom right")
        }
    }

    @Test
    fun aColorOrABlurredCopyCanGoBehind() {
        val green = Color.rgb(23, 121, 90)
        val colored = frame(Cutout(Behind.Color, 1f, 0f, color = green.toLong() and 0xFFFFFFFF))
        assertColor(Color.WHITE, colored.getPixel(8, 8), "person")
        assertColor(green, colored.getPixel(8, SIDE - 8), "behind")

        val blurred = frame(Cutout(Behind.Blur, 1f, 0f))
        // Next to the line between white and black: sharp on the person, blurred behind.
        val edge = SIDE / 2 - 1
        assertColor(Color.WHITE, blurred.getPixel(edge, 8), "person by the line")
        val behind = Color.red(blurred.getPixel(edge, SIDE - 8))
        assertTrue("blurred $behind", behind in 60..230)
        // Far from the line the blur is the picture itself.
        assertColor(Color.WHITE, blurred.getPixel(4, SIDE - 8), "behind, far left")
    }

    /** Export draws at its own size from the same masks: the same picture, only larger. */
    @Test
    fun exportMatchesPreview() {
        val cutout = Cutout(Behind.Color, color = 0xFF2F4FE0)
        val preview = frame(cutout, SIDE)
        val export = Bitmap.createScaledBitmap(frame(cutout, SIDE * 4), SIDE, SIDE, true)
        val match = Offscreen.match(preview, export, 12)
        assertTrue("match $match", match > 0.97)
        assertEquals(1, masks.frames)
    }

    private companion object {
        const val SIDE = 64
        val RED = Color.rgb(229, 72, 46)
    }
}

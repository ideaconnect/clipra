package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import java.io.File
import java.io.FileNotFoundException

/**
 * Golden images for masks (#27) on the design's sunset scene: Mosaic inside a feathered oval
 * over the face (the Masks board), Mosaic outside a rounded rectangle, and a turned, masked Blur.
 * Record with `recordGoldens true`, as for the filters.
 */
@RunWith(AndroidJUnit4::class)
class MaskGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    // The board's oval: centred on the face, 34 × 44 of 186 × 330, feathered 12.
    private val faceOval = Mask(
        shape = MaskShape.Oval,
        x = 0.5f,
        y = 0.57f,
        width = 68f / 186,
        height = 88f / 330,
        feather = 12f / 186,
    )

    private val cases = mapOf(
        "mosaic-oval" to (Effect("mosaic", 1f, mapOf(Filters.MOSAIC_SIZE to 0.075f)) to faceOval),
        "mosaic-rectangle-inverted" to (
            Effect("mosaic", 1f) to faceOval.copy(
                shape = MaskShape.Rectangle,
                invert = true,
                feather = 0f,
                cornerRadius = 0.08f,
            )
            ),
        "blur-turned" to (Effect("blur", 1f) to faceOval.copy(rotation = 30f, width = 0.6f)),
    )

    @Test
    fun masksMatchTheirGoldens() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val failures = cases.mapNotNull { (name, case) ->
            val (effect, mask) = case
            val rendered = Offscreen.render(context, timeline(scene, effect, mask), 500_000)
            if (record) {
                save(File(context.filesDir, "golden-masks"), name, rendered)
                return@mapNotNull null
            }
            val golden = golden(name) ?: return@mapNotNull "$name (no golden)"
            val matched = Offscreen.match(golden, rendered, 8)
            Log.i(TAG, "$name: ${"%.4f".format(matched)} of pixels match")
            if (matched >= 0.98) null else "$name (${"%.4f".format(matched)})"
        }
        assertTrue("masks off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun outsideTheMaskThePictureIsUntouched() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val plain = Offscreen.render(context, timeline(scene, null, null), 500_000)
        val (effect, mask) = cases.getValue("mosaic-oval")
        val masked = Offscreen.render(context, timeline(scene, effect, mask), 500_000)
        // Top-left corner: far outside the oval and its feather.
        for ((x, y) in listOf(10 to 10, 200 to 60, 20 to 440)) {
            assertTrue("$x,$y", Offscreen.match(crop(plain, x, y), crop(masked, x, y), 2) > 0.99)
        }
        // The face, inside: the mosaic shows.
        val inside = Offscreen.match(crop(plain, 125, 270), crop(masked, 125, 270), 2)
        assertTrue("inside $inside", inside < 0.9)
    }

    private fun crop(bitmap: Bitmap, x: Int, y: Int) = Bitmap.createBitmap(bitmap, x, y, 20, 20)

    private fun timeline(scene: Bitmap, effect: Effect?, mask: Mask?) = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                listOf(
                    RenderClip(
                        "scene",
                        0,
                        1_000_000,
                        RenderSource.Image(scene),
                        effects = listOfNotNull(effect),
                        mask = mask,
                    ),
                ),
            ),
        ),
    )

    private fun golden(name: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/masks/$name.png").use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun save(directory: File, name: String, bitmap: Bitmap) {
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TAG = "MaskGolden"
        const val WIDTH = 270
        const val HEIGHT = 480
    }
}

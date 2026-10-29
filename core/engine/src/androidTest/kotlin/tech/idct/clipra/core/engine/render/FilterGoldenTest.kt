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
import java.io.File
import java.io.FileNotFoundException

/**
 * Golden images for the 24 filters (#25). Each filter, at full strength, draws the design's
 * sunset scene (design/canvas/Filters.dc.html); the frame must match the reviewed golden in
 * assets/golden/filters. Record new goldens with the instrumentation argument
 * `recordGoldens true`: they are written to the test app's files/golden-filters, to pull with
 * `run-as` and review against the design's thumbnails.
 */
@RunWith(AndroidJUnit4::class)
class FilterGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    @Test
    fun everyFilterMatchesItsGolden() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val failures = Filters.all.mapNotNull { type ->
            val rendered = Offscreen.render(context, timeline(scene, type), TIME_US)
            if (record) {
                save(File(context.filesDir, "golden-filters"), type, rendered)
                return@mapNotNull null
            }
            val golden = golden(type) ?: return@mapNotNull "$type (no golden)"
            val matched = Offscreen.match(golden, rendered, TOLERANCE)
            Log.i(TAG, "$type: ${"%.4f".format(matched)} of pixels match")
            if (matched >= SHARE) {
                null
            } else {
                save(context.cacheDir, "$type-actual", rendered)
                "$type (${"%.4f".format(matched)})"
            }
        }
        assertTrue("filters off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun zeroStrengthChangesNothing() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val plain = Offscreen.render(context, timeline(scene, null), TIME_US)
        for (type in listOf("noir", "mosaic", "blur", "duotone")) {
            val none = Offscreen.render(context, timeline(scene, type, strength = 0f), TIME_US)
            assertTrue(type, Offscreen.match(plain, none, 1) > 0.999)
        }
    }

    @Test
    fun strengthBlendsWithTheOriginal() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val plain = Offscreen.render(context, timeline(scene, null), TIME_US)
        val full = Offscreen.render(context, timeline(scene, "mono"), TIME_US)
        val half = Offscreen.render(context, timeline(scene, "mono", strength = 0.5f), TIME_US)
        // Half strength sits halfway between the original and the full filter.
        val x = 30
        val y = 400
        for (channel in listOf(Color::red, Color::green, Color::blue)) {
            val expected = (channel(plain.getPixel(x, y)) + channel(full.getPixel(x, y))) / 2
            assertTrue(kotlin.math.abs(channel(half.getPixel(x, y)) - expected) <= 2)
        }
    }

    private fun timeline(scene: Bitmap, type: String?, strength: Float = 1f) = RenderTimeline(
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
                        effects = listOfNotNull(type?.let { Effect(it, strength) }),
                    ),
                ),
            ),
        ),
    )

    private fun golden(type: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/filters/$type.png").use {
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
        const val TAG = "FilterGolden"
        const val WIDTH = 270
        const val HEIGHT = 480
        const val TIME_US = 500_000L

        // GPUs differ a little in rounding and in the noise of Grain and Glitch.
        const val TOLERANCE = 8
        const val SHARE = 0.98
    }
}

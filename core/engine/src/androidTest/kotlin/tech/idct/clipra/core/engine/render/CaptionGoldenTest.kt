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
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Word
import java.io.File
import java.io.FileNotFoundException

/**
 * Golden images for the caption styles (#45) on the design's sunset scene: "We finally made it
 * to Lisbon" with "Lisbon" spoken, as on the CaptionsEdit board, through the compositor as
 * preview and export draw it. Record with `recordGoldens true`, as for the filters.
 */
@RunWith(AndroidJUnit4::class)
class CaptionGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    private val words = "We finally made it to Lisbon".split(" ").mapIndexed { i, text ->
        Word(text, i * 400_000L, i * 400_000L + 350_000)
    }

    private val cases = CaptionStyle.entries.associate { style ->
        style.name.lowercase() to CaptionSpec(words, style)
    } + mapOf(
        "boldpop-top-large" to CaptionSpec(
            words,
            CaptionStyle.BoldPop,
            CaptionPosition.Top,
            scale = 1.6f,
        ),
    )

    @Test
    fun captionStylesMatchTheirGoldens() {
        val scene = GoldenScenes.sunset(WIDTH, HEIGHT)
        val failures = cases.mapNotNull { (name, caption) ->
            val rendered = Offscreen.render(context, timeline(scene, caption), 500_000)
            if (record) {
                save(File(context.filesDir, "golden-captions"), name, rendered)
                return@mapNotNull null
            }
            val golden = golden(name) ?: return@mapNotNull "$name (no golden)"
            val matched = Offscreen.match(golden, rendered, 8)
            Log.i(TAG, "$name: ${"%.4f".format(matched)} of pixels match")
            if (matched >= 0.98) null else "$name (${"%.4f".format(matched)})"
        }
        assertTrue("captions off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun aLongCaptionShrinksToTwoLines() {
        val long = "Tomorrow we are taking the old yellow tram all the way up to Alfama"
            .split(" ").mapIndexed { i, text -> Word(text, i * 300_000L, i * 300_000L + 250_000) }
        val two = CaptionRenderer.render(context, CaptionSpec(long), -1, WIDTH, HEIGHT)
        val free = CaptionRenderer.render(
            context,
            CaptionSpec(long, maxTwoLines = false),
            -1,
            WIDTH,
            HEIGHT,
        )
        // Fewer rows hold letters when the caption is held to two lines.
        assertTrue(rows(two) < rows(free))
    }

    private fun rows(bitmap: Bitmap): Int = (0 until bitmap.height).count { y ->
        (0 until bitmap.width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 }
    }

    private fun timeline(scene: Bitmap, caption: CaptionSpec) = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(listOf(RenderClip("scene", 0, 1_000_000, RenderSource.Image(scene)))),
            RenderLayer(
                listOf(
                    RenderClip(
                        "caption",
                        0,
                        1_000_000,
                        RenderSource.Drawn {
                            CaptionRenderer.render(context, caption, 5, WIDTH, HEIGHT)
                        },
                    ),
                ),
            ),
        ),
    )

    private fun golden(name: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/captions/$name.png").use {
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
        const val TAG = "CaptionGolden"
        const val WIDTH = 270
        const val HEIGHT = 480
    }
}

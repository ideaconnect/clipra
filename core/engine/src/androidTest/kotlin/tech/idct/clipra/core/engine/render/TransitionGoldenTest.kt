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
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Transitions
import java.io.File
import java.io.FileNotFoundException

/**
 * Golden images for the 24 transitions (#26) at 25, 50 and 75%, from the design's sunset to its
 * tram picture. Each frame must match the reviewed golden in assets/golden/transitions. Record
 * new goldens with the instrumentation argument `recordGoldens true` (written to the test app's
 * files/golden-transitions, to pull with `run-as`).
 */
@RunWith(AndroidJUnit4::class)
class TransitionGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    @Test
    fun everyTransitionMatchesItsGoldens() {
        val from = GoldenScenes.sunset(WIDTH, HEIGHT)
        val to = GoldenScenes.tram(WIDTH, HEIGHT)
        val failures = mutableListOf<String>()
        for (type in Transitions.all) {
            for (percent in listOf(25, 50, 75)) {
                val name = "$type-$percent"
                // The transition runs from 0.5 s to 1.5 s, linearly.
                val timeUs = 500_000L + percent * 10_000L
                val rendered = Offscreen.render(context, timeline(from, to, type), timeUs)
                if (record) {
                    save(File(context.filesDir, "golden-transitions"), name, rendered)
                    continue
                }
                val golden = golden(name)
                if (golden == null) {
                    failures += "$name (no golden)"
                    continue
                }
                val matched = Offscreen.match(golden, rendered, TOLERANCE)
                Log.i(TAG, "$name: ${"%.4f".format(matched)} of pixels match")
                if (matched < SHARE) {
                    save(context.cacheDir, "$name-actual", rendered)
                    failures += "$name (${"%.4f".format(matched)})"
                }
            }
        }
        assertTrue("transitions off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun transitionsStartOnTheOutgoingClipAndEndOnTheIncoming() {
        val from = GoldenScenes.sunset(WIDTH, HEIGHT)
        val to = GoldenScenes.tram(WIDTH, HEIGHT)
        val plainFrom = Offscreen.render(context, timeline(from, to, null), 400_000)
        val plainTo = Offscreen.render(context, timeline(from, to, null), 1_600_000)
        for (type in listOf("dissolve", "push", "iris", "cube", "ink")) {
            val start = Offscreen.render(context, timeline(from, to, type), 500_000)
            val end = Offscreen.render(context, timeline(from, to, type), 1_499_999)
            assertTrue("$type starts", Offscreen.match(plainFrom, start, 12) > 0.97)
            assertTrue("$type ends", Offscreen.match(plainTo, end, 12) > 0.97)
        }
    }

    /** The sunset from 0 to 1 s, the tram from 1 to 2 s, and [type] across the cut. */
    private fun timeline(from: Bitmap, to: Bitmap, type: String?) = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = 30,
        durationUs = 2_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                clips = listOf(
                    RenderClip("from", 0, 1_000_000, RenderSource.Image(from)),
                    RenderClip("to", 1_000_000, 1_000_000, RenderSource.Image(to)),
                ),
                transitions = listOfNotNull(
                    type?.let {
                        RenderTransition("from", "to", 500_000, 1_000_000, it, Easing.Linear)
                    },
                ),
            ),
        ),
    )

    private fun golden(name: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/transitions/$name.png").use {
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
        const val TAG = "TransitionGolden"
        const val WIDTH = 108
        const val HEIGHT = 192

        // GPUs differ a little in rounding and in the noise of Shake, Glitch and Film burn.
        const val TOLERANCE = 8
        const val SHARE = 0.98
    }
}

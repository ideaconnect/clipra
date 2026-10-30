package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Behind
import tech.idct.clipra.core.model.ChromaKey
import tech.idct.clipra.core.model.Cutout
import kotlin.math.abs
import kotlin.random.Random

/**
 * Chroma key (#51) in the compositor: a green and a blue screen, each with the noise, shadow and
 * light of a real one, key cleanly around a subject, which keeps its colours, and green light on
 * the subject's edge is taken off.
 */
@RunWith(AndroidJUnit4::class)
class ChromaKeyRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A [screen] colour with noise, darker at the bottom and lighter at the top, and a subject
     * in the middle: skin on top, a white shirt below, and a [rim] on its left edge.
     */
    private fun studio(screen: Int, rim: Int): Bitmap {
        val random = Random(51)
        val pixels = IntArray(SIDE * SIDE) { i ->
            val x = i % SIDE
            val y = i / SIDE
            when {
                x == SUBJECT.first && y in SUBJECT_ROWS -> rim

                x in SUBJECT && y in SUBJECT_ROWS -> if (y < SIDE / 2) SKIN else WHITE

                else -> {
                    val light = 1.15f - 0.35f * y / SIDE
                    val noise = { c: Int ->
                        (c * light + random.nextInt(-12, 13)).toInt().coerceIn(0, 255)
                    }
                    Color.rgb(
                        noise(Color.red(screen)),
                        noise(Color.green(screen)),
                        noise(Color.blue(screen)),
                    )
                }
            }
        }
        return Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
    }

    private fun keyed(picture: Bitmap, cutout: Cutout): Bitmap = Offscreen.render(
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
                            "studio",
                            0,
                            1_000_000,
                            RenderSource.Image(picture),
                            cutout = RenderCutout(cutout, null),
                        ),
                    ),
                ),
            ),
        ),
        500_000,
    )

    private fun argb(color: Int): Long = color.toLong() and 0xFFFFFFFF

    /** Keyed with [key], magenta behind, so what went shows. */
    private fun onMagenta(key: ChromaKey) = Cutout(Behind.Color, color = argb(MAGENTA), key = key)

    private fun near(expected: Int, actual: Int, tolerance: Int = 10) =
        abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    /** The share of the screen that went, and of the subject (rim aside) that stayed as it was. */
    private fun check(name: String, screen: Int, rim: Int) {
        val picture = studio(screen, rim)
        val frame = keyed(picture, onMagenta(ChromaKey(color = argb(screen))))
        var screenPixels = 0
        var gone = 0
        var subjectPixels = 0
        var kept = 0
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val inSubject = x in SUBJECT && y in SUBJECT_ROWS
                // Pixels next to the subject blend with it in sampling; leave them out.
                val nextTo = x in SUBJECT.first - 2..SUBJECT.last + 2 && y in SUBJECT_ROWS
                val out = frame.getPixel(x, y)
                when {
                    inSubject && x != SUBJECT.first -> {
                        subjectPixels++
                        if (near(picture.getPixel(x, y), out)) kept++
                    }

                    !inSubject && !nextTo -> {
                        screenPixels++
                        if (near(MAGENTA, out)) gone++
                    }
                }
            }
        }
        val goneShare = gone.toDouble() / screenPixels
        val keptShare = kept.toDouble() / subjectPixels
        Log.i(TAG, "$name: screen gone $goneShare, subject kept $keptShare")
        assertTrue("$name screen gone $goneShare", goneShare > 0.99)
        assertTrue("$name subject kept $keptShare", keptShare > 0.99)
    }

    @Test
    fun aGreenScreenKeysCleanly() {
        check("green", GREEN, rim = Color.rgb(190, 200, 160))
        // The green light on the subject's edge is taken off (against no spill removal), and
        // the edge stays.
        val picture = studio(GREEN, Color.rgb(190, 200, 160))
        val excess = { spill: Float ->
            val frame = keyed(picture, onMagenta(ChromaKey(spill = spill)))
            val rim = frame.getPixel(SUBJECT.first, SIDE / 2)
            Log.i(TAG, "spill $spill: rim #%06X".format(rim and 0xFFFFFF))
            assertTrue("rim kept", !near(MAGENTA, rim, 40))
            Color.green(rim) - maxOf(Color.red(rim), Color.blue(rim))
        }
        val left = excess(0f)
        val removed = excess(ChromaKey.DEFAULT_SPILL)
        assertTrue("green over the rest $left", left >= 4)
        assertTrue("green over the rest $removed, was $left", removed <= left - 3)
    }

    @Test
    fun aBlueScreenKeysCleanly() = check("blue", BLUE, rim = Color.rgb(190, 150, 160))

    /** Layer below: what is under the clip shows where the screen was. */
    @Test
    fun theLayerBelowShowsThrough() {
        val picture = studio(GREEN, SKIN)
        val red = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(229, 72, 46))
        }
        val frame = Offscreen.render(
            context,
            RenderTimeline(
                width = SIDE,
                height = SIDE,
                frameRate = 30,
                durationUs = 1_000_000,
                background = Color.BLACK,
                layers = listOf(
                    RenderLayer(
                        listOf(RenderClip("below", 0, 1_000_000, RenderSource.Image(red))),
                    ),
                    RenderLayer(
                        listOf(
                            RenderClip(
                                "studio",
                                0,
                                1_000_000,
                                RenderSource.Image(picture),
                                cutout = RenderCutout(Cutout(key = ChromaKey()), null),
                            ),
                        ),
                    ),
                ),
            ),
            500_000,
        )
        assertTrue(near(Color.rgb(229, 72, 46), frame.getPixel(4, 4)))
        assertTrue(near(SKIN, frame.getPixel(SIDE / 2, SIDE / 3)))
    }

    private companion object {
        const val TAG = "ChromaKeyRenderTest"
        const val SIDE = 64
        val SUBJECT = 22..41
        val SUBJECT_ROWS = 12..63
        val GREEN = Color.rgb(59, 179, 74)
        val BLUE = Color.rgb(30, 70, 220)
        val SKIN = Color.rgb(200, 140, 105)
        val WHITE = Color.rgb(235, 235, 230)
        val MAGENTA = Color.rgb(255, 0, 255)
    }
}

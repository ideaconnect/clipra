package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Effect
import kotlin.math.abs

/**
 * The effect stack (#29) on the GPU, through the compositor that preview and export share:
 * effects apply in the stack's order, so Mono then Sepia is a sepia picture and Sepia then Mono
 * a grey one, each as the two colour matrices one after the other give it; a disabled effect
 * draws nothing.
 */
@RunWith(AndroidJUnit4::class)
class EffectStackGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val patches = listOf(
        Color.rgb(230, 140, 50),
        Color.rgb(70, 110, 210),
        Color.rgb(60, 170, 80),
        Color.rgb(200, 60, 170),
    )

    @Test
    fun theOrderOfEffectsChangesTheResultAsExpected() {
        val monoThenSepia = render(listOf(Effect("mono", 1f), Effect("sepia", 1f)))
        val sepiaThenMono = render(listOf(Effect("sepia", 1f), Effect("mono", 1f)))
        patches.indices.forEach { i ->
            val a = pixel(monoThenSepia, i)
            val b = pixel(sepiaThenMono, i)
            assertNear("mono, sepia at $i", expected(i, "mono", "sepia"), a)
            assertNear("sepia, mono at $i", expected(i, "sepia", "mono"), b)
            // Sepia last keeps a warm tint; Mono last is grey.
            assertTrue("sepia last is warm at $i: ${a.toList()}", a[0] > a[2] + 20)
            assertTrue("mono last is grey at $i: ${b.toList()}", abs(b[0] - b[2]) <= 3)
        }
    }

    @Test
    fun aDisabledEffectDrawsNothing() {
        val withOff = render(listOf(Effect("mono", 1f), Effect("sepia", 1f, enabled = false)))
        val alone = render(listOf(Effect("mono", 1f)))
        patches.indices.forEach { i -> assertNear("patch $i", pixel(alone, i), pixel(withOff, i)) }
    }

    /** The card with [effects] as its stack, rendered. */
    private fun render(effects: List<Effect>): Bitmap {
        val timeline = RenderTimeline(
            width = WIDTH,
            height = PATCH,
            frameRate = 30,
            durationUs = 1_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "card",
                            0,
                            1_000_000,
                            RenderSource.Image(card()),
                            effects = effects,
                        ),
                    ),
                ),
            ),
        )
        return Offscreen.render(context, timeline, 500_000)
    }

    /** Patch [i] through filter [first], then [second], at full strength, on the CPU. */
    private fun expected(i: Int, first: String, second: String): IntArray {
        val c = patches[i]
        var rgb = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
        for (type in listOf(first, second)) {
            rgb = FilterRecipes.of(type)!!.matrix.apply(rgb[0], rgb[1], rgb[2])
                .map { it.coerceIn(0f, 1f) }.toFloatArray()
        }
        return IntArray(3) { (rgb[it] * 255 + 0.5f).toInt() }
    }

    private fun pixel(frame: Bitmap, i: Int): IntArray {
        val p = frame.getPixel(i * PATCH + PATCH / 2, PATCH / 2)
        return intArrayOf(Color.red(p), Color.green(p), Color.blue(p))
    }

    private fun assertNear(message: String, expected: IntArray, actual: IntArray) {
        assertTrue(
            "$message: expected ${expected.toList()}, got ${actual.toList()}",
            (0 until 3).all { abs(expected[it] - actual[it]) <= 3 },
        )
    }

    private fun card(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, PATCH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        patches.forEachIndexed { i, colour ->
            paint.color = colour
            canvas.drawRect(
                (i * PATCH).toFloat(),
                0f,
                ((i + 1) * PATCH).toFloat(),
                PATCH.toFloat(),
                paint,
            )
        }
        return bitmap
    }

    private companion object {
        const val PATCH = 160
        const val WIDTH = PATCH * 4
    }
}

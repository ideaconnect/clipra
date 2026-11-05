package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** [Offscreen.likeness], the comparison for frames with random parts (#200), on made-up frames. */
@RunWith(AndroidJUnit4::class)
class LikenessTest {
    @Test
    fun aShadeTenLevelsLighterIsTenLevelsOff() {
        val likeness = Offscreen.likeness(frame { _, _ -> 100 }, frame { _, _ -> 110 })
        assertEquals(10.0, likeness.frame, 1e-9)
        assertEquals(10.0, likeness.region, 1e-9)
    }

    @Test
    fun twoDrawsOfTheSameNoiseAreAlike() {
        val first = noisy(Random(1), amount = 40)
        val second = noisy(Random(2), amount = 40)
        assertTrue(Offscreen.match(first, second, 8) < 0.5)
        val likeness = Offscreen.likeness(first, second)
        assertTrue("$likeness", likeness.within(Randomness.Fine.limit))
    }

    @Test
    fun noiseAtHalfStrengthIsNot() {
        // On flat grey, noise of up to 40 and of up to 20 levels either way is 10 levels apart.
        val random = Random(1)
        val strong = frame { _, _ -> 128 + random.nextInt(-40, 41) }
        val weak = frame { _, _ -> 128 + random.nextInt(-20, 21) }
        val likeness = Offscreen.likeness(strong, weak)
        assertEquals(10.0, likeness.frame, 0.5)
        assertFalse("$likeness", likeness.within(Randomness.Fine.limit))
    }

    @Test
    fun aSpotInAnotherPlaceIsNot() {
        // The same colours in the same amounts, so only the grid sees the spot move.
        val likeness = Offscreen.likeness(spot(left = 10, top = 10), spot(left = 200, top = 400))
        assertEquals(0.0, likeness.frame, 1e-9)
        assertFalse("$likeness", likeness.within(Randomness.Coarse.limit))
    }

    @Test
    fun whereAColourSitsInsideACellDoesNotCount() {
        // Mirroring each 90 x 120 cell of the 3 x 4 grid keeps every cell's colours.
        val original = noisy(Random(3), 40)
        val mirrored = frame { x, y ->
            val cellX = x / CELL_WIDTH * CELL_WIDTH
            Color.red(original.getPixel(cellX + CELL_WIDTH - 1 - x % CELL_WIDTH, y))
        }
        val likeness = Offscreen.likeness(original, mirrored)
        assertEquals(0.0, likeness.frame, 1e-9)
        assertEquals(0.0, likeness.region, 1e-9)
    }

    /** A grey sky, darker at the top, with up to [amount] levels of noise either way. */
    private fun noisy(random: Random, amount: Int) = frame { _, y ->
        (60 + y * 120 / HEIGHT + random.nextInt(-amount, amount + 1)).coerceIn(0, 255)
    }

    /** A white 60 x 60 square on dark grey, its top left corner at [left], [top]. */
    private fun spot(left: Int, top: Int) = frame { x, y ->
        if (x - left in 0 until 60 && y - top in 0 until 60) 255 else 60
    }

    /** A grey frame, each pixel's level given by [level]. */
    private fun frame(level: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(WIDTH * HEIGHT) {
            val value = level(it % WIDTH, it / WIDTH)
            Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val WIDTH = 270
        const val HEIGHT = 480
        const val CELL_WIDTH = WIDTH / 3
    }
}

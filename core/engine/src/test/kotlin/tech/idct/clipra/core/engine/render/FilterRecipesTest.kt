package tech.idct.clipra.core.engine.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.model.Filters

class FilterRecipesTest {
    private fun assertColor(expected: FloatArray, actual: FloatArray) =
        assertArrayEquals(expected, actual, 1e-3f)

    @Test
    fun everyFilterHasARecipe() {
        assertEquals(Filters.all.toSet(), FilterRecipes.types)
    }

    @Test
    fun neutralAmountsChangeNothing() {
        val color = floatArrayOf(0.2f, 0.5f, 0.9f)
        for (neutral in listOf(
            ColorMatrix.saturate(1f),
            ColorMatrix.contrast(1f),
            ColorMatrix.brightness(1f),
            ColorMatrix.hueRotate(0f),
            ColorMatrix.grayscale(0f),
            ColorMatrix.sepia(0f),
        )) {
            assertColor(color, neutral.apply(color[0], color[1], color[2]))
        }
    }

    @Test
    fun grayscaleEvensTheChannels() {
        val (r, g, b) = ColorMatrix.grayscale(1f).apply(0.9f, 0.3f, 0.1f).toList()
        assertEquals(r, g, 1e-4f)
        assertEquals(g, b, 1e-4f)
        assertEquals(0.2126f * 0.9f + 0.7152f * 0.3f + 0.0722f * 0.1f, r, 1e-4f)
    }

    @Test
    fun chainsApplyInCssOrder() {
        // brightness(0.5) contrast(2): 0.5 -> 0.25 -> 0.
        val darkFirst = ColorMatrix.chain(ColorMatrix.brightness(0.5f), ColorMatrix.contrast(2f))
        assertColor(floatArrayOf(0f, 0f, 0f), darkFirst.apply(0.5f, 0.5f, 0.5f))
        // contrast(2) brightness(0.5): 0.5 -> 0.5 -> 0.25.
        val contrastFirst =
            ColorMatrix.chain(ColorMatrix.contrast(2f), ColorMatrix.brightness(0.5f))
        assertColor(floatArrayOf(0.25f, 0.25f, 0.25f), contrastFirst.apply(0.5f, 0.5f, 0.5f))
    }

    @Test
    fun theGlMatrixIsColumnMajorWithTheOffsetLast() {
        val gl = ColorMatrix.contrast(2f).toGl()
        assertEquals(2f, gl[0])
        assertEquals(-0.5f, gl[12])
        assertEquals(-0.5f, gl[13])
        assertEquals(1f, gl[15])
    }
}

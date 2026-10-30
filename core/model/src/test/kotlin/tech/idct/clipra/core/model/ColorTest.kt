package tech.idct.clipra.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.PasteColor
import tech.idct.clipra.core.model.edit.SetColor
import java.util.Locale

/** Colour (#28): `.cube` files, what a grade does to a pixel, Auto and the commands. */
class ColorTest {
    private val orange = floatArrayOf(0.9f, 0.55f, 0.2f)
    private val blue = floatArrayOf(0.2f, 0.35f, 0.85f)
    private val grey = floatArrayOf(0.5f, 0.5f, 0.5f)

    @Test
    fun aStandard33PointCubeFileReads() {
        // Red and blue swapped, written as most tools write it.
        val text = cube(33) { floatArrayOf(it[2], it[1], it[0]) }
        val lut = CubeLut.parse(text)
        assertEquals(33, lut.size)
        assertEquals("Swap", lut.title)
        assertArrayEquals(floatArrayOf(0.85f, 0.35f, 0.2f), lut.sample(blue), 1e-4f)
        // Between points too: the table is linear, so blending is exact.
        assertArrayEquals(floatArrayOf(0.2f, 0.55f, 0.9f), lut.sample(orange), 1e-4f)
        assertArrayEquals(orange, CubeLut.identity().sample(orange), 1e-5f)
    }

    @Test
    fun brokenOrUnsupportedCubeFilesAreRefused() {
        assertThrows(CubeError::class.java) { CubeLut.parse("LUT_1D_SIZE 4\n0 0 0\n") }
        assertThrows(CubeError::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n") }
        assertThrows(CubeError::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0\n") }
        assertThrows(CubeError::class.java) { CubeLut.parse("not a lut") }
        // A domain other than 0 to 1 is honoured.
        val wide = CubeLut.parse(
            "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" + cube(2, header = false) { it },
        )
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, 0.25f), wide.sample(grey), 1e-5f)
    }

    @Test
    fun aNeutralGradeChangesNothing() {
        listOf(orange, blue, grey).forEach {
            assertArrayEquals(it, ColorMath.apply(it, 0.1f, 0.9f, ColorGrade(), null), 1e-5f)
        }
        assertTrue(ColorGrade(hsl = mapOf(HueRange.Blues to HslShift())).isNeutral)
    }

    @Test
    fun eachAdjustmentDoesWhatItSays() {
        fun graded(grade: ColorGrade, c: FloatArray = grey, u: Float = 0.5f, v: Float = 0.5f) =
            ColorMath.apply(c, u, v, grade, null)
        // One stop up, as light.
        assertEquals(0.5f * 2f.pow(1 / 2.2f), graded(ColorGrade(exposure = 100f))[0], 1e-4f)
        val warm = graded(ColorGrade(temperature = 50f))
        assertTrue(warm[0] > 0.5f && warm[2] < 0.5f)
        assertTrue(graded(ColorGrade(tint = 50f))[1] < 0.5f)
        // No saturation is grey; no contrast flattens toward mid-grey.
        val flat = graded(ColorGrade(saturation = -100f), orange)
        assertEquals(flat[0], flat[2], 1e-5f)
        assertTrue(graded(ColorGrade(contrast = 50f), floatArrayOf(0.8f, 0.8f, 0.8f))[0] > 0.8f)
        // The vignette darkens the corners, not the middle.
        val vignette = ColorGrade(vignette = 100f)
        assertEquals(0.5f, graded(vignette)[0], 1e-5f)
        assertTrue(graded(vignette, u = 0f, v = 0f)[0] < 0.2f)
        assertTrue(graded(ColorGrade(vignette = -100f), u = 1f, v = 1f)[0] > 0.7f)
    }

    @Test
    fun hslMovesOnlyItsRange() {
        val oranges = ColorGrade(hsl = mapOf(HueRange.Oranges to HslShift(hue = -100f)))
        val turned = ColorMath.apply(orange, 0.5f, 0.5f, oranges, null)
        // Toward red: green falls, blue stays low.
        assertTrue(turned[1] < orange[1] - 0.1f)
        assertArrayEquals(blue, ColorMath.apply(blue, 0.5f, 0.5f, oranges, null), 1e-4f)
        assertArrayEquals(grey, ColorMath.apply(grey, 0.5f, 0.5f, oranges, null), 1e-5f)
        val darkerBlues = ColorGrade(hsl = mapOf(HueRange.Blues to HslShift(luminance = -100f)))
        assertTrue(ColorMath.luma(ColorMath.apply(blue, 0.5f, 0.5f, darkerBlues, null)) < 0.25f)
    }

    @Test
    fun aLutShowsAtItsStrength() {
        val mono = Luts.builtIn(Luts.builtInId("silver_mono"))!!
        val full = ColorMath.apply(orange, 0.5f, 0.5f, ColorGrade(lut = "x"), mono)
        assertEquals(full[0], full[2], 1e-3f)
        val half = ColorMath.apply(
            orange,
            0.5f,
            0.5f,
            ColorGrade(lut = "x", lutStrength = 0.5f),
            mono,
        )
        assertEquals((orange[0] + full[0]) / 2, half[0], 1e-4f)
        // Every built-in look exists and changes the picture.
        Luts.builtInNames.forEach { name ->
            val lut = Luts.builtIn(Luts.builtInId(name))!!
            assertTrue(name, (0 until 3).any { abs(lut.sample(orange)[it] - orange[it]) > 0.02f })
        }
        assertNull(Luts.builtIn("imported:abc"))
    }

    @Test
    fun autoBalancesExposureAndCasts() {
        val dark = AutoColor.grade(ColorStats(0.15f, 0.15f, 0.15f, 0.15f, 0.02f, 0.4f, 0.2f))
        assertTrue(dark.exposure > 30f)
        assertTrue(dark.contrast > 0f)
        val bluish = AutoColor.grade(ColorStats(0.35f, 0.45f, 0.6f, 0.45f, 0.05f, 0.9f, 0.2f))
        assertTrue(bluish.temperature > 10f)
        val balanced = AutoColor.grade(ColorStats(0.45f, 0.45f, 0.45f, 0.45f, 0.07f, 0.92f, 0.2f))
        assertEquals(0f, balanced.exposure, 1f)
        assertEquals(0f, balanced.temperature, 0f)
        assertEquals(0f, balanced.tint, 0f)

        val pixels = IntArray(100) { if (it < 50) 0xFF102030.toInt() else 0xFF203040.toInt() }
        val stats = ColorStats.of(pixels)
        assertTrue(stats.blue > stats.red)
        assertTrue(stats.low < stats.high)
    }

    @Test
    fun theGradeIsSetPastedAndSaved() {
        val clip = { id: String, type: ClipType ->
            Clip(id, type, 0, 1_000_000, source = MediaRef(id, 0, 1_000_000))
        }
        val project = Project(
            id = "p",
            name = "Colour",
            layers = listOf(
                Layer("L1", clips = listOf(clip("a", ClipType.Video), clip("b", ClipType.Photo))),
                Layer("L2", clips = listOf(clip("music", ClipType.Audio))),
                Layer("L3", locked = true, clips = listOf(clip("c", ClipType.Video))),
            ),
        )
        val grade = ColorGrade(
            exposure = 10f,
            hsl = mapOf(HueRange.Oranges to HslShift(hue = -8f)),
            lut = Luts.builtInId("warm_film"),
            lutStrength = 0.6f,
        )
        val graded = SetColor("a", grade).apply(project)
        assertEquals(grade, graded.findClip("a")!!.second.color)
        // A neutral grade takes it away; text and sound have no colour.
        assertNull(SetColor("a", ColorGrade()).apply(graded).findClip("a")!!.second.color)
        assertThrows(EditRejected::class.java) { SetColor("music", grade).apply(project) }

        assertEquals(1, PasteColor.targets(graded, "a"))
        val pasted = PasteColor("a").apply(graded)
        assertEquals(grade, pasted.findClip("b")!!.second.color)
        assertNull(pasted.findClip("music")!!.second.color)
        assertNull(pasted.findClip("c")!!.second.color)

        val json = ProjectJson()
        assertEquals(pasted, json.decode(json.encode(pasted)))
    }

    /** A `.cube` file of [size] points a side, from [map]. */
    private fun cube(size: Int, header: Boolean = true, map: (FloatArray) -> FloatArray): String {
        val n = (size - 1).toFloat()
        return buildString {
            if (header) {
                append("# Created by a test\nTITLE \"Swap\"\n\nLUT_3D_SIZE $size\n")
            } else {
                append("LUT_3D_SIZE $size\n")
            }
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val out = map(floatArrayOf(r / n, g / n, b / n))
                        append(
                            String.format(Locale.ROOT, "%.6f %.6f %.6f\n", out[0], out[1], out[2]),
                        )
                    }
                }
            }
        }
    }

    private fun Float.pow(x: Float) = Math.pow(toDouble(), x.toDouble()).toFloat()

    private fun abs(x: Float) = kotlin.math.abs(x)
}

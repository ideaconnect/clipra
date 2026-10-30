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
import tech.idct.clipra.core.engine.media.LutLibrary
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.ColorMath
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.HslShift
import tech.idct.clipra.core.model.HueRange
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Colour (#28) on the GPU, through the compositor that preview and export share: every patch
 * of a test card comes out as `ColorMath` grades it, for the adjustments, the HSL ranges, a
 * built-in look and an imported standard 33-point `.cube` file.
 */
@RunWith(AndroidJUnit4::class)
class ColorGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val patches = listOf(
        Color.rgb(230, 140, 50),
        Color.rgb(70, 110, 210),
        Color.rgb(128, 128, 128),
        Color.rgb(224, 172, 140),
        Color.rgb(60, 170, 80),
        Color.rgb(30, 25, 40),
        Color.rgb(235, 230, 220),
        Color.rgb(200, 60, 170),
    )

    @Test
    fun adjustmentsRenderAsTheyAreDefined() {
        check(
            ColorGrade(
                exposure = 30f,
                contrast = 20f,
                saturation = -25f,
                temperature = 40f,
                tint = -20f,
                vignette = 50f,
            ),
            tolerance = 3,
        )
    }

    @Test
    fun hslRangesRenderAsTheyAreDefined() {
        check(
            ColorGrade(
                hsl = mapOf(
                    HueRange.Oranges to HslShift(hue = -60f, saturation = 30f),
                    HueRange.Blues to HslShift(saturation = -50f, luminance = -40f),
                    HueRange.Magentas to HslShift(hue = 40f),
                ),
            ),
            tolerance = 3,
        )
    }

    @Test
    fun aBuiltInLookRenders() {
        check(ColorGrade(lut = Luts.builtInId("teal_orange")), tolerance = 4)
    }

    @Test
    fun aStandard33PointCubeFileImportsAndRendersCorrectly() {
        // A table that mixes the channels and bends them, as a grading tool would export it.
        val text = buildString {
            append("# Made for ColorGoldenTest\nTITLE \"Mix\"\nLUT_3D_SIZE 33\n")
            for (b in 0 until 33) {
                for (g in 0 until 33) {
                    for (r in 0 until 33) {
                        val red = (g / 32f).pow(0.8f)
                        val green = b / 32f
                        val blue = 0.25f + 0.5f * r / 32f
                        append(String.format(Locale.ROOT, "%.6f %.6f %.6f\n", red, green, blue))
                    }
                }
            }
        }
        val library = LutLibrary(File(context.cacheDir, "luts-test").apply { deleteRecursively() })
        val entry = library.import("Mix.cube", text)
        assertEquals("Mix.cube", library.list().single().name)
        check(ColorGrade(lut = entry.id, lutStrength = 0.8f), tolerance = 4, library = library)
    }

    /** Renders the card graded by [grade] and compares every patch with `ColorMath`. */
    private fun check(grade: ColorGrade, tolerance: Int, library: LutLibrary? = null) {
        val card = card()
        val project = Project(
            id = "colour",
            name = "Colour",
            format = Format(),
            media = listOf(MediaAsset("card", "file://card", MediaKind.Photo)),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "card",
                            ClipType.Photo,
                            0,
                            1_000_000,
                            MediaRef("card", 0, 0),
                            color = grade,
                        ),
                    ),
                ),
            ),
        )
        val resolver = object : MediaResolver {
            override fun source(asset: MediaAsset): String? = null

            override fun image(asset: MediaAsset): Bitmap = card

            override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap? =
                null

            override fun lut(id: String): CubeLut? = Luts.builtIn(id) ?: library?.lut(id)
        }
        val timeline = ProjectRender.timeline(project, resolver, width = WIDTH, height = HEIGHT)
        assertTrue("the LUT resolves", grade.lut == null || timeline.layers[0].clips[0].lut != null)
        val frame = Offscreen.render(context, timeline, 500_000)
        val lut = grade.lut?.let(resolver::lut)
        patches.forEachIndexed { i, patch ->
            val x = i % COLUMNS * PATCH + PATCH / 2
            val y = i / COLUMNS * PATCH + PATCH / 2
            val rgb = floatArrayOf(
                Color.red(patch) / 255f,
                Color.green(patch) / 255f,
                Color.blue(patch) / 255f,
            )
            val expected = ColorMath.apply(
                rgb,
                x / WIDTH.toFloat(),
                y / HEIGHT.toFloat(),
                grade,
                lut,
            )
            val actual = frame.getPixel(x, y)
            val got = intArrayOf(Color.red(actual), Color.green(actual), Color.blue(actual))
            val want = IntArray(3) { (expected[it] * 255 + 0.5f).toInt() }
            assertTrue(
                "patch $i: expected ${want.toList()}, got ${got.toList()}",
                (0 until 3).all { abs(got[it] - want[it]) <= tolerance },
            )
        }
    }

    /** Eight solid patches, four across and two down, the size of the frame. */
    private fun card(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        patches.forEachIndexed { i, colour ->
            paint.color = colour
            val left = (i % COLUMNS * PATCH).toFloat()
            val top = (i / COLUMNS * PATCH).toFloat()
            canvas.drawRect(left, top, left + PATCH, top + PATCH, paint)
        }
        return bitmap
    }

    private companion object {
        const val PATCH = 160
        const val COLUMNS = 4
        const val WIDTH = PATCH * COLUMNS
        const val HEIGHT = PATCH * 2
    }
}

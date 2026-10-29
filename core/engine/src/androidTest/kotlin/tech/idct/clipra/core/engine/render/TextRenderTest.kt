package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.TextSpec
import java.io.File

/** Text clips (#31): Polish letters in every bundled font, and sharp text at 4K. */
@RunWith(AndroidJUnit4::class)
class TextRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyFontHasThePolishLetters() {
        for (family in TextRenderer.families) {
            val paint = Paint().apply { typeface = TextRenderer.typeface(context, family) }
            for (letter in "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ") {
                assertTrue("$family lacks $letter", paint.hasGlyph(letter.toString()))
            }
        }
    }

    @Test
    fun polishTextDrawsItsLetters() {
        val spec = TextSpec("Zażółć gęślą jaźń", font = TextRenderer.BRICOLAGE, weight = 800)
        val text = TextRenderer.render(context, spec, card = true, width = 1080, height = 1920)
        // White letters on the black card: a fair share of the middle band is lit.
        val lit = (0 until 1080).count { x -> Color.red(text.getPixel(x, 960)) > 200 }
        assertTrue("lit $lit", lit > 40)
    }

    @Test
    fun textIsDrawnAtFourKAndStaysSharp() {
        val project = Project(
            id = "p",
            name = "4K",
            format = Format(ratio = AspectRatio.Landscape16x9, resolution = Resolution.UHD),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "card",
                            ClipType.TextCard,
                            0,
                            3_000_000,
                            text = TextSpec("IIII", size = 0.3f, weight = 400),
                        ),
                    ),
                ),
            ),
        )
        val resolver = ProjectMediaResolver(
            context,
            MediaCache(File(context.cacheDir, "text-test")),
            forExport = true,
            maxImageSide = 4096,
        )
        val timeline = ProjectRender.timeline(project, resolver)
        val card = timeline.layers[0].clips.single().source as RenderSource.Image
        assertEquals(3840, card.bitmap.width)
        assertEquals(2160, card.bitmap.height)
        // Each stroke edge is at most two pixels of antialiasing.
        val strokes = edges(card.bitmap, 1080)
        assertTrue("no strokes", strokes > 0)
        assertTrue(
            "soft ${softPixels(card.bitmap, 1080)} for $strokes edges",
            softPixels(card.bitmap, 1080) <= strokes * 2,
        )
    }

    /** Pixels on row [y] between dark and light. */
    private fun softPixels(bitmap: Bitmap, y: Int) = (0 until bitmap.width).count { x ->
        Color.red(bitmap.getPixel(x, y)) in 40..215
    }

    /** Dark-to-light or light-to-dark changes along row [y]. */
    private fun edges(bitmap: Bitmap, y: Int): Int {
        var count = 0
        var light = Color.red(bitmap.getPixel(0, y)) > 128
        for (x in 1 until bitmap.width) {
            val now = Color.red(bitmap.getPixel(x, y)) > 128
            if (now != light) count++
            light = now
        }
        return count
    }
}

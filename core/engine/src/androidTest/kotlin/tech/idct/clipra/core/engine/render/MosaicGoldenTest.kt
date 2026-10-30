package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.edit.AddToMosaic
import java.io.File

/**
 * Mosaic layouts on the GPU (#17): a 2×2 project with four clips renders four cells through the
 * same compositor as preview and export, each picture filling its cell and nothing spilling
 * into the next. A moved and enlarged clip is cut at its cell's edges, as a Canvas reference
 * of the rule draws it.
 */
@RunWith(AndroidJUnit4::class)
class MosaicGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    @Test
    fun aTwoByTwoProjectShowsFourCells() {
        // Four wide pictures, one per cell, imported as the editor imports them.
        var project =
            Project(id = "mosaic", name = "Mosaic", format = Format(layout = Layout.Grid2x2))
        colours.indices.forEach { i ->
            project = project.copy(
                media = project.media + MediaAsset("m$i", "file://m$i", MediaKind.Photo),
            )
            val clip = Clip("c$i", ClipType.Photo, 0, 1_000_000, MediaRef("m$i", 0, 0))
            project = AddToMosaic(clip, "L${i + 2}").apply(project)
        }
        val resolver = object : MediaResolver {
            override fun source(asset: MediaAsset): String? = null

            override fun image(asset: MediaAsset): Bitmap =
                solid(colours[asset.id.removePrefix("m").toInt()], 320, 240)

            override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap? =
                null
        }
        val timeline = ProjectRender.timeline(project, resolver, width = 540, height = 960)
        val frame = Offscreen.render(context, timeline, 500_000)

        val expected = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        Canvas(expected).apply {
            colours.forEachIndexed { cell, colour ->
                val rect = Cells.rect(project.format, cell)!!
                drawRect(
                    rect.left * 540,
                    rect.top * 960,
                    (rect.left + rect.width) * 540,
                    (rect.top + rect.height) * 960,
                    Paint().apply { color = colour },
                )
            }
        }
        assertSimilar("four cells", expected, frame, 8, 0.995)
    }

    @Test
    fun aMovedClipIsCutAtItsCell() {
        val pattern = quadrants()
        // Cell 1 of a 2×2 9:16 frame: the top right quarter.
        val format = Format(layout = Layout.Grid2x2)
        val cell = Cells.rect(format, 1)!!
        val transform = Transform(x = 0.2f, y = 0.15f, scale = 1.4f, rotation = 20f)
        val timeline = RenderTimeline(
            width = 540,
            height = 960,
            frameRate = 30,
            durationUs = 1_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "clip",
                            0,
                            1_000_000,
                            RenderSource.Image(pattern),
                            transform = transform,
                            cell = cell,
                        ),
                    ),
                ),
            ),
        )
        val frame = Offscreen.render(context, timeline, 500_000)

        // The rule: laid out as if the cell were the frame, then cut to the cell.
        val left = cell.left * 540
        val top = cell.top * 960
        val width = cell.width * 540
        val height = cell.height * 960
        val expected = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        Canvas(expected).apply {
            drawColor(Color.BLACK)
            clipRect(left, top, left + width, top + height)
            val scale = maxOf(width / pattern.width, height / pattern.height) * transform.scale
            val matrix = Matrix().apply {
                postTranslate(-pattern.width / 2f, -pattern.height / 2f)
                postScale(scale, scale)
                postRotate(transform.rotation)
                postTranslate(
                    left + width * (0.5f + transform.x),
                    top + height * (0.5f + transform.y),
                )
            }
            drawBitmap(pattern, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        assertSimilar("cut to the cell", expected, frame, 16, 0.995)
        // Nothing outside the cell: the other three quarters are background.
        val outside = listOf(10 to 10, 260 to 470, 10 to 950, 530 to 950, 280 to 490)
        outside.forEach { (x, y) ->
            val pixel = frame.getPixel(x, y)
            assertTrue(
                "($x, $y) shows the clip",
                Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 24,
            )
        }
    }

    private fun solid(colour: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    /** 320 × 240 in four colours: red, green / blue, white. */
    private fun quadrants(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(
            Triple(0, 0, Color.RED),
            Triple(160, 0, Color.GREEN),
            Triple(0, 120, Color.BLUE),
            Triple(160, 120, Color.WHITE),
        ).forEach { (left, top, colour) ->
            paint.color = colour
            canvas.drawRect(left.toFloat(), top.toFloat(), left + 160f, top + 120f, paint)
        }
        return bitmap
    }

    private fun assertSimilar(
        what: String,
        expected: Bitmap,
        actual: Bitmap,
        tolerance: Int,
        share: Double,
    ) {
        val matched = Offscreen.match(expected, actual, tolerance)
        Log.i(TAG, "$what: ${"%.4f".format(matched)} of pixels match")
        if (matched < share) {
            File(context.cacheDir, "mosaic-$what-expected.png").outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.cacheDir, "mosaic-$what-actual.png").outputStream().use {
                actual.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue("$what: only ${"%.4f".format(matched)} of pixels match", matched >= share)
    }

    private companion object {
        const val TAG = "MosaicGolden"
    }
}

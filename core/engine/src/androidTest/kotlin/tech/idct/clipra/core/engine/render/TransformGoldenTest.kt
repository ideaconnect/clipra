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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Transform
import java.io.File

/**
 * Golden-image tests for clip transforms (#19). The compositor renders offscreen, straight into a
 * framebuffer, and each frame is compared with a reference drawn by android.graphics.Canvas
 * from the transform's definition (a separate implementation of the same rules), and preview
 * size with export size. Preview and export use this same compositor, so matching at both sizes
 * means they render transforms identically.
 */
@RunWith(AndroidJUnit4::class)
class TransformGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var video: File

    // Moved, scaled, turned, mirrored and cropped on every side.
    private val transform = Transform(
        x = 0.12f,
        y = -0.08f,
        scale = 0.55f,
        rotation = 30f,
        cropLeft = 0.1f,
        cropTop = 0.05f,
        cropRight = 0.2f,
        cropBottom = 0.15f,
        flipHorizontal = true,
        fit = Fit.Fit,
    )

    @Before
    fun copyMedia() {
        video = File(context.cacheDir, "golden-quadrants.mp4")
        instrumentation.context.assets.open("golden/quadrants.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
    }

    @Test
    fun untransformedFillsTheFrameUpright() {
        val pattern = quadrants()
        val rendered = render(single(RenderSource.Image(pattern), Transform()), 540, 960)
        assertSimilar("fill", reference(pattern, Transform(), 540, 960), rendered, 16, 0.995)
    }

    @Test
    fun photoMatchesTheReference() {
        val pattern = quadrants()
        val rendered = render(single(RenderSource.Image(pattern), transform), 1080, 1920)
        val expected = reference(pattern, transform, 1080, 1920)
        assertSimilar("photo", expected, rendered, 16, 0.995)
    }

    @Test
    fun videoMatchesTheReference() {
        val rendered = render(single(RenderSource.Video(video.path), transform), 1080, 1920)
        val expected = reference(quadrants(), transform, 1080, 1920)
        // Video is compressed and subsampled, so colours and edges differ a little more.
        assertSimilar("video", expected, rendered, 48, 0.985)
    }

    @Test
    fun fillAndFlipVerticallyMatchTheReference() {
        val other = Transform(scale = 1.3f, rotation = -100f, flipVertical = true, y = 0.1f)
        val pattern = quadrants()
        val rendered = render(single(RenderSource.Image(pattern), other), 1080, 1920)
        assertSimilar("fill", reference(pattern, other, 1080, 1920), rendered, 16, 0.995)
    }

    @Test
    fun aWrongFlipIsCaught() {
        // The comparison must be strict enough to see a mirrored picture.
        val pattern = quadrants()
        val rendered = render(single(RenderSource.Image(pattern), transform), 1080, 1920)
        val mirrored = reference(pattern, transform.copy(flipHorizontal = false), 1080, 1920)
        assertTrue(Offscreen.match(mirrored, rendered, 16) < 0.98)
    }

    @Test
    fun previewSizeMatchesExportSize() {
        val pattern = quadrants()
        val timeline = { width: Int, height: Int ->
            single(RenderSource.Image(pattern), transform).copy(width = width, height = height)
        }
        val export = render(timeline(1080, 1920), 1080, 1920)
        val preview = render(timeline(540, 960), 540, 960)
        val scaled = Bitmap.createScaledBitmap(export, 540, 960, true)
        assertSimilar("preview vs export", scaled, preview, 24, 0.99)
    }

    /** One clip over black, from 0 to 1 s. */
    private fun single(source: RenderSource, transform: Transform) = RenderTimeline(
        width = 1080,
        height = 1920,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(listOf(RenderClip("clip", 0, 1_000_000, source, transform = transform))),
        ),
    )

    /** The frame at 0.5 s at [width] × [height]. */
    private fun render(timeline: RenderTimeline, width: Int, height: Int): Bitmap =
        Offscreen.render(context, timeline.copy(width = width, height = height), 500_000)

    /**
     * The expected frame: [pattern] placed by android.graphics.Matrix following Transform's
     * definition. Scale 1 fits ([Fit.Fit]) or covers ([Fit.Fill]); the picture is mirrored,
     * scaled, turned clockwise and moved, in that order, then cropped in source pixels.
     */
    private fun reference(pattern: Bitmap, transform: Transform, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val sourceWidth = pattern.width.toFloat()
        val sourceHeight = pattern.height.toFloat()
        val horizontal = width / sourceWidth
        val vertical = height / sourceHeight
        val base = if (transform.fit == Fit.Fit) {
            minOf(horizontal, vertical)
        } else {
            maxOf(horizontal, vertical)
        }
        val scale = base * transform.scale
        val matrix = Matrix().apply {
            postTranslate(-sourceWidth / 2, -sourceHeight / 2)
            postScale(
                if (transform.flipHorizontal) -scale else scale,
                if (transform.flipVertical) -scale else scale,
            )
            postRotate(transform.rotation)
            postTranslate(width * (0.5f + transform.x), height * (0.5f + transform.y))
        }
        canvas.concat(matrix)
        canvas.clipRect(
            transform.cropLeft * sourceWidth,
            transform.cropTop * sourceHeight,
            (1 - transform.cropRight) * sourceWidth,
            (1 - transform.cropBottom) * sourceHeight,
        )
        canvas.drawBitmap(pattern, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /** 320 × 240 in four colours: red, green / blue, white (the test video's frames). */
    private fun quadrants(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(
            Triple(0, 0, Color.RED),
            Triple(160, 0, Color.GREEN),
            Triple(0, 120, Color.BLUE),
            Triple(160, 120, Color.WHITE),
        ).forEach { (left, top, color) ->
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), left + 160f, top + 120f, paint)
        }
        return bitmap
    }

    /** At least [share] of the pixels within [tolerance] on every channel. */
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
            File(context.cacheDir, "golden-$what-expected.png").outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.cacheDir, "golden-$what-actual.png").outputStream().use {
                actual.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue("$what: only ${"%.4f".format(matched)} of pixels match", matched >= share)
    }

    private companion object {
        const val TAG = "TransformGolden"
    }
}

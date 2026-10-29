package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import java.io.File
import java.io.FileNotFoundException

/**
 * Golden images for the 24 filters (#25). Each filter, at full strength, draws the design's
 * sunset scene (design/canvas/Filters.dc.html); the frame must match the reviewed golden in
 * assets/golden/filters. Record new goldens with the instrumentation argument
 * `recordGoldens true`: they are written to the test app's files/golden-filters, to pull with
 * `run-as` and review against the design's thumbnails.
 */
@RunWith(AndroidJUnit4::class)
class FilterGoldenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"

    @Test
    fun everyFilterMatchesItsGolden() {
        val scene = sunset()
        val failures = Filters.all.mapNotNull { type ->
            val rendered = Offscreen.render(context, timeline(scene, type), TIME_US)
            if (record) {
                save(File(context.filesDir, "golden-filters"), type, rendered)
                return@mapNotNull null
            }
            val golden = golden(type) ?: return@mapNotNull "$type (no golden)"
            val matched = Offscreen.match(golden, rendered, TOLERANCE)
            Log.i(TAG, "$type: ${"%.4f".format(matched)} of pixels match")
            if (matched >= SHARE) {
                null
            } else {
                save(context.cacheDir, "$type-actual", rendered)
                "$type (${"%.4f".format(matched)})"
            }
        }
        assertTrue("filters off their goldens: $failures", failures.isEmpty())
    }

    @Test
    fun zeroStrengthChangesNothing() {
        val scene = sunset()
        val plain = Offscreen.render(context, timeline(scene, null), TIME_US)
        for (type in listOf("noir", "mosaic", "blur", "duotone")) {
            val none = Offscreen.render(context, timeline(scene, type, strength = 0f), TIME_US)
            assertTrue(type, Offscreen.match(plain, none, 1) > 0.999)
        }
    }

    @Test
    fun strengthBlendsWithTheOriginal() {
        val scene = sunset()
        val plain = Offscreen.render(context, timeline(scene, null), TIME_US)
        val full = Offscreen.render(context, timeline(scene, "mono"), TIME_US)
        val half = Offscreen.render(context, timeline(scene, "mono", strength = 0.5f), TIME_US)
        // Half strength sits halfway between the original and the full filter.
        val x = 30
        val y = 400
        for (channel in listOf(Color::red, Color::green, Color::blue)) {
            val expected = (channel(plain.getPixel(x, y)) + channel(full.getPixel(x, y))) / 2
            assertTrue(kotlin.math.abs(channel(half.getPixel(x, y)) - expected) <= 2)
        }
    }

    private fun timeline(scene: Bitmap, type: String?, strength: Float = 1f) = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = 30,
        durationUs = 1_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                listOf(
                    RenderClip(
                        "scene",
                        0,
                        1_000_000,
                        RenderSource.Image(scene),
                        effects = listOfNotNull(type?.let { Effect(it, strength) }),
                    ),
                ),
            ),
        ),
    )

    private fun golden(type: String): Bitmap? = try {
        instrumentation.context.assets.open("golden/filters/$type.png").use {
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

    /** The design's preview scene: a sunset sky, the sun, two hills and a person. */
    private fun sunset(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(
                0xFF23305C.toInt(),
                0xFF6B4D7A.toInt(),
                0xFFD9785A.toInt(),
                0xFFF3B064.toInt(),
            ),
            floatArrayOf(0f, 0.36f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        fun oval(left: Float, top: Float, right: Float, bottom: Float, color: Long) {
            paint.color = color.toInt()
            canvas.drawOval(RectF(left * w, top * h, right * w, bottom * h), paint)
        }
        val sun = 0.24f * w
        paint.color = 0xFFFFE6A8.toInt()
        canvas.drawOval(RectF(0.62f * w, 0.30f * h, 0.62f * w + sun, 0.30f * h + sun), paint)
        oval(-0.30f, 0.64f, 0.65f, 1.14f, 0xFF4A3355)
        oval(0.25f, 0.72f, 1.40f, 1.22f, 0xFF22192E)
        paint.color = 0xFF2F4A7A.toInt()
        canvas.drawRoundRect(
            RectF(0.21f * w, 0.74f * h, 0.79f * w, 1.06f * h),
            0.28f * w,
            0.15f * h,
            paint,
        )
        paint.color = 0xFFB97A5B.toInt()
        canvas.drawRect(0.45f * w, 0.66f * h, 0.55f * w, 0.76f * h, paint)
        oval(0.37f, 0.50f, 0.63f, 0.68f, 0xFFC98B6B)
        paint.color = 0xFF2B1E1A.toInt()
        canvas.drawRoundRect(
            RectF(0.36f * w, 0.485f * h, 0.64f * w, 0.57f * h),
            0.14f * w,
            0.04f * h,
            paint,
        )
        return bitmap
    }

    private companion object {
        const val TAG = "FilterGolden"
        const val WIDTH = 270
        const val HEIGHT = 480
        const val TIME_US = 500_000L

        // GPUs differ a little in rounding and in the noise of Grain and Glitch.
        const val TOLERANCE = 8
        const val SHARE = 0.98
    }
}

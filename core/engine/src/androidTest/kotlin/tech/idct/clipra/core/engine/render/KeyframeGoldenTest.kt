package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Keyframes
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.at
import java.io.File

/**
 * Keyframes on the GPU (#21): a keyframed clip renders, at any moment, exactly as a still clip
 * holding the interpolated values, and the same at preview size as at export size. Preview and
 * export draw with this one compositor, so keyframed values move identically in both.
 */
@RunWith(AndroidJUnit4::class)
class KeyframeGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pattern = quadrants()

    // From the left, small and upright, to the right, full size and turned, fading in.
    private val transforms = listOf(
        Keyframe(0, Transform(x = -0.25f, scale = 0.5f), Easing.EaseInOut),
        Keyframe(
            1_000_000,
            Transform(x = 0.25f, y = 0.1f, scale = 1f, rotation = 90f),
            Easing.Linear,
        ),
    )
    private val opacities = listOf(Keyframe(0, 0.4f, Easing.Linear), Keyframe(1_000_000, 1f))

    @Test
    fun eachFrameIsTheInterpolatedClip() {
        // The clip's last frame is just before its end.
        listOf(0L, 250_000L, 500_000L, 800_000L, 999_000L).forEach { time ->
            val animated = render(keyframed(), time, 540, 960)
            val still = render(
                single(
                    Keyframed(Transform(), transforms).at(time, Keyframes::transform),
                    Keyframed(1f, opacities).at(time, Keyframes::opacity),
                ),
                time,
                540,
                960,
            )
            assertSimilar("at $time", still, animated, 4, 0.999)
        }
    }

    @Test
    fun previewSizeMatchesExportSize() {
        val export = render(keyframed(), 600_000, 1080, 1920)
        val preview = render(keyframed(), 600_000, 540, 960)
        assertSimilar(
            "preview vs export",
            Bitmap.createScaledBitmap(export, 540, 960, true),
            preview,
            24,
            0.99,
        )
    }

    @Test
    fun theMovementIsSeen() {
        // The two ends differ, so the comparison above can tell frames apart.
        val start = render(keyframed(), 0, 540, 960)
        val end = render(keyframed(), 999_000, 540, 960)
        assertTrue(Offscreen.match(start, end, 16) < 0.9)
    }

    private fun keyframed() = single(Transform(), 1f).let { timeline ->
        val clip = timeline.layers.single().clips.single()
        timeline.copy(
            layers = listOf(
                RenderLayer(
                    listOf(
                        clip.copy(transformKeyframes = transforms, opacityKeyframes = opacities),
                    ),
                ),
            ),
        )
    }

    /** One clip over black, from 0 to 1 s. */
    private fun single(transform: Transform, opacity: Float) = RenderTimeline(
        width = 1080,
        height = 1920,
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
                        opacity = opacity,
                    ),
                ),
            ),
        ),
    )

    private fun render(timeline: RenderTimeline, timeUs: Long, width: Int, height: Int): Bitmap =
        Offscreen.render(context, timeline.copy(width = width, height = height), timeUs)

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
            File(context.cacheDir, "keyframe-$what-expected.png").outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.cacheDir, "keyframe-$what-actual.png").outputStream().use {
                actual.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue("$what: only ${"%.4f".format(matched)} of pixels match", matched >= share)
    }

    private companion object {
        const val TAG = "KeyframeGolden"
    }
}

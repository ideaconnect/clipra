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
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import java.io.File

/**
 * Animations on the GPU (#33), through the compositor that preview and export share: an
 * animated clip renders, at any moment, exactly as a still clip holding the animation's values;
 * text in renders as the text renderer draws it at that moment; and both look the same at
 * preview size as at export size. So animations play identically in preview and export.
 */
@RunWith(AndroidJUnit4::class)
class AnimationGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pattern = quadrants()
    private val animation = Animation(enter = "zoom_in", exit = "slide_down", loop = "pulse")

    @Test
    fun eachFrameOfAnAnimatedClipIsTheClipWhereItsAnimationPutsIt() {
        listOf(0L, 200_000L, 450_000L, 1_000_000L, 1_700_000L, 1_966_000L).forEach { time ->
            val motion = Animations.motion(animation, Transform(), time, DURATION)
            val animated = render(clip(animation = animation), time, 540, 960)
            val still = render(clip(motion.transform, motion.opacity), time, 540, 960)
            assertSimilar("at $time", still, animated, 4, 0.999)
        }
        // The animation moves things, so the comparison can tell frames apart.
        val start = render(clip(animation = animation), 100_000, 540, 960)
        val middle = render(clip(animation = animation), 1_000_000, 540, 960)
        assertTrue(Offscreen.match(start, middle, 16) < 0.9)
    }

    @Test
    fun anAnimatedClipLooksTheSameAtPreviewAndExportSize() {
        listOf(300_000L, 1_800_000L).forEach { time ->
            val export = render(clip(animation = animation), time, 1080, 1920)
            val preview = render(clip(animation = animation), time, 540, 960)
            assertSimilar(
                "preview vs export at $time",
                Bitmap.createScaledBitmap(export, 540, 960, true),
                preview,
                24,
                0.99,
            )
        }
    }

    @Test
    fun textComesInAsItsRendererDrawsIt() {
        val spec =
            TextSpec(
                "Day two",
                size = 0.1f,
                color = TextColors.WHITE,
                background = TextColors.BLACK,
            )
        val project = Project(
            id = "text",
            name = "Text",
            format = Format(),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "card",
                            ClipType.TextCard,
                            0,
                            DURATION,
                            text = spec,
                            animation = Animation(enter = "typewriter", exit = "fade"),
                        ),
                    ),
                ),
            ),
        )
        val media = ProjectMediaResolver(
            context,
            MediaCache(File(context.cacheDir, "animation-test")),
            forExport = true,
            maxImageSide = 2048,
        )

        // Frames of the 30 fps grid inside the way in and the way out, and one between.
        fun frame(k: Long) = k * 1_000_000L / 30
        listOf(frame(5), frame(12), frame(30), frame(55)).forEach { time ->
            val frames = listOf(540 to 960, 1080 to 1920).map { (w, h) ->
                Offscreen.render(context, ProjectRender.timeline(project, media, w, h), time)
            }
            val expected = Animations.textMotion(
                project.layers[0].clips[0].animation,
                time,
                DURATION,
            )
                .let { TextRenderer.render(context, spec, card = true, 540, 960, it) }
            assertSimilar("text at $time", expected, frames[0], 8, 0.995)
            assertSimilar(
                "text preview vs export at $time",
                Bitmap.createScaledBitmap(frames[1], 540, 960, true),
                frames[0],
                40,
                0.99,
            )
        }
        // Typing shows more letters as it goes.
        val early = Offscreen.render(
            context,
            ProjectRender.timeline(project, media, 540, 960),
            frame(5),
        )
        val later = Offscreen.render(
            context,
            ProjectRender.timeline(project, media, 540, 960),
            frame(15),
        )
        assertTrue(lit(early) < lit(later))
    }

    private fun lit(bitmap: Bitmap): Int {
        var n = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) if (Color.red(bitmap.getPixel(x, y)) > 128) n++
        }
        return n
    }

    /** The pattern over black for 2 s, held still at [transform] or animated. */
    private fun clip(
        transform: Transform = Transform(),
        opacity: Float = 1f,
        animation: Animation? = null,
    ) = RenderTimeline(
        width = 1080,
        height = 1920,
        frameRate = 30,
        durationUs = DURATION,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                listOf(
                    RenderClip(
                        "clip",
                        0,
                        DURATION,
                        RenderSource.Image(pattern),
                        transform = transform,
                        opacity = opacity,
                        animation = animation,
                    ),
                ),
            ),
        ),
    )

    private fun render(timeline: RenderTimeline, timeUs: Long, width: Int, height: Int): Bitmap =
        Offscreen.render(context, timeline.copy(width = width, height = height), timeUs)

    private fun assertSimilar(
        message: String,
        expected: Bitmap,
        actual: Bitmap,
        tolerance: Int,
        share: Double,
    ) {
        val matched = Offscreen.match(expected, actual, tolerance)
        assertTrue("$message: ${"%.4f".format(matched)} of pixels match", matched >= share)
    }

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
        ).forEach { (x, y, colour) ->
            paint.color = colour
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 160f, y + 120f, paint)
        }
        return bitmap
    }

    private companion object {
        const val DURATION = 2_000_000L
    }
}

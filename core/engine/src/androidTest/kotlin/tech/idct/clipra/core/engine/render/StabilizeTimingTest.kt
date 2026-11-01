package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.FrameTimes
import tech.idct.clipra.core.model.Correction
import tech.idct.clipra.core.model.Stabilized
import java.io.File

/**
 * A steadied clip moves each frame by that frame's own correction (#54), though its frames don't
 * fall on the project's: phone footage runs at 30.06 fps or a variable rate, and a 24 fps clip in
 * a 30 fps project shows one frame over several project frames. Looked up at the project's time,
 * the correction drifted off the shake, and a strongly shaken clip kept two thirds of its shake.
 */
@RunWith(AndroidJUnit4::class)
class StabilizeTimingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun eachFrameMovesByItsOwnCorrection() {
        // Red and green above, blue and white below, 24 frames a second.
        val video = File(context.cacheDir, "quadrants-24fps.mp4")
        instrumentation.context.assets.open("golden/quadrants-24fps.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val times = FrameTimes.of(context, video.path)
        val frameTimes = (0 until FRAMES).map { times.at(it, -1) }
        assertEquals(41_667.0, frameTimes[1].toDouble(), 1.0)
        // Every other frame shaken a quarter of the picture to the left, and moved back by its
        // correction; the frames between stay where they are.
        val corrections = frameTimes.indices.map { i ->
            if (i % 2 == 1) Correction(0.25f, 0f, 0f, 1f) else Correction.NONE
        }
        val timeline = RenderTimeline(
            width = SIDE,
            height = SIDE,
            frameRate = 30,
            durationUs = 1_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "steadied",
                            0,
                            1_000_000,
                            RenderSource.Video(video.path, 0, 1_000_000, hasAudio = false),
                            stabilized = Stabilized(frameTimes, corrections, 1f),
                        ),
                    ),
                ),
            ),
        )
        // At 0.1 s the project shows the frame of 0.083 s (frame 2, not moved). At the project's
        // own time, between frames 2 and 3, the correction would move it right by 0.1.
        val frame = Offscreen.render(context, timeline, 100_000)
        assertGreen(frame, (SIDE * 0.55f).toInt(), SIDE / 4, "frame 2 not moved")

        // At 0.133 s it shows frame 3 (0.125 s), moved right by a quarter: red reaches past
        // the middle.
        val moved = Offscreen.render(context, timeline, 133_333)
        val red = moved.getPixel((SIDE * 0.6f).toInt(), SIDE / 4)
        assertTrue("frame 3 moved #%06X".format(red and 0xFFFFFF), Color.red(red) > 180)
    }

    private fun assertGreen(frame: Bitmap, x: Int, y: Int, what: String) {
        val pixel = frame.getPixel(x, y)
        val green = Color.green(pixel) > 180 && Color.red(pixel) < 80
        assertTrue("$what #%06X".format(pixel and 0xFFFFFF), green)
    }

    private companion object {
        const val SIDE = 160
        const val FRAMES = 24
    }
}

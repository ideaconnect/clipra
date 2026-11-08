package tech.idct.clipra.core.engine.preview

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.render.CanvasClips
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The preview plays at the project's frame rate (#227), not at the 30 frames a second
 * CompositionPlayer gives a still image: a photo in picture moves across the frame, as in the
 * issue's probe, and the frames that reach the surface are counted. A check of the pipeline,
 * not of a phone's speed, so it holds on the emulator: a 60 fps project gets clearly more than
 * 30, and no project more than its own rate. Logs under PreviewRate, with the longest wait for
 * a new picture.
 */
@RunWith(AndroidJUnit4::class)
class PreviewRateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val root = File(context.cacheDir, "preview-rate").apply { mkdirs() }

    @Test
    fun aSixtyFpsProjectPreviewsAboveThirty() {
        val fps = shownFps(FrameRate.Fps60, makeFirst = true)
        assertTrue("60 fps project previewed at $fps fps", fps > MIN_HIGH_FPS)
        assertTrue("60 fps project previewed at $fps fps", fps < 60 + SLACK_FPS)
    }

    @Test
    fun aTwentyFourFpsProjectPreviewsAtItsOwnRate() {
        val fps = shownFps(FrameRate.Fps24, makeFirst = true)
        assertTrue("24 fps project previewed at $fps fps", fps < 24 + SLACK_FPS)
        assertTrue("24 fps project previewed at $fps fps", fps > 24 * 0.8)
    }

    /** The first preview at a rate starts on the image canvas and moves to the clip by itself. */
    @Test
    fun aPreviewMovesToItsClipOnceItIsMade() {
        val fps = shownFps(FrameRate.Fps50, makeFirst = false)
        assertTrue("50 fps project previewed at $fps fps", fps > MIN_HIGH_FPS)
        assertTrue("50 fps project previewed at $fps fps", fps < 50 + SLACK_FPS)
    }

    /**
     * Frames shown a second while [rate]'s project plays from 7.5 s. [makeFirst] makes the
     * canvas clip before the preview starts; otherwise it is made from nothing as the preview
     * starts, as on the first use of a rate.
     */
    private fun shownFps(rate: FrameRate, makeFirst: Boolean): Double {
        if (makeFirst) {
            CanvasClips.make(
                context,
                rate.fps,
            )
        } else {
            CanvasClips.forget(context, rate.fps)
        }
        val project = project(rate)
        val size = PreviewPlayer.previewSize(project.format)
        val thread = HandlerThread("rate-reader").apply { start() }
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
        val shown = AtomicInteger()
        val arrivals = Collections.synchronizedList(ArrayList<Long>())
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            shown.incrementAndGet()
            arrivals += SystemClock.elapsedRealtimeNanos()
        }, Handler(thread.looper))
        lateinit var player: PreviewPlayer
        instrumentation.runOnMainSync {
            player = PreviewPlayer(context, MediaCache(File(root, "cache")))
            player.setProject(project, START_US)
            player.attach(reader.surface, size.width, size.height)
            player.play()
        }
        SystemClock.sleep(if (makeFirst) WARM_UP_MS else WARM_UP_MS + MAKE_MS)
        var startUs = 0L
        instrumentation.runOnMainSync { startUs = player.positionUs }
        val before = shown.get()
        val started = SystemClock.elapsedRealtime()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        SystemClock.sleep(MEASURE_MS)
        val frames = shown.get() - before
        val elapsedMs = SystemClock.elapsedRealtime() - started
        var endUs = 0L
        instrumentation.runOnMainSync {
            endUs = player.positionUs
            player.release()
        }
        reader.close()
        thread.quitSafely()
        val fps = frames * 1_000.0 / elapsedMs
        // The longest wait for a new picture, and how many waits were over 1.5 frames.
        val times = synchronized(arrivals) { arrivals.filter { it >= startedNs } }
        val gapsMs = times.zipWithNext { a, b -> (b - a) / 1e6 }
        val late = gapsMs.count { it > 1.5 * 1_000 / rate.fps }
        val longest = gapsMs.indices.maxByOrNull { gapsMs[it] }
        // Where it was, roughly: the clock runs in real time.
        val longestAtS = longest?.let { (startUs + (times[it] - startedNs) / 1_000) / 1e6 } ?: 0.0
        Log.i(
            TAG,
            "${rate.fps} fps project, ${size.width}x${size.height}, from %.2f s to %.2f s: "
                .format(Locale.ROOT, startUs / 1e6, endUs / 1e6) +
                "$frames frames in $elapsedMs ms, %.1f fps shown, ".format(Locale.ROOT, fps) +
                "longest gap %.1f ms at about %.2f s, $late gaps over 1.5 frames"
                    .format(Locale.ROOT, longest?.let { gapsMs[it] } ?: 0.0, longestAtS),
        )
        assertTrue("The preview didn't play: $startUs to $endUs us", endUs - startUs > 1_000_000)
        return fps
    }

    /** A 20 s project: a photo at 40 % moving from the top left to the bottom right. */
    private fun project(rate: FrameRate): Project {
        val file = File(root, "photo.png")
        instrumentation.context.assets.open("spike/photo.png").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val photo = MediaInspector.inspect(context, Uri.fromFile(file))
        val from = Transform(x = -0.3f, y = -0.3f, scale = 0.4f)
        val to = Transform(x = 0.3f, y = 0.3f, scale = 0.4f)
        return Project(
            id = "rate-${rate.fps}",
            name = "Rate",
            format = Format(frameRate = rate),
            media = listOf(photo),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "p",
                            ClipType.Photo,
                            0,
                            DURATION_US,
                            MediaRef(photo.id, 0, 0),
                            transform = Keyframed(
                                from,
                                listOf(Keyframe(0, from), Keyframe(DURATION_US, to)),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val TAG = "PreviewRate"
        const val DURATION_US = 20_000_000L
        const val START_US = 7_500_000L
        const val WARM_UP_MS = 1_000L

        /** Time for the canvas clip to be made when the preview starts without it. */
        const val MAKE_MS = 2_000L
        const val MEASURE_MS = 5_000L

        /** Clearly above the 30 frames a second of an image canvas. */
        const val MIN_HIGH_FPS = 40.0

        /** Measured over 5 s, a frame or two either way. */
        const val SLACK_FPS = 1.5
    }
}

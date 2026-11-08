package tech.idct.clipra.core.engine.render

import android.graphics.Color
import android.media.MediaExtractor
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.ReversedVideos
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Reversed clips (#22) frame by frame (#229). Back to front, read from the media or from the
 * copy the preview plays, a reversed clip shows at each moment the frame the media has there:
 * the last one at or before it, as playing forwards does. Read from the media, each frame is
 * decoded about once, where seeking back for every frame decoded half a group of pictures for
 * each frame shown.
 *
 * At 24 fps the times asked for never fall on one of the media's 60 frames a second, so a frame
 * either side of the right one shows as wrong; at 30 fps every one does, as in most projects.
 *
 * `golden/frame-numbers.mp4` writes each frame's number in eight bars, the highest bit on the
 * left, light for 1 and dark for 0: 160x96, 60 fps, 3 s, H.264 High with B-frames and a key
 * frame every second, made with ffmpeg:
 *
 * ```
 * ffmpeg -f lavfi -i "color=c=black:s=160x96:r=60:d=3,format=yuv420p,
 *     geq=lum='if(mod(floor(N/pow(2\,7-floor(X/20)))\,2)\,235\,16)':cb=128:cr=128" \
 *   -c:v libx264 -profile:v high -preset slow -crf 12 -g 60 -keyint_min 60 -sc_threshold 0 \
 *   -bf 2 -pix_fmt yuv420p -color_primaries smpte170m -color_trc smpte170m \
 *   -colorspace smpte170m -color_range tv -movflags +faststart -an frame-numbers.mp4
 * ```
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ReversedFramesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "reversed-frames")
    private lateinit var video: File
    private lateinit var frameTimes: List<Long>
    private val callbackThread = HandlerThread("reversed-frames").apply { start() }
    private val handler = Handler(callbackThread.looper)

    @Before
    fun copyMedia() {
        dir.deleteRecursively()
        dir.mkdirs()
        video = File(dir, "frame-numbers.mp4")
        instrumentation.context.assets.open("golden/frame-numbers.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        frameTimes = frameTimes(video)
        assertEquals(FRAMES, frameTimes.size)
    }

    @After
    fun stopThread() {
        callbackThread.quitSafely()
    }

    @Test
    fun backToFrontEachFrameIsDecodedOnce() = withGl {
        val requests = reversedPlayback(24)
        val reversed = ReversedFrameSource(context, video.path, handler, 2 * WIDTH, WIDTH)
        val seeking = VideoFrameSource(context, video.path, handler)
        try {
            for (sourceUs in requests) {
                assertTrue(reversed.frameAt(sourceUs, tolerance(24)))
                assertTrue(seeking.frameAt(sourceUs, tolerance(24)))
                val expected = expectedFrame(sourceUs, 24)
                assertEquals("at $sourceUs us", expected, reversed.frameTimeUs)
                assertEquals("seeking, at $sourceUs us", expected, seeking.frameTimeUs)
            }
            Log.i(
                TAG,
                "back to front: ${reversed.decodedFrames} frames decoded for ${requests.size} " +
                    "shown, seeking back: ${seeking.decodedFrames}",
            )
            // Three groups of 60 frames, each decoded once, and the frame after each stretch.
            assertTrue("decoded ${reversed.decodedFrames}", reversed.decodedFrames <= FRAMES + 6)
            // Seeking back for every frame decodes half a group for each of the 72 shown.
            assertTrue(
                "seeking decoded ${seeking.decodedFrames}, kept frames ${reversed.decodedFrames}",
                seeking.decodedFrames > 8 * reversed.decodedFrames,
            )
        } finally {
            reversed.release()
            seeking.release()
        }
    }

    @Test
    fun fewSlotsDecodeAGroupInPasses() = withGl {
        val slotBytes = WIDTH.toLong() * HEIGHT * 4
        val reversed = ReversedFrameSource(
            context,
            video.path,
            handler,
            2 * WIDTH,
            WIDTH,
            budgetBytes = 8 * slotBytes,
        )
        try {
            assertEquals(8, reversed.slots)
            for (sourceUs in reversedPlayback(24)) {
                assertTrue(reversed.frameAt(sourceUs, tolerance(24)))
                assertEquals("at $sourceUs us", expectedFrame(sourceUs, 24), reversed.frameTimeUs)
            }
            // Each group of 60 in passes of 8 frames: about 60 * 60 / 16 decoded, not 60 * 30.
            val decoded = reversed.decodedFrames
            Log.i(TAG, "back to front in 8 slots: $decoded frames decoded")
            assertTrue("decoded $decoded", decoded in FRAMES + 1..3 * 300)
        } finally {
            reversed.release()
        }
    }

    @Test
    fun scrubbingBothWaysShowsEachTimesFrame() = withGl {
        val slotBytes = WIDTH.toLong() * HEIGHT * 4
        val reversed =
            ReversedFrameSource(context, video.path, handler, 2 * WIDTH, WIDTH, 8 * slotBytes)
        try {
            val times = listOf(
                2_500_000L, 2_480_000, 2_700_000, 900_000, 1_010_000, 990_000, 0, 16_000,
                2_966_000, 1_999_000, 2_000_000, 1_400_000, 1_380_000, 1_420_000, 600_000,
            )
            for (sourceUs in times) {
                assertTrue(reversed.frameAt(sourceUs, tolerance(30)))
                assertEquals("at $sourceUs us", expectedFrame(sourceUs, 30), reversed.frameTimeUs)
            }
        } finally {
            reversed.release()
        }
    }

    @Test
    fun theCompositorDrawsAReversedClipsFrames() {
        val clip = RenderClip(
            "reversed",
            0,
            DURATION_US,
            RenderSource.Video(video.path, 0, DURATION_US, hasAudio = false),
            speed = Speed(reverse = true),
            sourceSpanUs = DURATION_US,
        )
        for ((timeUs, number) in drawnFrames(timeline(clip, 24))) {
            val expected = frameTimes.indexOf(expectedFrame(sourceTimeAt(clip, timeUs, 24), 24))
            assertEquals("at $timeUs us", expected, number)
        }
    }

    @Test
    fun thePreviewPlaysTheCopyLaidOutBackToFront() {
        val cache = MediaCache(File(dir, "cache"))
        val asset = MediaInspector.inspect(context, Uri.fromFile(video))
        val clip = Clip(
            "reversed",
            ClipType.Video,
            0,
            DURATION_US,
            MediaRef(asset.id, 0, DURATION_US),
            speed = Speed(reverse = true),
        )
        val project = Project(
            id = "reversed",
            name = "Reversed",
            format = Format(frameRate = FrameRate.Fps30),
            media = listOf(asset),
            layers = listOf(Layer("L1", clips = listOf(clip))),
        )
        assertTrue(ReversedVideos(context, cache).prepare(project, clip))
        val copy = cache.reversedVideo(asset.id, 0, DURATION_US)
        assertTrue(copy.exists())
        // Every frame of the media, the first one at the out point.
        val copied = frameTimes(copy)
        assertTrue("${copied.size} frames", abs(copied.size - FRAMES) <= 1)
        assertEquals(0L, copied.first())

        val preview = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 1)
        val played = ProjectRender.timeline(project, preview, WIDTH, HEIGHT)
            .layers.single().clips.single()
        assertEquals(copy.path, played.reversedVideoPath)
        val export = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 1)
        val exported = ProjectRender.timeline(project, export, WIDTH, HEIGHT)
            .layers.single().clips.single()
        assertNull(exported.reversedVideoPath)

        // The copy is encoded once more, at the media's frame rate: the same frames, also where
        // a time falls on one of them. The media itself goes first, so that the frames can
        // only come from the copy.
        assertTrue(video.delete())
        val shown = drawnFrames(timeline(played, 30))
        val off = shown.count { (timeUs, number) ->
            number != frameTimes.indexOf(expectedFrame(sourceTimeAt(played, timeUs, 30), 30))
        }
        Log.i(TAG, "from the copy: $off of ${shown.size} frames off")
        for ((timeUs, number) in shown) {
            val expected = frameTimes.indexOf(expectedFrame(sourceTimeAt(played, timeUs, 30), 30))
            assertEquals("at $timeUs us", expected, number)
        }
    }

    /** The source times a timeline at [fps] asks for, playing the whole clip reversed. */
    private fun reversedPlayback(fps: Int): List<Long> = (0 until DURATION_US * fps / 1_000_000)
        .map { (DURATION_US - it * 1_000_000L / fps).coerceIn(0, DURATION_US - 1_000_000L / fps) }

    /** Half a frame at [fps]: what the compositor allows a frame to come early. */
    private fun tolerance(fps: Int): Long = 500_000L / fps

    /** The frame [VideoFrameSource.frameAt] shows for [sourceUs]: the last one at or before. */
    private fun expectedFrame(sourceUs: Long, fps: Int): Long =
        frameTimes.lastOrNull { it <= sourceUs + tolerance(fps) } ?: frameTimes.first()

    /** Where a compositor at [fps] reads [clip]'s media at [timeUs]. */
    private fun sourceTimeAt(clip: RenderClip, timeUs: Long, fps: Int): Long =
        clip.sourceOffsetAt(timeUs).coerceIn(0, DURATION_US - 1_000_000L / fps)

    private fun timeline(clip: RenderClip, fps: Int) = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = fps,
        durationUs = DURATION_US,
        background = Color.BLACK,
        layers = listOf(RenderLayer(clips = listOf(clip))),
    )

    /** The number each frame of [timeline] shows, drawn in order by one compositor. */
    private fun drawnFrames(timeline: RenderTimeline): Map<Long, Int> = withGl {
        val program = LayerCompositor(timeline).toGlShaderProgram(context, false)
            as BaseGlShaderProgram
        val texture = GlUtil.createTexture(WIDTH, HEIGHT, false)
        val fbo = GlUtil.createFboForTexture(texture)
        val fps = timeline.frameRate
        try {
            program.configure(16, 16)
            (0 until DURATION_US * fps / 1_000_000).associate { k ->
                val timeUs = k * 1_000_000L / fps
                GlUtil.focusFramebufferUsingCurrentContext(fbo, WIDTH, HEIGHT)
                program.drawFrame(0, timeUs)
                timeUs to number()
            }
        } finally {
            program.release()
            GlUtil.deleteFbo(fbo)
            GlUtil.deleteTexture(texture)
        }
    }

    /** The frame number the bars of the frame drawn say, read across its middle row. */
    private fun number(): Int {
        val row = ByteBuffer.allocateDirect(WIDTH * 4)
        GLES20.glReadPixels(0, HEIGHT / 2, WIDTH, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, row)
        var value = 0
        for (bit in 0 until 8) {
            val level = row.get((bit * BAR + BAR / 2) * 4).toInt() and 0xFF
            value = value * 2 + if (level > 128) 1 else 0
        }
        return value
    }

    private fun frameTimes(file: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val times = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                times += extractor.sampleTime
                extractor.advance()
            }
            return times.sorted()
        } finally {
            extractor.release()
        }
    }

    private fun <T> withGl(block: () -> T): T {
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(display)
        GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        try {
            return block()
        } finally {
            GlUtil.destroyEglContext(display, eglContext)
        }
    }

    private companion object {
        const val TAG = "ReversedFrames"
        const val WIDTH = 160
        const val HEIGHT = 96
        const val BAR = 20
        const val FRAMES = 180
        const val DURATION_US = 3_000_000L
    }
}

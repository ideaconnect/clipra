package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The engine spike (#6): five layers with gaps, Screen and Multiply blend modes, a dissolve, a
 * photo and a text card, exported at 1080 × 1920, 30 fps with Transformer and previewed with
 * CompositionPlayer. Each check reads the centre pixel at a time where the expected colour is
 * known from the layer stack. Numbers go to logcat under the tag EngineSpike.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class EngineSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var media: File

    @Before
    fun copyMedia() {
        media = File(context.cacheDir, "spike").apply { mkdirs() }
        val assets = instrumentation.context.assets
        for (name in assets.list("spike")!!) {
            assets.open("spike/$name").use { input ->
                File(media, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    @Test
    fun exportsTheLayerStack() {
        val timeline = timeline(width = 1080, height = 1920)
        val drawNs = AtomicLong()
        val frames = AtomicInteger()
        val composition = CompositionFactory.create(context, timeline) { _, ns ->
            drawNs.addAndGet(ns)
            frames.incrementAndGet()
        }
        val output = File(context.cacheDir, "spike-export.mp4").apply { delete() }

        val started = SystemClock.elapsedRealtime()
        export(composition, output)
        val exportMs = SystemClock.elapsedRealtime() - started
        Log.i(
            TAG,
            "export 1080x1920 30fps 8 s: $exportMs ms (${"%.2f".format(
                8000.0 / exportMs,
            )}x real time), " +
                "${frames.get()} frames, compositor ${drawNs.get() / 1_000_000 / maxOf(
                    1,
                    frames.get(),
                )} ms/frame, " +
                "${output.length() / 1024} KiB",
        )

        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            // Media3 encodes portrait as landscape with a 90 degree rotation unless portrait
            // encoding is enabled; players show it upright either way.
            fun meta(key: Int) = retriever.extractMetadata(key)!!.toInt()
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            Log.i(TAG, "export stored as ${width}x$height, rotation $rotation")
            val shown = if (rotation % 180 == 0) width to height else height to width
            assertEquals(1080 to 1920, shown)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )!!.toLong()
            assertTrue("duration $durationMs ms", abs(durationMs - 8000) < 200)
            assertEquals(
                "yes",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
            )
            for ((timeUs, expected) in CENTRE_CHECKS) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )!!
                assertColor("centre at $timeUs us", expected, frame.getPixel(540, 960))
                assertColor("text card at $timeUs us", Color.WHITE, frame.getPixel(130, 1640))
            }
        }
    }

    @Test
    fun previewsAndSeeks() {
        // Preview renders at half size, as the editor does on proxies.
        val timeline = timeline(width = 540, height = 960)
        val frames = AtomicInteger()
        val drawNs = AtomicLong()
        val composition = CompositionFactory.create(context, timeline, includeAudio = false) {
                timeUs,
                ns,
            ->
            if (frames.incrementAndGet() <=
                40
            ) {
                Log.d(TAG, "preview frame at $timeUs us: ${ns / 1_000_000} ms")
            }
            drawNs.addAndGet(ns)
        }
        val readerThread = HandlerThread("spike-reader").apply { start() }
        val reader = ImageReader.newInstance(540, 960, PixelFormat.RGBA_8888, 3)
        val latest = AtomicReference<Bitmap?>()
        val shown = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val bitmap = Bitmap.createBitmap(
                    plane.rowStride / plane.pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                latest.set(bitmap)
                shown.incrementAndGet()
            }
        }, Handler(readerThread.looper))

        lateinit var player: CompositionPlayer
        instrumentation.runOnMainSync {
            player = CompositionPlayer.Builder(context).build()
            player.setVideoSurface(reader.surface, Size(540, 960))
            player.setComposition(composition)
            player.prepare()
            player.play()
        }
        val started = SystemClock.elapsedRealtime()
        SystemClock.sleep(5_000)
        val elapsed = SystemClock.elapsedRealtime() - started
        val drawn = frames.get()
        Log.i(
            TAG,
            "preview 540x960: ${"%.1f".format(drawn * 1000.0 / elapsed)} fps composited, " +
                "${"%.1f".format(shown.get() * 1000.0 / elapsed)} fps shown, " +
                "compositor ${drawNs.get() / 1_000_000 / maxOf(1, drawn)} ms/frame",
        )

        // Seek forward into the gap on L2, then back to the Screen blend; wait for the frame.
        for ((timeUs, expected) in listOf(6_500_000L to GRAY, 1_500_000L to YELLOW)) {
            instrumentation.runOnMainSync {
                player.pause()
                player.seekTo(timeUs / 1_000)
            }
            val seekStarted = SystemClock.elapsedRealtime()
            var centre = 0
            while (SystemClock.elapsedRealtime() - seekStarted < SEEK_TIMEOUT_MS) {
                centre = latest.get()?.getPixel(270, 480) ?: 0
                if (isClose(expected, centre)) break
                SystemClock.sleep(50)
            }
            Log.i(TAG, "seek to $timeUs us: ${SystemClock.elapsedRealtime() - seekStarted} ms")
            assertColor("preview centre after seeking to $timeUs us", expected, centre)
        }
        instrumentation.runOnMainSync { player.release() }
        reader.close()
        readerThread.quitSafely()
    }

    /**
     * L1 gray (whole timeline). L2 red, then blue dissolving in over 3.5 to 4.0 s, then a gap.
     * L3 green in a centred box with Screen (1 to 3 s), a gap, a photo top-left (5 to 7 s).
     * L4 cyan with Multiply (2 to 3.5 s). L5 a text card near the bottom.
     */
    private fun timeline(width: Int, height: Int): RenderTimeline {
        fun video(name: String) = RenderSource.Video(File(media, "$name.mp4").path)
        val photo = android.graphics.BitmapFactory.decodeFile(File(media, "photo.png").path)
        return RenderTimeline(
            width = width,
            height = height,
            frameRate = 30,
            durationUs = 8_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(listOf(RenderClip("gray", 0, 8_000_000, video("gray")))),
                RenderLayer(
                    listOf(
                        RenderClip("red", 0, 4_000_000, video("red")),
                        RenderClip("blue", 3_500_000, 2_500_000, video("blue"), fadeInUs = 500_000),
                    ),
                ),
                RenderLayer(
                    listOf(
                        RenderClip(
                            "green",
                            1_000_000,
                            2_000_000,
                            video("green"),
                            placement = Placement.Box(0.25f, 0.35f, 0.5f, 0.3f),
                            blend = BlendMode.Screen,
                        ),
                        RenderClip(
                            "photo",
                            5_000_000,
                            2_000_000,
                            RenderSource.Image(photo),
                            placement = Placement.Box(0.05f, 0.05f, 0.4f, 0.2f),
                        ),
                    ),
                ),
                RenderLayer(
                    listOf(
                        RenderClip(
                            "cyan",
                            2_000_000,
                            1_500_000,
                            video("cyan"),
                            blend = BlendMode.Multiply,
                        ),
                    ),
                ),
                RenderLayer(
                    listOf(
                        RenderClip(
                            "text",
                            0,
                            8_000_000,
                            RenderSource.Image(textCard()),
                            placement = Placement.Box(0.1f, 0.82f, 0.8f, 0.1f),
                        ),
                    ),
                ),
            ),
        )
    }

    /** A white card with dark text, as a text overlay renders. */
    private fun textCard(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x14, 0x12, 0x10)
            textSize = 64f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("DAY ONE", 400f, 72f, paint)
        return bitmap
    }

    private fun export(composition: Composition, output: File) {
        val done = CountDownLatch(1)
        val error = AtomicReference<ExportException?>()
        instrumentation.runOnMainSync {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        error.set(exportException)
                        done.countDown()
                    }
                })
                .build()
                .start(composition, output.path)
        }
        assertTrue("export timed out", done.await(10, TimeUnit.MINUTES))
        error.get()?.let { throw it }
    }

    private fun isClose(expected: Int, actual: Int): Boolean =
        abs(Color.red(expected) - Color.red(actual)) <= TOLERANCE &&
            abs(Color.green(expected) - Color.green(actual)) <= TOLERANCE &&
            abs(Color.blue(expected) - Color.blue(actual)) <= TOLERANCE

    private fun assertColor(what: String, expected: Int, actual: Int) {
        val close = isClose(expected, actual)
        assertTrue(
            "$what: expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF),
            close,
        )
    }

    private companion object {
        const val TAG = "EngineSpike"
        const val TOLERANCE = 28
        const val SEEK_TIMEOUT_MS = 8_000L
        val GRAY = Color.rgb(128, 128, 128)
        val YELLOW = Color.rgb(255, 255, 0)

        /** Time and the centre colour the layer stack gives at that time. */
        val CENTRE_CHECKS = listOf(
            // L2 red covers L1.
            500_000L to Color.RED,
            // L3 green Screen over red: yellow.
            1_500_000L to YELLOW,
            // L4 cyan Multiply over yellow: green.
            2_500_000L to Color.GREEN,
            // Halfway through the dissolve from red to blue.
            3_750_000L to Color.rgb(128, 0, 128),
            // Blue alone.
            4_500_000L to Color.BLUE,
            // L2 and L3 have gaps: L1 gray shows through.
            6_500_000L to GRAY,
        )
    }
}

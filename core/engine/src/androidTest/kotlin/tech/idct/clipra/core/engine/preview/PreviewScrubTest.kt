package tech.idct.clipra.core.engine.preview

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.ProxyGenerator
import tech.idct.clipra.core.engine.render.CanvasClips
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The paused preview while the timeline is scrubbed (#244): six full layers, the playhead moving
 * back and forth 60 times a second for three seconds, as a finger and its fling move it, with
 * Media3's scrubbing mode off and on. Logs under PreviewScrub how many pictures reached the
 * surface, the test process's CPU time and how long the last position took to show; checks that
 * the preview ends on the last position either way. On phones only: on the emulators no picture
 * reaches the surface, and the test is skipped.
 *
 * L1 to L5 loop the phone's own video when one was pushed to [FOOTAGE] (docs/release/
 * performance.md), the bundled red clip otherwise. L6 alternates blue and red clips every three
 * seconds, so the colour of the picture says where the preview is.
 */
@RunWith(AndroidJUnit4::class)
class PreviewScrubTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "preview-scrub").apply { mkdirs() }
    private val cache = MediaCache(File(dir, "cache"))

    @Test
    fun theScrubbedPreviewEndsOnTheLastPosition() = runBlocking {
        val project = project()
        for (asset in project.media.filter(ProxyGenerator::needsProxy)) {
            val proxy = cache.proxy(asset.id)
            if (!proxy.exists()) ProxyGenerator.generate(context, Uri.parse(asset.uri), proxy)
        }
        // The preview plays on its canvas clip once that is made (#227); made here, it starts so.
        CanvasClips.make(context, project.format.frameRate.fps)
        val results = listOf(false, true, false, true).map { scrubbing ->
            scrub(project, scrubbing)
        }
        // The emulators' preview never reaches an ImageReader (PreviewDragTest counts no frames
        // there either): nothing to measure or check.
        assumeTrue(
            "no picture reached the surface: $results",
            results.all { it.warmShown > 0 },
        )
        for (scrubbing in listOf(false, true)) {
            val runs = results.filter { it.scrubbing == scrubbing }
            Log.i(
                TAG,
                "scrubbing mode ${if (scrubbing) "on" else "off"}, ${project.layers.size} layers " +
                    "(${project.media.first().displayName}): " +
                    runs.joinToString("; ") { it.toString() },
            )
        }
        for (result in results) {
            assertEquals(
                "ends at the last position",
                END_US.toDouble(),
                result.endUs.toDouble(),
                50e3,
            )
            assertTrue("$result: the last position never showed", result.settleMs >= 0)
        }
        // With the mode on, the preview still follows the scrub as it goes.
        assertTrue("$results", results.filter { it.scrubbing }.all { it.shown > 0 })
    }

    private class Result(
        val scrubbing: Boolean,
        val seeks: Int,
        val ms: Long,
        val shown: Int,
        val cpuMs: Long,
        val settleMs: Long,
        val endUs: Long,
        /** Pictures before the scrub and in all, and the last one's colour, for a failure. */
        val warmShown: Int,
        val allShown: Int,
        val lastColour: Int,
    ) {
        override fun toString() =
            "%d seeks in %d ms: %d pictures (%.1f a second), CPU %d ms, last position in %d ms"
                .format(Locale.ROOT, seeks, ms, shown, shown * 1000.0 / ms, cpuMs, settleMs) +
                " (%d pictures before, %d in all, the last #%06x)"
                    .format(Locale.ROOT, warmShown, allShown, lastColour and 0xFFFFFF)
    }

    private fun scrub(project: Project, scrubbing: Boolean): Result {
        val size = PreviewPlayer.previewSize(project.format)
        val thread = HandlerThread("scrub-reader").apply { start() }
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
        val shown = AtomicInteger()
        val blueShownAt = AtomicReference<Long?>(null)
        val lastColour = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = image.planes[0]
            val at = (size.height / 2) * plane.rowStride + (size.width / 2) * plane.pixelStride
            val red = plane.buffer.get(at).toInt() and 0xFF
            val green = plane.buffer.get(at + 1).toInt() and 0xFF
            val blue = plane.buffer.get(at + 2).toInt() and 0xFF
            image.close()
            lastColour.set(red shl 16 or (green shl 8) or blue)
            shown.incrementAndGet()
            if (blue > red + COLOUR_MARGIN) {
                blueShownAt.compareAndSet(null, SystemClock.elapsedRealtime())
            } else {
                blueShownAt.set(null)
            }
        }, Handler(thread.looper))
        lateinit var player: PreviewPlayer
        instrumentation.runOnMainSync {
            player = PreviewPlayer(context, cache)
            player.setProject(project, START_US)
            player.attach(reader.surface, size.width, size.height)
        }
        // Decoders start and the first picture settles before the scrub.
        SystemClock.sleep(WARM_UP_MS)

        val startShown = shown.get()
        val startCpu = Process.getElapsedCpuTime()
        val started = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { player.setScrubbing(scrubbing) }
        var seeks = 0
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= SCRUB_MS) break
            // Back and forth, once a second, as three swipes and their flings move the
            // playhead.
            val phase = (elapsed % 1_000) / 1_000f
            val wave = if (phase < 0.5f) phase * 2 else (1 - phase) * 2
            val positionUs = START_US + (wave * SPAN_US).toLong()
            instrumentation.runOnMainSync { player.seekTo(positionUs) }
            seeks++
            SystemClock.sleep(SEEK_EVERY_MS)
        }
        // The finger lifts on the last position.
        val lastSeek = SystemClock.elapsedRealtime()
        blueShownAt.set(null)
        instrumentation.runOnMainSync {
            player.seekTo(END_US)
            player.setScrubbing(false)
        }
        seeks++
        val scrubMs = lastSeek - started
        val scrubShown = shown.get() - startShown
        val cpuMs = Process.getElapsedCpuTime() - startCpu
        while (SystemClock.elapsedRealtime() - lastSeek < SETTLE_LIMIT_MS) {
            val blue = blueShownAt.get()
            // Blue for a moment: no red picture from before the last seek came after it.
            if (blue != null && SystemClock.elapsedRealtime() - blue > STEADY_MS) break
            SystemClock.sleep(10)
        }
        val settleMs = blueShownAt.get()?.let { it - lastSeek } ?: -1
        var endUs = 0L
        instrumentation.runOnMainSync {
            endUs = player.positionUs
            player.release()
        }
        reader.close()
        thread.quitSafely()
        return Result(
            scrubbing,
            seeks,
            scrubMs,
            scrubShown,
            cpuMs,
            settleMs,
            endUs,
            startShown,
            shown.get(),
            lastColour.get(),
        )
    }

    /**
     * Six full 16:9 layers over [DURATION_US]: L1 to L5 the footage, L6 blue and red clips in
     * turn, three seconds each, blue first.
     */
    private fun project(): Project {
        val footage = footage() ?: asset("spike/red.mp4")
        val blue = asset("spike/blue.mp4")
        val red = asset("spike/red.mp4")
        val lower = (1..5).map { layer("L$it") { footage } }
        val top = layer("L6", COLOUR_US) { i -> if (i % 2 == 0) blue else red }
        return Project(
            id = "scrub",
            name = "Scrub",
            format = Format(ratio = AspectRatio.Landscape16x9),
            media = listOf(footage, blue, red).distinctBy { it.id },
            layers = lower + top,
        )
    }

    /** Clips of [assetAt] (by the clip's index) from the start to the end of the project. */
    private fun layer(
        id: String,
        lengthUs: Long = Long.MAX_VALUE,
        assetAt: (Int) -> MediaAsset,
    ): Layer {
        val clips = ArrayList<Clip>()
        var atUs = 0L
        while (atUs < DURATION_US) {
            val asset = assetAt(clips.size)
            val clipUs = minOf(asset.durationUs, lengthUs, DURATION_US - atUs)
            clips += Clip(
                "$id-${clips.size}",
                ClipType.Video,
                atUs,
                clipUs,
                MediaRef(asset.id, 0, clipUs),
            )
            atUs += clipUs
        }
        return Layer(id, clips = clips)
    }

    private fun asset(path: String): MediaAsset {
        val file = File(dir, path.substringAfterLast('/'))
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return MediaInspector.inspect(context, Uri.fromFile(file))
    }

    /** The phone's own video at [FOOTAGE], if one was pushed. */
    private fun footage(): MediaAsset? {
        val file = File(dir, "footage.mp4")
        val output = instrumentation.uiAutomation.executeShellCommand("cat $FOOTAGE")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return if (file.length() > 0) MediaInspector.inspect(context, Uri.fromFile(file)) else null
    }

    private companion object {
        const val TAG = "PreviewScrub"
        const val FOOTAGE = "/data/local/tmp/clipra-budget.mp4"
        const val DURATION_US = 30_000_000L
        const val COLOUR_US = 3_000_000L

        /** The scrub stays in a red clip (15 to 18 s)... */
        const val START_US = 15_100_000L
        const val SPAN_US = 2_800_000L

        /** ...and the finger lifts in the blue one before it, so a blue picture is the last. */
        const val END_US = 13_500_000L
        const val WARM_UP_MS = 3_000L
        const val SCRUB_MS = 3_000L
        const val SEEK_EVERY_MS = 16L
        const val SETTLE_LIMIT_MS = 10_000L
        const val STEADY_MS = 300L
        const val COLOUR_MARGIN = 60
    }
}

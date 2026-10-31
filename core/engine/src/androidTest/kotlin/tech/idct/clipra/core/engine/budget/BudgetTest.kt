package tech.idct.clipra.core.engine.budget

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.ProxyGenerator
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The export and preview budgets of docs/architecture.md, section 11 (#77): a 42 s project of
 * three video layers at 1080p30 exported from the originals, and previewed from proxies with two
 * effects. Numbers go to logcat under the tag Budget. The limits are checked only with the
 * instrumentation argument `budgets=check`, on real phones; the emulator shows that the
 * measurement works, not how fast a phone is.
 *
 * The layers loop the bundled 1080p clips. For a phone's own footage, push a 1080p30 video to
 * [FOOTAGE] first (docs/release/performance.md); the test reads it through the shell.
 */
@RunWith(AndroidJUnit4::class)
class BudgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val check = InstrumentationRegistry.getArguments().getString("budgets") == "check"
    private val dir = File(context.cacheDir, "budget").apply { mkdirs() }
    private val cache = MediaCache(File(dir, "cache"))

    @Test
    fun exportsThreeLayersIn60Seconds() = runBlocking {
        val project = project()
        val renderer = ExportRenderer(context, cache)

        val started = SystemClock.elapsedRealtime()
        val rendered = renderer.render(project, ExportSettings(), PauseGate()) {}
        val seconds = (SystemClock.elapsedRealtime() - started) / 1_000.0

        Log.i(
            TAG,
            "export ${rendered.width}x${rendered.height} 30 fps, 42 s, 3 layers " +
                "(${project.media.first().displayName}): %.1f s, %.2fx real time"
                    .format(seconds, DURATION_US / 1e6 / seconds),
        )
        rendered.file.delete()
        if (check) assertTrue("export took $seconds s", seconds < EXPORT_LIMIT_S)
    }

    @Test
    fun previewsThreeLayersWithTwoEffectsInRealTime() = runBlocking {
        val project = project()
        for (asset in project.media.filter(ProxyGenerator::needsProxy)) {
            val proxy = cache.proxy(asset.id)
            if (!proxy.exists()) ProxyGenerator.generate(context, Uri.parse(asset.uri), proxy)
        }
        val size = PreviewPlayer.previewSize(project.format)
        val readerThread = HandlerThread("budget-reader").apply { start() }
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
        val shown = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            shown.incrementAndGet()
        }, Handler(readerThread.looper))
        lateinit var player: PreviewPlayer
        instrumentation.runOnMainSync {
            player = PreviewPlayer(context, cache)
            player.setProject(project, 0)
            player.attach(reader.surface, size.width, size.height)
            player.play()
        }
        // Decoders start and the first frames settle before the measurement.
        SystemClock.sleep(WARM_UP_MS)
        var startUs = 0L
        instrumentation.runOnMainSync { startUs = player.positionUs }
        val startShown = shown.get()
        val started = SystemClock.elapsedRealtime()
        SystemClock.sleep(MEASURE_MS)
        var endUs = 0L
        instrumentation.runOnMainSync {
            endUs = player.positionUs
            player.release()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        reader.close()
        readerThread.quitSafely()

        val fps = (shown.get() - startShown) * 1_000.0 / elapsedMs
        val pace = (endUs - startUs) / 1_000.0 / elapsedMs
        Log.i(
            TAG,
            "preview ${size.width}x${size.height}, 3 layers, 2 effects: " +
                "%.1f fps shown, %.2fx real time".format(fps, pace),
        )
        if (check) {
            assertTrue("preview at ${pace}x real time", pace >= MIN_PACE)
            assertTrue("preview at $fps fps", fps >= MIN_FPS)
        }
    }

    /**
     * Three full layers: the picture, a picture in picture with a blur and a smaller one on top;
     * the bottom layer has a vignette.
     */
    private fun project(): Project {
        val footage = footage()
        val bottom = footage ?: asset("spike/gray.mp4")
        val middle = footage ?: asset("spike/red.mp4")
        val top = footage ?: bottom
        return Project(
            id = "budget",
            name = "Budget",
            media = listOf(bottom, middle, top).distinctBy { it.id },
            layers = listOf(
                layer("L1", bottom, Transform(), Effect("vignette")),
                layer("L2", middle, Transform(x = 0.2f, y = -0.25f, scale = 0.5f), Effect("blur")),
                layer("L3", top, Transform(x = -0.25f, y = 0.3f, scale = 0.35f)),
            ),
        )
    }

    /** [asset] repeated from the start to the end of the project. */
    private fun layer(
        id: String,
        asset: MediaAsset,
        transform: Transform,
        vararg effects: Effect,
    ): Layer {
        val clips = ArrayList<Clip>()
        var atUs = 0L
        while (atUs < DURATION_US) {
            val lengthUs = minOf(asset.durationUs, DURATION_US - atUs)
            clips += Clip(
                "$id-${clips.size}",
                ClipType.Video,
                atUs,
                lengthUs,
                MediaRef(asset.id, 0, lengthUs),
                transform = Keyframed(transform),
                effects = effects.toList(),
            )
            atUs += lengthUs
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
        const val TAG = "Budget"
        const val FOOTAGE = "/data/local/tmp/clipra-budget.mp4"
        const val DURATION_US = 42_000_000L
        const val EXPORT_LIMIT_S = 60.0
        const val WARM_UP_MS = 3_000L
        const val MEASURE_MS = 10_000L

        /** Real time: the clock keeps pace, and at most one frame in ten is dropped at 30 fps. */
        const val MIN_PACE = 0.95
        const val MIN_FPS = 27.0
    }
}

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
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * How fast the paused preview follows a drag (follow-up to #118): a clip's transform changes 60
 * times a second for three seconds, and the frames that reach the surface are counted. Logs
 * under PreviewDrag; a measurement, not a check.
 */
@RunWith(AndroidJUnit4::class)
class PreviewDragTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun aDragRedrawsThePausedPreview() {
        val root = File(context.cacheDir, "preview-drag").apply { mkdirs() }
        val gray = File(root, "gray.mp4")
        instrumentation.context.assets.open("spike/gray.mp4").use { input ->
            gray.outputStream().use { input.copyTo(it) }
        }
        val asset = MediaInspector.inspect(context, Uri.fromFile(gray))
        val project = Project(
            id = "drag",
            name = "Drag",
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 8_000_000, MediaRef(asset.id, 0, 8_000_000)),
                    ),
                ),
            ),
        )
        val size = PreviewPlayer.previewSize(project.format)
        val thread = HandlerThread("drag-reader").apply { start() }
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
        val shown = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            shown.incrementAndGet()
        }, Handler(thread.looper))
        lateinit var player: PreviewPlayer
        instrumentation.runOnMainSync {
            player = PreviewPlayer(context, MediaCache(File(root, "cache")))
            player.setProject(project, 2_000_000)
            player.attach(reader.surface, size.width, size.height)
        }
        SystemClock.sleep(3_000)
        val before = shown.get()
        val started = SystemClock.elapsedRealtime()
        var step = 0
        while (SystemClock.elapsedRealtime() - started < DRAG_MS) {
            val x = (step++ % 60) / 100f
            instrumentation.runOnMainSync { player.setLiveTransform("a", Transform(x = x)) }
            SystemClock.sleep(16)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        SystemClock.sleep(500)
        val frames = shown.get() - before
        Log.i(
            "PreviewDrag",
            "$step changes in $elapsed ms: $frames frames, " +
                "%.1f fps".format(frames * 1000.0 / elapsed),
        )
        instrumentation.runOnMainSync { player.release() }
        reader.close()
        thread.quitSafely()
    }

    private companion object {
        const val DRAG_MS = 3_000L
    }
}

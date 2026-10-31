package tech.idct.clipra.core.engine.preview

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The preview moves to a new view when the layout changes (#68), as when a tablet turns, and
 * gets one back when its screen shows again. The paused frame must show on the new surface at
 * once, not after the composition is built again.
 */
@RunWith(AndroidJUnit4::class)
class PreviewSurfaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val thread = HandlerThread("surface-reader")
    private val readers = mutableListOf<ImageReader>()
    private lateinit var player: PreviewPlayer
    private lateinit var size: Size

    @Before
    fun setUp() {
        thread.start()
        val root = File(context.cacheDir, "preview-surface").apply { mkdirs() }
        val gray = File(root, "gray.mp4")
        instrumentation.context.assets.open("spike/gray.mp4").use { input ->
            gray.outputStream().use { input.copyTo(it) }
        }
        val asset = MediaInspector.inspect(context, Uri.fromFile(gray))
        val project = Project(
            id = "surface",
            name = "Surface",
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
        size = PreviewPlayer.previewSize(project.format)
        instrumentation.runOnMainSync {
            player = PreviewPlayer(context, MediaCache(File(root, "cache")))
            player.setProject(project, 2_000_000)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
        readers.forEach { it.close() }
        thread.quitSafely()
    }

    @Test
    fun aNewViewShowsThePausedFrameAtOnce() {
        val first = shown(attachable())
        val waited = measure { second ->
            player.attach(second, size.width, size.height)
            // The new view attaches before the old one lets go.
            player.detach(first)
        }
        assertTrue("The new surface waited $waited ms", waited < MAX_WAIT_MS)
    }

    @Test
    fun aViewThatComesBackShowsThePausedFrameAtOnce() {
        val first = shown(attachable())
        instrumentation.runOnMainSync { player.detach(first) }
        SystemClock.sleep(500)
        val waited = measure { second -> player.attach(second, size.width, size.height) }
        assertTrue("The surface waited $waited ms", waited < MAX_WAIT_MS)
    }

    /** The project was ready before the editor's view had a surface. */
    @Test
    fun theFirstViewShowsTheFrame() {
        SystemClock.sleep(2_000)
        measure { surface -> player.attach(surface, size.width, size.height) }
    }

    /** A surface with the paused frame on it. */
    private fun shown(frame: Frame): Surface {
        instrumentation.runOnMainSync { player.attach(frame.surface, size.width, size.height) }
        assertTrue("No first frame", frame.latch.await(10, TimeUnit.SECONDS))
        SystemClock.sleep(1_000)
        return frame.surface
    }

    /** How long a new surface waits for a frame after [change]; the frame must be the clip. */
    private fun measure(change: (Surface) -> Unit): Long {
        val next = attachable()
        val started = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { change(next.surface) }
        assertTrue("No frame on the new surface", next.latch.await(10, TimeUnit.SECONDS))
        val waited = SystemClock.elapsedRealtime() - started
        Log.i("PreviewSurface", "first frame on the new surface after $waited ms")
        // gray.mp4 is mid-gray; a black frame means nothing was drawn.
        assertTrue("The frame is black (${next.centre})", next.centre in 64..192)
        return waited
    }

    private class Frame(val surface: Surface, val latch: CountDownLatch) {
        @Volatile var centre = -1
    }

    /** An ImageReader's surface; its latch opens on the first frame, keeping its centre's red. */
    private fun attachable(): Frame {
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
        readers += reader
        val frame = Frame(reader.surface, CountDownLatch(1))
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                if (frame.latch.count > 0) {
                    val plane = image.planes[0]
                    val at = (image.height / 2) * plane.rowStride +
                        (image.width / 2) * plane.pixelStride
                    frame.centre = plane.buffer.get(at).toInt() and 0xFF
                    frame.latch.countDown()
                }
            }
        }, Handler(thread.looper))
        return frame
    }

    private companion object {
        const val MAX_WAIT_MS = 500L
    }
}

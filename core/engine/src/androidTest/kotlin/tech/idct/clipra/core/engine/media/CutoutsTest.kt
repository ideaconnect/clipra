package tech.idct.clipra.core.engine.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.ml.vision.SelfieSegmenter
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Person masks (#50) for a test clip: MediaPipe's public-domain test portrait, held for a second
 * and a half in a 9:16 video Clipra exports itself. Every frame gets a mask, the person is found
 * and the background isn't, and the steadied masks barely change from frame to frame where the
 * model alone wavers.
 */
@RunWith(AndroidJUnit4::class)
class CutoutsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "cutouts-test")

    @Test
    fun everyFrameGetsASteadyMask() = runBlocking {
        val video = clip()
        val asset = MediaInspector.inspect(context, Uri.fromFile(video))
        assertEquals(MediaKind.Video, asset.kind)
        val cache = MediaCache(File(root, "cache").apply { deleteRecursively() })
        val cutouts = Cutouts(context, cache)

        val started = System.nanoTime()
        assertTrue(cutouts.prepare(asset))
        val seconds = (System.nanoTime() - started) / 1e9
        val masks = cutouts.masks(asset)!!
        Log.i(TAG, "${masks.frames} masks in ${"%.1f".format(seconds)} s")
        assertTrue("frames ${masks.frames}", masks.frames in 40..50)
        assertTrue(masks.found)

        // The face (the portrait is cropped to fill 9:16) and the curtain left of it.
        val size = CutoutMasks.SIZE
        val mask = ByteArray(size * size)
        val steady = mutableListOf<ByteArray>()
        for (i in 0 until masks.frames) {
            masks.frame(i, mask)
            assertTrue("face in $i", (mask[50 * size + 124].toInt() and 0xFF) > 200)
            assertTrue("curtain in $i", (mask[37 * size + 35].toInt() and 0xFF) < 30)
            steady += mask.copyOf()
        }
        // How much the masks change from frame to frame on a clip where nothing moves: the
        // steadied ones against the model's own on the same frames.
        val raw = rawMasks(video, masks.frames)
        val steadyChange = change(steady)
        val rawChange = change(raw)
        Log.i(TAG, "frame to frame: steadied $steadyChange, model alone $rawChange")
        assertTrue("steadied $steadyChange", steadyChange < 0.5)
        assertTrue("steadied $steadyChange, raw $rawChange", steadyChange <= rawChange)
    }

    /** The mean change of a mask value (0 to 255) from one frame to the next. */
    private fun change(frames: List<ByteArray>): Double {
        var total = 0.0
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            for (j in a.indices) total += abs((a[j].toInt() and 0xFF) - (b[j].toInt() and 0xFF))
        }
        return total / ((frames.size - 1).toDouble() * frames[0].size)
    }

    /** The model's masks of the first [count] frames, without steadying. */
    private fun rawMasks(video: File, count: Int): List<ByteArray> {
        val size = SelfieSegmenter.SIZE
        val pixels = IntArray(size * size)
        val out = FloatArray(size * size)
        val retriever = MediaMetadataRetriever().apply { setDataSource(video.path) }
        return SelfieSegmenter(context).use { segmenter ->
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            retriever.getFramesAtIndex(0, count, params).map { frame ->
                Bitmap.createScaledBitmap(frame, size, size, true)
                    .getPixels(pixels, 0, size, 0, 0, size, size)
                segmenter.segment(pixels, out)
                ByteArray(size * size) { (out[it] * 255 + 0.5f).toInt().coerceIn(0, 255).toByte() }
            }
        }.also { retriever.release() }
    }

    /** The portrait held for 1.5 s, exported at 1080 × 1920. */
    private suspend fun clip(): File {
        root.mkdirs()
        val portrait = portrait()
        val photo = MediaInspector.inspect(context, Uri.fromFile(portrait))
        assertEquals(MediaKind.Photo, photo.kind)
        val project = Project(
            id = "cutouts-test",
            name = "Cutouts test",
            media = listOf(photo),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("p", ClipType.Photo, 0, 1_500_000, MediaRef(photo.id, 0, 0)),
                    ),
                ),
            ),
        )
        val renderer = ExportRenderer(context, MediaCache(File(root, "export")))
        return renderer.render(project, ExportSettings(), PauseGate()) { }.file
    }

    private fun portrait(): File {
        val file = File(root, "portrait.jpg")
        if (!file.exists() || sha256(file) != PORTRAIT_SHA256) {
            URL(PORTRAIT_URL).openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        assertEquals(PORTRAIT_SHA256, sha256(file))
        return file
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CutoutsTest"
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}

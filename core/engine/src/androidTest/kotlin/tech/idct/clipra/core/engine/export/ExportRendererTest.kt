package tech.idct.clipra.core.engine.export

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import kotlin.math.abs

/**
 * Export from originals (#57): a two-clip project renders at the export size, plays, keeps its
 * sound and lands in Movies/Clipra. Logs the real size against the estimate (#56).
 */
@RunWith(AndroidJUnit4::class)
class ExportRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun asset(path: String): Uri {
        val file = File(
            context.cacheDir,
            "export-test/${path.substringAfterLast('/')}",
        ).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun rendersFromOriginalsAndPublishesToTheGallery() = runBlocking {
        val gray = MediaInspector.inspect(context, asset("spike/gray.mp4"))
        val uhd = MediaInspector.inspect(context, asset("import/uhd.mp4"))
        val project = Project(
            id = "export-test",
            name = "Export test",
            media = listOf(gray, uhd),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 4_000_000, MediaRef(gray.id, 0, 4_000_000)),
                        Clip(
                            "b",
                            ClipType.Video,
                            4_000_000,
                            1_000_000,
                            MediaRef(uhd.id, 0, 1_000_000),
                        ),
                    ),
                ),
            ),
        )
        val settings = ExportSettings()
        val renderer = ExportRenderer(
            context,
            MediaCache(File(context.cacheDir, "export-test/cache")),
        )
        var lastProgress = 0f

        val rendered = renderer.render(project, settings, PauseGate()) { lastProgress = it }

        val estimateBytes = settings.estimatedMegabytes(project.durationUs) * 1_000_000
        Log.i(
            "ExportTest",
            "1080x1920 5 s: ${rendered.file.length()} bytes, estimate ${estimateBytes.toLong()} " +
                "(${"%.0f".format(
                    100.0 * rendered.file.length() / estimateBytes,
                )}%), last progress $lastProgress",
        )
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(rendered.file.path)
            fun meta(key: Int) = retriever.extractMetadata(key)!!
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            assertEquals(1080 to 1920, if (rotation % 180 == 0) w to h else h to w)
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()
            assertTrue("duration $durationMs", abs(durationMs - 5_000) < 200)
            assertEquals("yes", meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            // The gray clip fills the frame for the first four seconds.
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val centre = frame.getPixel(frame.width / 2, frame.height / 2)
            assertTrue(
                "centre #%06X".format(centre and 0xFFFFFF),
                abs(Color.red(centre) - 128) < 28,
            )
        }

        val uri = renderer.publish(rendered.file, "clipra-export-test.mp4")
        val projection = arrayOf(MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Movies/Clipra/", cursor.getString(0))
            assertTrue(cursor.getLong(1) > 0)
        }
        context.contentResolver.delete(uri, null, null)
        Unit
    }
}

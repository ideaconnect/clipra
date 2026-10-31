package tech.idct.clipra.core.engine.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
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
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportExtra
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.Otio
import java.io.File
import kotlin.math.abs

/**
 * Export extras (#59): a gray clip with a caption exports with captions as a separate file, and
 * every extra lands where it belongs, named like the video: the cover in Pictures/Clipra, the
 * sound in Music/Clipra, the .srt and the .otio in Download/Clipra.
 */
@RunWith(AndroidJUnit4::class)
class ExportExtrasTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun asset(path: String): Uri {
        val file = File(context.cacheDir, "extras-test/${path.substringAfterLast('/')}")
            .apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private fun read(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun folder(uri: Uri): String = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
        null,
        null,
        null,
    )!!.use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    @Test
    fun everyExtraIsSavedWhereItBelongs() = runBlocking {
        val gray = MediaInspector.inspect(context, asset("spike/gray.mp4"))
        val words = listOf(Word("Hello", 500_000, 900_000), Word("Lisbon", 900_000, 1_500_000))
        val project = Project(
            id = "extras-test",
            name = "Extras test",
            media = listOf(gray),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 2_000_000, MediaRef(gray.id, 0, 2_000_000)),
                    ),
                ),
                Layer(
                    "L2",
                    clips = listOf(
                        Clip(
                            "cap",
                            ClipType.Caption,
                            500_000,
                            1_000_000,
                            caption = CaptionSpec(words),
                        ),
                    ),
                ),
            ),
        )
        val settings = ExportSettings(
            captions = CaptionMode.Srt,
            extras = setOf(ExportExtra.Audio, ExportExtra.Cover, ExportExtra.Project),
            coverUs = 1_000_000,
        )
        val renderer = ExportRenderer(
            context,
            MediaCache(File(context.cacheDir, "extras-test/cache")),
        )
        val rendered = renderer.render(project, settings, PauseGate()) {}
        val saved = try {
            ExportExtras(context).save(project, settings, rendered.file, "clipra-extras-test") {
                renderer.cover(project, settings)
            }
        } finally {
            rendered.file.delete()
        }
        try {
            assertEquals(
                listOf(
                    ExportExtra.Captions,
                    ExportExtra.Audio,
                    ExportExtra.Cover,
                    ExportExtra.Project,
                ),
                saved.map { it.extra }.sortedBy { it.ordinal },
            )
            val byExtra = saved.associateBy { it.extra }
            fun uri(extra: ExportExtra) = Uri.parse(byExtra.getValue(extra).uri)

            // The .srt holds the caption, and the video has none drawn into it.
            assertEquals(
                Srt.write(Srt.cues(project)),
                read(uri(ExportExtra.Captions)).toString(Charsets.UTF_8),
            )
            assertEquals("Download/Clipra/", folder(uri(ExportExtra.Captions)))
            assertEquals("clipra-extras-test.srt", byExtra.getValue(ExportExtra.Captions).fileName)

            // The .otio is the project's timeline.
            assertEquals(
                Otio.write(project),
                read(uri(ExportExtra.Project)).toString(Charsets.UTF_8),
            )
            assertEquals("clipra-extras-test.otio", byExtra.getValue(ExportExtra.Project).fileName)

            // The cover: the gray frame at the export size, without the caption.
            val cover = read(uri(ExportExtra.Cover))
            val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size)
            assertEquals(1080 to 1920, bitmap.width to bitmap.height)
            for (y in listOf(bitmap.height / 2, bitmap.height * 3 / 4, bitmap.height - 200)) {
                val pixel = bitmap.getPixel(bitmap.width / 2, y)
                assertTrue(
                    "cover at $y #%06X".format(pixel and 0xFFFFFF),
                    abs(Color.red(pixel) - 128) < 28 && abs(Color.blue(pixel) - 128) < 28,
                )
            }
            assertEquals("Pictures/Clipra/", folder(uri(ExportExtra.Cover)))

            // The sound alone, as long as the video.
            assertEquals("Music/Clipra/", folder(uri(ExportExtra.Audio)))
            val sound = File(context.cacheDir, "extras-test/sound.m4a")
            sound.writeBytes(read(uri(ExportExtra.Audio)))
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(sound.path)
                assertEquals(
                    "yes",
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
                )
                assertEquals(
                    null,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                )
                val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                    .toLong()
                assertTrue("sound $ms ms", abs(ms - 2_000) < 150)
            }
        } finally {
            saved.forEach { context.contentResolver.delete(Uri.parse(it.uri), null, null) }
        }
        Unit
    }
}

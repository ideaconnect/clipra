package tech.idct.clipra.core.engine.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An export is dated when it was made and carries none of its clips' metadata (#219), in the
 * file and in the gallery, also once MediaProvider has scanned it. Its clip is a phone
 * recording, as a phone's camera writes one.
 */
@RunWith(AndroidJUnit4::class)
class ExportedMetadataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val root = File(context.cacheDir, "metadata-test").apply { mkdirs() }

    private fun asset(path: String): File {
        val file = File(root, path.substringAfterLast('/'))
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    /**
     * `spike/blue.mp4` as a phone records it, its samples copied as they are: recorded on
     * 2025-09-13 at 13:39:52 UTC, with the maker and model tags of the Xiaomi in #219 and a
     * location. Mp4Muxer puts them where Android's own MPEG4Writer does: the date in `mvhd`, the
     * tags as `mdta` keys in `moov/meta`, the location in `udta/©xyz`.
     */
    @OptIn(UnstableApi::class)
    private fun phoneRecording(): File {
        val source = asset("spike/blue.mp4")
        val recording = File(root, "phone-recording.mp4")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            FileOutputStream(recording).use { output ->
                val muxer = Mp4Muxer.Builder(output).build()
                try {
                    val tracks = (0 until extractor.trackCount).associateWith { track ->
                        extractor.selectTrack(track)
                        val format = extractor.getTrackFormat(track)
                        muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
                    }
                    while (extractor.sampleTrackIndex >= 0) {
                        // The muxer may keep the buffer, so every sample gets its own.
                        val sample = ByteBuffer.allocateDirect(extractor.sampleSize.toInt())
                        val size = extractor.readSampleData(sample, 0)
                        val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        muxer.writeSampleData(
                            tracks.getValue(extractor.sampleTrackIndex),
                            sample,
                            BufferInfo(
                                extractor.sampleTime,
                                size,
                                if (key) C.BUFFER_FLAG_KEY_FRAME else 0,
                            ),
                        )
                        extractor.advance()
                    }
                    val recorded = Mp4TimestampData.unixTimeToMp4TimeSeconds(RECORDED_AT)
                    muxer.addMetadataEntry(Mp4TimestampData(recorded, recorded))
                    for ((key, value) in PHONE_TAGS) {
                        muxer.addMetadataEntry(
                            MdtaMetadataEntry(
                                key,
                                value.toByteArray(Charsets.UTF_8),
                                MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                            ),
                        )
                    }
                    muxer.addMetadataEntry(Mp4LocationData(52.2297f, 21.0122f))
                } finally {
                    muxer.close()
                }
            }
        } finally {
            extractor.release()
        }
        return recording
    }

    private fun meta(file: File, key: Int): String? = MediaMetadataRetriever().use {
        it.setDataSource(file.path)
        it.extractMetadata(key)
    }

    private fun recordedAt(file: File): Long =
        MediaInspector.containerDate(meta(file, MediaMetadataRetriever.METADATA_KEY_DATE))

    /** Whether [text] is anywhere in [file], in whichever box a muxer may have put it. */
    private fun holds(file: File, text: String): Boolean {
        val bytes = file.readBytes()
        val needle = text.toByteArray(Charsets.UTF_8)
        return (0..bytes.size - needle.size).any { start ->
            needle.indices.all { bytes[start + it] == needle[it] }
        }
    }

    private fun column(uri: Uri, name: String): String? = context.contentResolver.query(
        uri,
        arrayOf(name),
        null,
        null,
        null,
    )!!.use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    private fun dateTaken(uri: Uri): Long =
        column(uri, MediaStore.Video.Media.DATE_TAKEN)?.toLong() ?: 0

    /** Has MediaProvider scan the published file again, as it does after a reboot. */
    @Suppress("DEPRECATION") // DATA: the scanner takes a path.
    private fun scan(uri: Uri) {
        val path = column(uri, MediaStore.MediaColumns.DATA)!!
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
            scanned.countDown()
        }
        assertTrue("scan of $path", scanned.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun anExportIsDatedNowWithoutItsClipsTags() = runBlocking {
        val recording = phoneRecording()
        // Android reads when and where the clip was recorded, and which phone recorded it.
        assertEquals(RECORDED_AT, recordedAt(recording))
        assertNotNull(meta(recording, MediaMetadataRetriever.METADATA_KEY_LOCATION))
        for (value in PHONE_TAGS.values) assertTrue(value, holds(recording, value))

        val clip = MediaInspector.inspect(context, Uri.fromFile(recording))
        val project = Project(
            id = "metadata-test",
            name = "Metadata test",
            media = listOf(clip),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 2_000_000, MediaRef(clip.id, 0, 2_000_000)),
                    ),
                ),
            ),
        )
        val renderer = ExportRenderer(context, MediaCache(File(root, "cache")))
        // MP4 times are in whole seconds.
        val from = System.currentTimeMillis() / 1_000 * 1_000
        val video = renderer
            .render(project, ExportSettings(resolution = Resolution.HD), PauseGate()) {}
            .file
        val uri = renderer.publish(video, "clipra-metadata-test.mp4")
        val to = System.currentTimeMillis()
        try {
            val created = recordedAt(video)
            val location = meta(video, MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val tags = (PHONE_TAGS.keys + PHONE_TAGS.values).filter { holds(video, it) }
            val published = dateTaken(uri)
            scan(uri)
            val scanned = dateTaken(uri)
            Log.i(
                "ExportedMetadataTest",
                "exported from $from to $to: created $created, location $location, tags $tags, " +
                    "date taken $published, after a scan $scanned",
            )

            assertTrue("created at $created, exported from $from to $to", created in from..to)
            assertNull(location)
            assertEquals(emptyList<String>(), tags)
            // The gallery dates it now, as soon as it is published and after a scan.
            assertTrue("date taken $published, from $from to $to", published in from..to)
            assertTrue("scanned date taken $scanned, from $from to $to", scanned in from..to)
        } finally {
            context.contentResolver.delete(uri, null, null)
            video.delete()
            root.deleteRecursively()
        }
        Unit
    }

    private companion object {
        /** 2025-09-13 13:39:52 UTC, the first clip's recording date in #219. */
        const val RECORDED_AT = 1_757_770_792_000L

        val PHONE_TAGS = mapOf(
            "com.android.manufacturer" to "Xiaomi",
            "com.android.model" to "25010PN30G",
            "com.xiaomi.product.marketname" to "Xiaomi 15 Ultra",
        )
    }
}

package tech.idct.clipra.core.engine.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import tech.idct.clipra.core.model.export.ExportExtra
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.Otio
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** A file saved next to an export (#59), as Saved to Gallery lists it. */
data class SavedExtra(val extra: ExportExtra, val fileName: String, val uri: String)

/**
 * Saves the files that go with an export (#59), named like the video: the cover frame as a .jpg
 * in Pictures/Clipra, the sound alone as an .m4a in Music/Clipra, and the captions (.srt) and the
 * project (.otio) in Download/Clipra, where each kind of file belongs on Android. Each is saved
 * on its own: one that can't be made leaves the video and the others as they are.
 */
internal class ExportExtras(private val context: Context) {
    suspend fun save(
        project: Project,
        settings: ExportSettings,
        video: File,
        baseName: String,
        cover: suspend () -> Bitmap,
    ): List<SavedExtra> = settings.savedExtras(project).mapNotNull { extra ->
        try {
            when (extra) {
                ExportExtra.Captions -> text(extra, baseName, Srt.write(Srt.cues(project)))

                ExportExtra.Project -> text(extra, baseName, Otio.write(project))

                ExportExtra.Cover -> {
                    val frame = cover()
                    insert(extra, baseName) { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }

                ExportExtra.Audio -> audio(video, baseName)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun text(extra: ExportExtra, baseName: String, text: String) =
        insert(extra, baseName) { it.write(text.toByteArray(Charsets.UTF_8)) }

    /** The video's sound track copied as it is into an .m4a; null when the video is silent. */
    private suspend fun audio(video: File, baseName: String): SavedExtra? {
        val sound = File(video.parentFile, "${video.nameWithoutExtension}.m4a")
        try {
            if (!withContext(Dispatchers.IO) { copySound(video, sound) }) return null
            return insert(ExportExtra.Audio, baseName) { out ->
                sound.inputStream().use { it.copyTo(out) }
            }
        } finally {
            sound.delete()
        }
    }

    private suspend fun insert(
        extra: ExportExtra,
        baseName: String,
        write: (OutputStream) -> Unit,
    ): SavedExtra = withContext(Dispatchers.IO) {
        val (collection, folder) = when (extra) {
            ExportExtra.Cover -> MediaStore.Images.Media.getContentUri(VOLUME) to
                Environment.DIRECTORY_PICTURES

            ExportExtra.Audio -> MediaStore.Audio.Media.getContentUri(VOLUME) to
                Environment.DIRECTORY_MUSIC

            ExportExtra.Captions, ExportExtra.Project ->
                MediaStore.Downloads.getContentUri(VOLUME) to Environment.DIRECTORY_DOWNLOADS
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.${extra.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, extra.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Clipra")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Not saved")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        SavedExtra(extra, displayName(uri) ?: "$baseName.${extra.extension}", uri.toString())
    }

    /** The name MediaStore gave [uri]; it adds a number when the name was taken. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    companion object {
        private const val VOLUME = MediaStore.VOLUME_EXTERNAL_PRIMARY

        /** Copies the first sound track of [video] into [out] without re-encoding it. */
        fun copySound(video: File, out: File): Boolean {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return false
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val index = muxer.addTrack(format)
                    muxer.start()
                    val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        SAMPLE_BUFFER
                    }
                    val buffer = ByteBuffer.allocate(maxOf(size, SAMPLE_BUFFER))
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) break
                        val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(
                            0,
                            read,
                            extractor.sampleTime,
                            if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        muxer.writeSampleData(index, buffer, info)
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
                return true
            } finally {
                extractor.release()
            }
        }

        private const val SAMPLE_BUFFER = 256 * 1024
    }
}

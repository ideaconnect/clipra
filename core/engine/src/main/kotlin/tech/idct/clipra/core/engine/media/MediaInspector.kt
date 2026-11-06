package tech.idct.clipra.core.engine.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Reads what Clipra needs to know about a picked photo, video or sound: a content fingerprint
 * (the media id, which keys proxies and thumbnails) and its metadata, with the size and the
 * date taken that describe it once the file is missing (#216). Nothing is copied.
 */
object MediaInspector {
    /** Bytes read from each end of the file for the fingerprint. */
    private const val SAMPLE_BYTES = 1 shl 20

    fun inspect(context: Context, uri: Uri): MediaAsset {
        val resolver = context.contentResolver
        // A file in app storage (a recording, #41) has no provider to say its type.
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString()),
            ).orEmpty()
        val kind = when {
            mime.startsWith("image/") -> MediaKind.Photo
            mime.startsWith("audio/") -> MediaKind.Audio
            else -> MediaKind.Video
        }
        val (id, size) = sample(context, uri)
        val base = MediaAsset(
            id = id,
            uri = uri.toString(),
            kind = kind,
            displayName = displayName(context, uri),
            sizeBytes = size,
            takenAtMillis = dateTaken(context, uri),
        )
        return when (kind) {
            MediaKind.Photo -> withPhotoSize(context, uri, base)
            else -> withAvMetadata(context, uri, base)
        }
    }

    /**
     * SHA-256 over the size and the first and last megabyte: stable for the same file wherever
     * it is, cheap for a 4K video, and different for different recordings.
     */
    fun fingerprint(context: Context, uri: Uri): String = sample(context, uri).first

    /** The fingerprint and the size in bytes, from one look at the file. */
    private fun sample(context: Context, uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val size = context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val bytes = descriptor.statSize
            digest.update(bytes.toString().toByteArray())
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(SAMPLE_BYTES)
                for (position in listOf(0L, maxOf(0L, bytes - SAMPLE_BYTES))) {
                    buffer.clear()
                    channel.position(position)
                    channel.read(buffer)
                    buffer.flip()
                    digest.update(buffer)
                }
            }
            bytes
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to maxOf(0L, size)
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()

    /**
     * When MediaStore says the photo or video was taken: the photo picker's and MediaStore's
     * links both answer it. Other providers (the Files app) don't know the column; 0 then.
     */
    private fun dateTaken(context: Context, uri: Uri): Long {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return 0
        val column = arrayOf(MediaStore.MediaColumns.DATE_TAKEN)
        return try {
            context.contentResolver.query(uri, column, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) {
            // MediaStore refuses an unknown column; some providers refuse any query.
            0L
        }
    }

    private fun withPhotoSize(context: Context, uri: Uri, base: MediaAsset): MediaAsset {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        val taken = base.takenAtMillis.takeIf { it > 0 } ?: exifDateTaken(context, uri)
        // Stored upright, as the photo is drawn.
        val swap = Thumbnails.swapsSides(Thumbnails.exifOrientation(context, uri))
        return if (swap) {
            base.copy(width = options.outHeight, height = options.outWidth, takenAtMillis = taken)
        } else {
            base.copy(width = options.outWidth, height = options.outHeight, takenAtMillis = taken)
        }
    }

    private fun exifDateTaken(context: Context, uri: Uri): Long = try {
        context.contentResolver.openInputStream(uri)?.use {
            val exif = ExifInterface(it)
            exifDate(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
        } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun withAvMetadata(context: Context, uri: Uri, base: MediaAsset): MediaAsset =
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val hasVideo = meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            base.copy(
                durationUs = durationMs * 1_000,
                width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0,
                height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0,
                rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                kind = if (hasVideo) MediaKind.Video else MediaKind.Audio,
                // A camera writes when it recorded into the file; a sound has no date taken.
                takenAtMillis = when {
                    !hasVideo -> base.takenAtMillis
                    base.takenAtMillis > 0 -> base.takenAtMillis
                    else -> containerDate(meta(MediaMetadataRetriever.METADATA_KEY_DATE))
                },
            )
        }

    /**
     * The recording date a video's container holds, as `MediaMetadataRetriever` gives it
     * ("20261105T143000.000Z", in UTC), in milliseconds; 0 when there is none. Files whose
     * date was never set say 1904 or 1970, the container's and Unix's zero.
     */
    internal fun containerDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val date = try {
            LocalDateTime.parse(text.trim(), CONTAINER_DATE)
        } catch (_: DateTimeParseException) {
            return 0
        }
        return date.toInstant(ZoneOffset.UTC).toEpochMilli().takeIf { date.year > 1970 } ?: 0
    }

    /**
     * When a photo was taken from its EXIF ("2026:11:05 14:30:00"), in milliseconds: with its
     * offset ("+01:00") when the camera wrote one, else in [zone], as cameras write local time.
     */
    internal fun exifDate(
        text: String?,
        offset: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (text.isNullOrBlank()) return 0
        val date = try {
            LocalDateTime.parse(text.trim(), EXIF_DATE)
        } catch (_: DateTimeParseException) {
            return 0
        }
        if (date.year <= 1970) return 0
        val zoned = offset?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                OffsetDateTime.of(date, ZoneOffset.of(it)).toInstant()
            } catch (_: Exception) {
                null
            }
        } ?: date.atZone(zone).toInstant()
        return zoned.toEpochMilli()
    }

    private val CONTAINER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]'Z'")
    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
}

package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.security.MessageDigest

/**
 * Reads what Clipra needs to know about a picked photo, video or sound: a content fingerprint
 * (the media id, which keys proxies and thumbnails) and its metadata. Nothing is copied.
 */
object MediaInspector {
    /** Bytes read from each end of the file for the fingerprint. */
    private const val SAMPLE_BYTES = 1 shl 20

    fun inspect(context: Context, uri: Uri): MediaAsset {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val kind = when {
            mime.startsWith("image/") -> MediaKind.Photo
            mime.startsWith("audio/") -> MediaKind.Audio
            else -> MediaKind.Video
        }
        val base = MediaAsset(
            id = fingerprint(context, uri),
            uri = uri.toString(),
            kind = kind,
            displayName = displayName(context, uri),
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
    fun fingerprint(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val size = descriptor.statSize
            digest.update(size.toString().toByteArray())
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(SAMPLE_BYTES)
                for (position in listOf(0L, maxOf(0L, size - SAMPLE_BYTES))) {
                    buffer.clear()
                    channel.position(position)
                    channel.read(buffer)
                    buffer.flip()
                    digest.update(buffer)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()

    private fun withPhotoSize(context: Context, uri: Uri, base: MediaAsset): MediaAsset {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return base.copy(width = options.outWidth, height = options.outHeight)
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
            )
        }
}

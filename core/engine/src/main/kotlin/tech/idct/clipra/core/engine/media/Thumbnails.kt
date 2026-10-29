package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.io.File

/** Stills for Home, the media bin and the timeline's filmstrips, cached as JPEG files. */
object Thumbnails {
    private const val THUMBNAIL_SIZE = 320
    private const val FILMSTRIP_HEIGHT = 96

    /** The cached thumbnail of [asset], made on first use; null for sound or unreadable media. */
    fun thumbnail(context: Context, asset: MediaAsset, cache: MediaCache): File? {
        val file = cache.thumbnail(asset.id)
        if (file.exists()) return file
        val bitmap = when (asset.kind) {
            MediaKind.Photo -> decodePhoto(context, Uri.parse(asset.uri), THUMBNAIL_SIZE)

            MediaKind.Video -> frame(
                context,
                Uri.parse(asset.uri),
                0,
                THUMBNAIL_SIZE,
                THUMBNAIL_SIZE,
            )

            MediaKind.Audio -> null
        } ?: return null
        return save(bitmap, file)
    }

    /**
     * Filmstrip frame [index] (one per second of the media), read from the proxy when there is
     * one; null when the media can't be read.
     */
    fun filmstripFrame(context: Context, asset: MediaAsset, cache: MediaCache, index: Int): File? {
        val file = cache.filmstripFrame(asset.id, index)
        if (file.exists()) return file
        val proxy = cache.proxy(asset.id)
        val uri = if (proxy.exists()) Uri.fromFile(proxy) else Uri.parse(asset.uri)
        val width = if (asset.height > 0) asset.width * FILMSTRIP_HEIGHT / asset.height else 0
        val timeUs = index * MediaCache.FILMSTRIP_STEP_US
        val bitmap =
            frame(context, uri, timeUs, width.coerceAtLeast(1), FILMSTRIP_HEIGHT) ?: return null
        return save(bitmap, file)
    }

    /**
     * A photo decoded no larger than [maxSide] on its long side, keeping memory small, and turned
     * upright by its EXIF orientation, as galleries show it.
     */
    fun decodePhoto(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val orientation = exifOrientation(context, uri)
        if (orientation <= ExifInterface.ORIENTATION_NORMAL) return decoded
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)

            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /** The EXIF orientation of a photo, or [ExifInterface.ORIENTATION_UNDEFINED]. */
    fun exifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        } ?: ExifInterface.ORIENTATION_UNDEFINED
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    /** Whether an EXIF orientation turns the picture by a quarter, swapping width and height. */
    fun swapsSides(orientation: Int): Boolean = orientation in setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

    private fun frame(context: Context, uri: Uri, timeUs: Long, width: Int, height: Int): Bitmap? =
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    height,
                )
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun save(bitmap: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".part")
        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        partial.renameTo(file)
        return file
    }
}

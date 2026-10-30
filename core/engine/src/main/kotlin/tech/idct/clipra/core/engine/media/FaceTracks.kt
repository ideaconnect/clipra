package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.FaceSample
import tech.idct.clipra.core.model.FaceTracking
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.mediaTimeAt
import tech.idct.clipra.ml.vision.Face
import tech.idct.clipra.ml.vision.FaceDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * Follow face (#52) on the device: the face detector looks at a clip ten times a second (in its
 * media, from the proxy when there is one) and follows the face nearest the one before.
 * `FaceTracking` then fills the gaps and smooths the path. Nothing leaves the device.
 */
@Singleton
class FaceTracks @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    /**
     * Where the face is along [clip] (clip time), sample by sample; null where none was seen.
     * [onProgress] gets 0 to 1; [stopped] ends it early (null comes back).
     */
    fun find(
        asset: MediaAsset,
        clip: Clip,
        onProgress: (Float) -> Unit = {},
        stopped: () -> Boolean = { false },
    ): List<FaceSample>? = FaceDetector(context).use { detector ->
        if (asset.kind == MediaKind.Photo) {
            val photo = Thumbnails.decodePhoto(context, Uri.parse(asset.uri), PHOTO_SIDE)
                ?: return null
            onProgress(1f)
            return listOf(FaceSample(0, pick(detector.detect(photo), null)))
        }
        val retriever = MediaMetadataRetriever()
        try {
            val proxy = cache.proxy(asset.id)
            if (proxy.exists()) {
                retriever.setDataSource(proxy.path)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.uri))
            }
            val count = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val durationUs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1_000)?.takeIf { it > 0 } ?: return null
            // The frame each sample shows (any speed, forwards or reversed).
            val times = (0 until clip.durationUs step FaceTracking.SAMPLE_US).toList()
            val wanted = times.map { timeUs ->
                val mediaUs = clip.mediaTimeAt(timeUs) ?: 0
                (mediaUs * count / durationUs).toInt().coerceIn(0, count - 1)
            }
            // Decoded in order, a few at a time, which is far quicker than seeking each.
            val faces = HashMap<Int, List<Face>>()
            val needed = wanted.toSortedSet()
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            var index = needed.first()
            val lastIndex = needed.last()
            while (index <= lastIndex) {
                if (stopped()) return null
                val batch = minOf(BATCH, lastIndex - index + 1)
                retriever.getFramesAtIndex(index, batch, params).forEachIndexed { i, frame ->
                    if (index + i in needed) {
                        val picture = upright(frame, asset)
                        val small = shrink(picture)
                        faces[index + i] = detector.detect(small)
                        if (small !== picture) small.recycle()
                        if (picture !== frame) picture.recycle()
                    }
                    frame.recycle()
                }
                index += batch
                onProgress(needed.headSet(index).size.toFloat() / needed.size)
            }
            onProgress(1f)
            // Follow the face in clip order.
            var last: FaceBox? = null
            times.mapIndexed { i, timeUs ->
                val box = pick(faces[wanted[i]].orEmpty(), last)
                if (box != null) last = box
                FaceSample(timeUs, box)
            }
        } finally {
            retriever.release()
        }
    }

    /** [picture] no larger than [FRAME_SIDE]; the detector looks at 128 × 128 anyway. */
    private fun shrink(picture: Bitmap): Bitmap {
        val scale = FRAME_SIDE.toFloat() / maxOf(picture.width, picture.height)
        if (scale >= 1f) return picture
        return Bitmap.createScaledBitmap(
            picture,
            maxOf(1, (picture.width * scale).toInt()),
            maxOf(1, (picture.height * scale).toInt()),
            true,
        )
    }

    /** The face to follow: nearest the last one, or the surest at the start. */
    private fun pick(faces: List<Face>, last: FaceBox?): FaceBox? {
        val boxes = faces.map {
            FaceBox(it.box.centerX(), it.box.centerY(), it.box.width(), it.box.height())
        }
        if (last == null) return boxes.firstOrNull()
        return boxes.minByOrNull { hypot(it.x - last.x, it.y - last.y) }
    }

    private companion object {
        const val FRAME_SIDE = 480

        /** Frames decoded at a time. */
        const val BATCH = 8
        const val PHOTO_SIDE = 1024
    }
}

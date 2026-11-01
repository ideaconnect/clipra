package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.FaceSample
import tech.idct.clipra.core.model.FaceTracking
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.core.model.mediaTimeAt
import tech.idct.clipra.ml.vision.Face
import tech.idct.clipra.ml.vision.FaceDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Follow face (#52) on the device: the face detector looks at a clip ten times a second (in its
 * media, from the proxy when there is one) and follows the face nearest the one before; a small
 * face is looked for in tiles of the frame too (#182). `FaceTracking` then fills the gaps and
 * smooths the path. On a steadied clip (#54) the faces are moved where the steadied picture
 * shows them. Nothing leaves the device.
 */
@Singleton
class FaceTracks @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    /**
     * Where the face is along [clip] (clip time), sample by sample; null where none was seen.
     * The face is the one nearest [near] at [atUs] (clip time; where the mask is, so a mask put
     * on a face follows that face), or the surest one there ([FaceTracking.follow]).
     * [onProgress] gets 0 to 1; [stopped] ends it early (null comes back).
     */
    fun find(
        asset: MediaAsset,
        clip: Clip,
        atUs: Long = 0,
        near: FaceBox? = null,
        onProgress: (Float) -> Unit = {},
        stopped: () -> Boolean = { false },
    ): List<FaceSample>? = FaceDetector(context).use { detector ->
        if (asset.kind == MediaKind.Photo) {
            val photo = Thumbnails.decodePhoto(context, Uri.parse(asset.uri), PHOTO_SIDE)
                ?: return null
            onProgress(1f)
            val seen = boxes(detect(detector, photo, photo, near))
            val box = FaceTracking.follow(listOf(seen), 0, near)[0]
            return listOf(FaceSample(0, box))
        }
        val retriever = MediaMetadataRetriever()
        try {
            val proxy = cache.proxy(asset.id)
            if (proxy.exists()) {
                retriever.setDataSource(proxy.path)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.uri))
            }
            val frameTimes = FrameTimes.of(context, if (proxy.exists()) proxy.path else asset.uri)
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
                val even = (mediaUs * count / durationUs).toInt()
                frameTimes.nearest(mediaUs, even).coerceIn(0, count - 1)
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
                        faces[index + i] = detect(detector, picture, small, near)
                        if (small !== picture) small.recycle()
                        if (picture !== frame) picture.recycle()
                    }
                    frame.recycle()
                }
                index += batch
                onProgress(needed.headSet(index).size.toFloat() / needed.size)
            }
            onProgress(1f)
            val anchor = times.indices.minByOrNull { abs(times[it] - atUs) } ?: 0
            val picked = FaceTracking.follow(
                times.indices.map { boxes(faces[wanted[it]].orEmpty()) },
                anchor,
                near,
            )
            val motion = Stabilizer.cached(cache.stabilize(asset.id))
            val steadied = motion?.over(clip)
            times.mapIndexed { i, timeUs ->
                val box = picked[i]
                val shown = if (box != null && steadied != null) {
                    val mediaUs = clip.mediaTimeAt(timeUs) ?: 0
                    Stabilizing.steady(box, steadied.at(mediaUs), steadied.zoom, motion.aspect)
                } else {
                    box
                }
                FaceSample(timeUs, shown)
            }
        } finally {
            retriever.release()
        }
    }

    /**
     * The faces in [picture]: in all of it, through [small] (the same picture, smaller); and
     * when the face under the mask ([near]) is too small to find that way, in tiles of [picture]
     * at its full size too (#182). The tiles find other faces and things like faces as well, but
     * Follow face takes the one nearest the mask.
     */
    private fun detect(
        detector: FaceDetector,
        picture: Bitmap,
        small: Bitmap,
        near: FaceBox?,
    ): List<Face> {
        val whole = detector.detect(small)
        val tiles = near?.let { FaceTracking.tiles(picture.width, picture.height, it) }.orEmpty()
        if (tiles.isEmpty()) return whole
        val tiled = tiles.flatMap { tile ->
            val region = Rect(tile.x, tile.y, tile.x + tile.side, tile.y + tile.side)
            detector.detect(picture, region = region)
        }
        return FaceDetector.merge(whole + tiled)
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

    /** The detector's faces as boxes, the surest first. */
    private fun boxes(faces: List<Face>): List<FaceBox> = faces.map {
        FaceBox(it.box.centerX(), it.box.centerY(), it.box.width(), it.box.height())
    }

    private companion object {
        const val FRAME_SIDE = 480

        /** Frames decoded at a time. */
        const val BATCH = 8
        const val PHOTO_SIDE = 1024
    }
}

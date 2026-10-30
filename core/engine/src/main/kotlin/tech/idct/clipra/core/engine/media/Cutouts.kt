package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.model.CutoutMath
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.ml.vision.SelfieSegmenter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Person cutout (#50) on the device: the selfie segmenter runs once over every frame of a
 * media (its proxy when there is one), each mask steadied by the one before
 * (`CutoutMath.smooth`), and the masks are kept in [MediaCache]. Clips show the cutout once
 * they are there. Nothing leaves the device.
 */
@Singleton
class Cutouts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val working = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** The media being worked on, with how far along (0 to 1). */
    val progress: StateFlow<Map<String, Float>> = working.asStateFlow()

    /** The masks of [asset], once they are made. */
    fun masks(asset: MediaAsset): CutoutMasks? = CutoutMasks.cached(cache.cutout(asset.id))

    /** Makes the masks in the background unless they are there (WorkManager, once per media). */
    fun request(asset: MediaAsset) {
        if (cache.cutout(asset.id).exists()) return
        CutoutWorker.enqueue(context, asset)
    }

    /**
     * Requests the masks of every clip with a person cutout, such as after the cache was cleared.
     * A chroma key (#51) needs none.
     */
    fun requestAll(project: Project) {
        for (clip in project.layers.flatMap { it.clips }) {
            if (clip.cutout == null || clip.cutout?.key != null) continue
            clip.source?.let { project.asset(it.mediaId) }?.let(::request)
        }
    }

    /**
     * Makes the masks now unless they are there; false when the media can't be read or
     * [stopped] said so. Safe to call from several threads.
     */
    fun prepare(asset: MediaAsset, stopped: () -> Boolean = { false }): Boolean =
        synchronized(locks.getOrPut(asset.id) { Any() }) {
            if (cache.cutout(asset.id).exists()) return true
            working.update { it + (asset.id to 0f) }
            try {
                val made = SelfieSegmenter(context).use { segmenter ->
                    CutoutMasks.Writer(cache.cutout(asset.id)).use { writer ->
                        val done = if (asset.kind == MediaKind.Photo) {
                            photo(asset, segmenter, writer)
                        } else {
                            video(asset, segmenter, writer, stopped)
                        }
                        if (done) writer.finish()
                        done
                    }
                }
                if (made) cache.voiceReady()
                made
            } catch (_: Exception) {
                false
            } finally {
                working.update { it - asset.id }
            }
        }

    private fun photo(
        asset: MediaAsset,
        segmenter: SelfieSegmenter,
        writer: CutoutMasks.Writer,
    ): Boolean {
        val image = Thumbnails.decodePhoto(context, Uri.parse(asset.uri), PHOTO_SIDE)
            ?: return false
        val mask = MaskMaker(segmenter)
        writer.add(0, mask.next(image))
        return true
    }

    private fun video(
        asset: MediaAsset,
        segmenter: SelfieSegmenter,
        writer: CutoutMasks.Writer,
        stopped: () -> Boolean,
    ): Boolean {
        val proxy = cache.proxy(asset.id)
        val retriever = MediaMetadataRetriever()
        try {
            if (proxy.exists()) {
                retriever.setDataSource(proxy.path)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.uri))
            }
            val count = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: return false
            val durationUs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1_000) ?: asset.durationUs
            val maker = MaskMaker(segmenter)
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            var index = 0
            while (index < count) {
                if (stopped()) return false
                val batch = minOf(BATCH, count - index)
                val frames = retriever.getFramesAtIndex(index, batch, params)
                frames.forEachIndexed { i, frame ->
                    val upright = upright(frame, asset)
                    // Frames are evenly spaced, as in the proxy.
                    val timeUs = (index + i).toLong() * durationUs / count
                    writer.add(timeUs, maker.next(upright))
                    if (upright !== frame) upright.recycle()
                    frame.recycle()
                }
                index += batch
                working.update { it + (asset.id to index.toFloat() / count) }
            }
            return true
        } finally {
            retriever.release()
        }
    }

    /** [frame] turned upright when the decoder left the video's rotation to the player. */
    private fun upright(frame: Bitmap, asset: MediaAsset): Bitmap {
        val shown = asset.displaySize
        val turned = asset.rotation % 180 != 0 && shown.width > 0 &&
            (frame.width > frame.height) != (shown.width > shown.height)
        if (!turned) return frame
        val matrix = Matrix().apply { postRotate(asset.rotation.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }

    /** Masks one frame after another, each steadied by the last. */
    private class MaskMaker(private val segmenter: SelfieSegmenter) {
        private val size = SelfieSegmenter.SIZE
        private val pixels = IntArray(size * size)
        private val current = FloatArray(size * size)
        private val steady = FloatArray(size * size)
        private var first = true

        fun next(frame: Bitmap): ByteArray {
            val square = Bitmap.createScaledBitmap(frame, size, size, true)
            square.getPixels(pixels, 0, size, 0, 0, size, size)
            if (square !== frame) square.recycle()
            segmenter.segment(pixels, current)
            if (first) {
                current.copyInto(steady)
                first = false
            } else {
                CutoutMath.smooth(steady, current, steady)
            }
            return ByteArray(size * size) {
                (steady[it] * 255).roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private companion object {
        /** Frames decoded at a time. */
        const val BATCH = 8

        /** Photos are decoded no larger than this before the model squeezes them. */
        const val PHOTO_SIDE = 1024
    }
}

/** Makes a media's person masks in the background, once per media id. */
@HiltWorker
class CutoutWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cutouts: Cutouts,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val asset = MediaAsset(
            id = inputData.getString(KEY_ID) ?: return Result.failure(),
            uri = inputData.getString(KEY_URI) ?: return Result.failure(),
            kind = MediaKind.valueOf(inputData.getString(KEY_KIND) ?: return Result.failure()),
            durationUs = inputData.getLong(KEY_DURATION, 0),
            width = inputData.getInt(KEY_WIDTH, 0),
            height = inputData.getInt(KEY_HEIGHT, 0),
            rotation = inputData.getInt(KEY_ROTATION, 0),
        )
        val made = withContext(Dispatchers.Default) { cutouts.prepare(asset) { isStopped } }
        return if (made || isStopped) Result.success() else Result.failure()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_KIND = "kind"
        private const val KEY_DURATION = "duration"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_ROTATION = "rotation"

        fun enqueue(context: Context, asset: MediaAsset) {
            val request = OneTimeWorkRequestBuilder<CutoutWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ID to asset.id,
                        KEY_URI to asset.uri,
                        KEY_KIND to asset.kind.name,
                        KEY_DURATION to asset.durationUs,
                        KEY_WIDTH to asset.width,
                        KEY_HEIGHT to asset.height,
                        KEY_ROTATION to asset.rotation,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("cutout-${asset.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}

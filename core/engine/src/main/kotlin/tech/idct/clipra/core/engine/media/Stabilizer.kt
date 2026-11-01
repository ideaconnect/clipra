package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
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
import tech.idct.clipra.core.model.CameraStep
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Stabilized
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.ml.vision.Gray
import tech.idct.clipra.ml.vision.Motion
import tech.idct.clipra.ml.vision.MotionEstimator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** A media's camera motion (#54): the picture's shape, and a step into each frame. */
data class CameraMotion(val aspect: Float, val steps: List<CameraStep>) {
    /** [clip] steadied over the part of the media it plays; null when it isn't stabilized. */
    fun over(clip: Clip): Stabilized? {
        val settings = clip.stabilize?.takeIf { clip.type == ClipType.Video } ?: return null
        return Stabilizing.stabilize(playedBy(clip), settings.strength, aspect)
    }

    /** The steps into the frames [clip] plays. */
    fun playedBy(clip: Clip): List<CameraStep> {
        val source = clip.source ?: return emptyList()
        val from = minOf(source.inPointUs, source.outPointUs)
        val to = maxOf(source.inPointUs, source.outPointUs)
        return steps.filter { it.timeUs in from..to }
    }
}

/**
 * Video stabilization's analysis on the device (#54): the media's proxy is read in order at
 * 320 px wide, and `MotionEstimator` finds the camera's motion from each frame to the next. The
 * steps are kept in [MediaCache] in fractions of the picture, keyed by media time, so they fit
 * the proxy and the original, and trims, speed and reverse. Nothing leaves the device.
 */
@Singleton
class Stabilizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val working = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val unreadable = MutableStateFlow<Set<String>>(emptySet())

    /** The media being analysed, with how far along (0 to 1). */
    val progress: StateFlow<Map<String, Float>> = working.asStateFlow()

    /** The media whose analysis failed since the app started. */
    val failed: StateFlow<Set<String>> = unreadable.asStateFlow()

    /** The camera motion of [asset], once it is known. */
    fun motion(asset: MediaAsset): CameraMotion? = cached(cache.stabilize(asset.id))

    /** Analyses [asset] in the background unless that is done (WorkManager, once per media). */
    fun request(asset: MediaAsset) {
        if (asset.kind != MediaKind.Video || cache.stabilize(asset.id).exists()) return
        unreadable.update { it - asset.id }
        StabilizeWorker.enqueue(context, asset)
    }

    /** Stops the analysis of [asset], if it is running. */
    fun cancel(asset: MediaAsset) = StabilizeWorker.cancel(context, asset)

    /** Requests the analysis of every stabilized clip, such as after the cache was cleared. */
    fun requestAll(project: Project) {
        for (clip in project.layers.flatMap { it.clips }) {
            if (clip.stabilize == null) continue
            clip.source?.let { project.asset(it.mediaId) }?.let(::request)
        }
    }

    /**
     * Analyses [asset] now unless that is done; false when it can't be read or [stopped] said
     * so. Safe to call from several threads.
     */
    fun prepare(asset: MediaAsset, stopped: () -> Boolean = { false }): Boolean =
        synchronized(locks.getOrPut(asset.id) { Any() }) {
            if (cache.stabilize(asset.id).exists()) return true
            working.update { it + (asset.id to 0f) }
            try {
                val motion = analyse(asset, stopped)
                if (motion == null) {
                    if (!stopped()) unreadable.update { it + asset.id }
                    return false
                }
                write(cache.stabilize(asset.id), motion)
                cache.voiceReady()
                true
            } catch (_: Exception) {
                unreadable.update { it + asset.id }
                false
            } finally {
                working.update { it - asset.id }
            }
        }

    private fun analyse(asset: MediaAsset, stopped: () -> Boolean): CameraMotion? {
        val retriever = MediaMetadataRetriever()
        try {
            val proxy = cache.proxy(asset.id)
            val source = if (proxy.exists()) proxy.path else asset.uri
            if (proxy.exists()) {
                retriever.setDataSource(proxy.path)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.uri))
            }
            val count = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 1 } ?: return null
            val frameTimes = FrameTimes.of(context, source)
            val durationUs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1_000)?.takeIf { it > 0 } ?: return null
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            // Frames are decoded in order; each batch is prepared and its steps estimated on
            // every core, an estimator per thread.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
            val pool = Executors.newFixedThreadPool(threads)
            val estimators = ThreadLocal.withInitial { MotionEstimator() }
            val steps = ArrayList<CameraStep>(count)
            var previous: MotionEstimator.Prepared? = null
            var aspect = 0f
            var index = 0
            try {
                while (index < count) {
                    if (stopped()) return null
                    val batch = minOf(BATCH, count - index)
                    val frames = retriever.getFramesAtIndex(index, batch, params)
                    val prepared = frames.map { frame ->
                        pool.submit<MotionEstimator.Prepared> {
                            val picture = upright(frame, asset)
                            estimators.get()!!.prepare(gray(picture)).also {
                                if (picture !== frame) picture.recycle()
                                frame.recycle()
                            }
                        }
                    }.map { it.get() }
                    aspect = prepared.first().picture.width.toFloat() /
                        prepared.first().picture.height
                    val motions = prepared.mapIndexed { i, frame ->
                        val before = if (i == 0) previous else prepared[i - 1]
                        pool.submit<Motion?> {
                            before?.let { estimators.get()!!.estimate(it, frame) }
                        }
                    }.map { it.get() }
                    motions.forEachIndexed { i, motion ->
                        val timeUs = frameTimes.at(
                            index + i,
                            (index + i).toLong() * durationUs / count,
                        )
                        val width = prepared[i].picture.width
                        steps += if (motion == null) {
                            CameraStep(timeUs, 0f, 0f, 0f, 1f)
                        } else {
                            CameraStep(
                                timeUs,
                                motion.dx / width,
                                motion.dy / width,
                                motion.angle,
                                motion.scale,
                            )
                        }
                    }
                    previous = prepared.last()
                    index += batch
                    working.update { it + (asset.id to index.toFloat() / count) }
                }
            } finally {
                pool.shutdownNow()
            }
            return CameraMotion(aspect, steps)
        } finally {
            retriever.release()
        }
    }

    /** [picture] as grey at [ANALYSIS_WIDTH] wide. */
    private fun gray(picture: Bitmap): Gray {
        val width = ANALYSIS_WIDTH
        val height = maxOf(2, picture.height * width / picture.width)
        val small = Bitmap.createScaledBitmap(picture, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== picture) small.recycle()
        return Gray.of(pixels, width, height)
    }

    private fun write(file: File, motion: CameraMotion) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.bufferedWriter().use { out ->
            out.write("aspect ${motion.aspect}\n")
            for (s in motion.steps) out.write("${s.timeUs} ${s.dx} ${s.dy} ${s.angle} ${s.scale}\n")
        }
        check(part.renameTo(file) || (file.delete() && part.renameTo(file)))
    }

    companion object {
        private val read = ConcurrentHashMap<String, CameraMotion>()

        /** The camera motion in [file], read once and shared; null while there is none. */
        fun cached(file: File): CameraMotion? {
            if (!file.exists()) {
                read.remove(file.path)
                return null
            }
            read[file.path]?.let { return it }
            return readFile(file)?.also { read[file.path] = it }
        }

        private fun readFile(file: File): CameraMotion? = try {
            val lines = file.readLines()
            val aspect = lines.first().removePrefix("aspect ").toFloat()
            val steps = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val v = line.split(' ')
                CameraStep(
                    v[0].toLong(),
                    v[1].toFloat(),
                    v[2].toFloat(),
                    v[3].toFloat(),
                    v[4].toFloat(),
                )
            }
            CameraMotion(aspect, steps)
        } catch (_: Exception) {
            null
        }

        /** The width frames are analysed at. */
        private const val ANALYSIS_WIDTH = 320

        /** Frames decoded at a time. */
        private const val BATCH = 12

        /** Threads that prepare frames and estimate steps. */
        private const val MAX_THREADS = 6
    }
}

/** Analyses a media's camera motion in the background, once per media id. */
@HiltWorker
class StabilizeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val stabilizer: Stabilizer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val asset = MediaAsset(
            id = inputData.getString(KEY_ID) ?: return Result.failure(),
            uri = inputData.getString(KEY_URI) ?: return Result.failure(),
            kind = MediaKind.Video,
            durationUs = inputData.getLong(KEY_DURATION, 0),
            width = inputData.getInt(KEY_WIDTH, 0),
            height = inputData.getInt(KEY_HEIGHT, 0),
            rotation = inputData.getInt(KEY_ROTATION, 0),
        )
        val done = withContext(Dispatchers.Default) { stabilizer.prepare(asset) { isStopped } }
        return if (done || isStopped) Result.success() else Result.failure()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_DURATION = "duration"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_ROTATION = "rotation"

        fun enqueue(context: Context, asset: MediaAsset) {
            val request = OneTimeWorkRequestBuilder<StabilizeWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ID to asset.id,
                        KEY_URI to asset.uri,
                        KEY_DURATION to asset.durationUs,
                        KEY_WIDTH to asset.width,
                        KEY_HEIGHT to asset.height,
                        KEY_ROTATION to asset.rotation,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("stabilize-${asset.id}", ExistingWorkPolicy.KEEP, request)
        }

        /** Cancels the analysis of [asset], if it is running. */
        fun cancel(context: Context, asset: MediaAsset) {
            WorkManager.getInstance(context).cancelUniqueWork("stabilize-${asset.id}")
        }
    }
}

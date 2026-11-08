package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.idct.clipra.core.engine.render.CompositionFactory
import tech.idct.clipra.core.engine.render.MediaSources
import tech.idct.clipra.core.engine.render.RenderClip
import tech.idct.clipra.core.engine.render.RenderLayer
import tech.idct.clipra.core.engine.render.RenderSource
import tech.idct.clipra.core.engine.render.RenderTimeline
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * The picture of reversed clips for the preview (#229): the stretch of media a clip plays, laid
 * out back to front in [MediaCache], so the preview plays it forwards like any other clip, in
 * real time. Read back to front, the media costs more decoding than playing it forwards
 * (`ReversedFrameSource`); the copy costs the same.
 *
 * Each copy is made from the proxy (or from the original when it needs none) at the proxy's size
 * and the media's own frame rate, so speeds and ramps keep every frame: the layer compositor
 * reads the media back to front, and Transformer encodes it as H.264 with a key frame every
 * second, as proxies are. One is made at a time, in the background; [MediaCache.voiceVersion]
 * changes when it is ready. The export reads the original instead. Nothing leaves the device.
 */
@Singleton
@OptIn(UnstableApi::class)
class ReversedVideos @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val resolver = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 1)

    /** Copies queued or being made, and copies that failed in this run of the app, by file. */
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Queues the copy of every reversed video clip in [project] that has none yet, and returns.
     * Media whose proxy is still being made waits for it: ask again once it is ready.
     */
    fun requestAll(project: Project) {
        for (clip in project.layers.flatMap { it.clips }) {
            val job = jobOf(project, clip) ?: continue
            val path = job.file.path
            if (job.file.exists() || path in failed || !queued.add(path)) continue
            WORKER.execute {
                try {
                    if (!make(job)) failed += path
                } finally {
                    queued -= path
                }
            }
        }
    }

    /**
     * Makes [clip]'s copy now unless it is there, blocking; not on the main thread. True when
     * it is there or none is needed (clips playing forwards, held frames, photos), false when
     * it can't be made yet.
     */
    fun prepare(project: Project, clip: Clip): Boolean {
        if (!needsCopy(clip)) return true
        val job = jobOf(project, clip) ?: return false
        return make(job)
    }

    /** What one copy is made of: [toUs] - [fromUs] of [path], whose media lasts [mediaUs]. */
    private class Job(
        val file: File,
        val path: String,
        val fromUs: Long,
        val toUs: Long,
        val mediaUs: Long,
    )

    private fun needsCopy(clip: Clip): Boolean = clip.type == ClipType.Video &&
        clip.speed.reverse && (clip.speed.rate > 0 || clip.speed.ramp != null)

    private fun jobOf(project: Project, clip: Clip): Job? {
        if (!needsCopy(clip)) return null
        val ref = clip.source ?: return null
        if (ref.outPointUs <= ref.inPointUs) return null
        val asset = project.asset(ref.mediaId) ?: return null
        // Made from the original, a 4K copy would decode at full size for minutes.
        if (ProxyGenerator.needsProxy(asset) && !cache.proxy(asset.id).exists()) return null
        val path = resolver.source(asset) ?: return null
        val file = cache.reversedVideo(asset.id, ref.inPointUs, ref.outPointUs)
        return Job(file, path, ref.inPointUs, ref.outPointUs, asset.durationUs)
    }

    private fun make(job: Job): Boolean = synchronized(LOCKS.getOrPut(job.file.path) { Any() }) {
        if (job.file.exists()) return@synchronized true
        try {
            runBlocking { generate(job) }
            cache.voiceReady()
            true
        } catch (e: Exception) {
            Log.w(TAG, "No reversed copy ${job.file.name}", e)
            false
        }
    }

    private suspend fun generate(job: Job) {
        val shape = shapeOf(job.path)
        val spanUs = job.toUs - job.fromUs
        val clip = RenderClip(
            id = "reversed",
            startUs = 0,
            durationUs = spanUs,
            source = RenderSource.Video(
                job.path,
                job.fromUs,
                job.mediaUs.takeIf { it > 0 } ?: Long.MAX_VALUE,
                hasAudio = false,
            ),
            speed = Speed(reverse = true),
            sourceSpanUs = spanUs,
        )
        val timeline = RenderTimeline(
            width = shape.width,
            height = shape.height,
            frameRate = shape.frameRate,
            durationUs = spanUs,
            background = Color.BLACK,
            layers = listOf(RenderLayer(clips = listOf(clip))),
        )
        val composition = CompositionFactory.create(context, timeline, includeAudio = false)
        job.file.parentFile?.mkdirs()
        val partial = File(job.file.path + ".part").apply { delete() }
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        try {
            suspendCancellableCoroutine { continuation ->
                // Transformer runs on the thread that builds it; the main looper is always there.
                main.post {
                    transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder()
                                        .setiFrameIntervalSeconds(1f)
                                        .build(),
                                )
                                .build(),
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(exportException)
                                }
                            }
                        })
                        .build()
                        .also { it.start(composition, partial.path) }
                }
                continuation.invokeOnCancellation { main.post { transformer?.cancel() } }
            }
            check(partial.renameTo(job.file)) { "Couldn't move the reversed copy into place" }
        } finally {
            partial.delete()
        }
    }

    /** The size and frame rate of a copy of [path]: its own, at most proxy size. */
    private class Shape(val width: Int, val height: Int, val frameRate: Int)

    private fun shapeOf(path: String): Shape {
        val extractor = MediaSources.open(context, path)
        try {
            val track = (0 until extractor.trackCount).first {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/")
            }
            val format = extractor.getTrackFormat(track)
            val rotation = format.intOrZero(MediaFormat.KEY_ROTATION)
            val rawWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val width = if (rotation % 180 == 0) rawWidth else rawHeight
            val height = if (rotation % 180 == 0) rawHeight else rawWidth
            val scale = minOf(1f, ProxyGenerator.SHORT_SIDE.toFloat() / minOf(width, height))
            fun even(side: Int) = maxOf(2, (side * scale / 2).roundToInt() * 2)
            extractor.selectTrack(track)
            return Shape(even(width), even(height), frameRateOf(extractor, format))
        } finally {
            extractor.release()
        }
    }

    /** The file's frame rate, or its frames over its length where it doesn't say. */
    private fun frameRateOf(extractor: MediaExtractor, format: MediaFormat): Int {
        val stated = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat()
        } else {
            null
        }
        val rate = stated?.takeIf { it > 0f } ?: run {
            var frames = 0
            var lastUs = 0L
            while (extractor.sampleTime >= 0) {
                frames++
                lastUs = maxOf(lastUs, extractor.sampleTime)
                extractor.advance()
            }
            if (frames > 1 && lastUs > 0) (frames - 1) * 1e6f / lastUs else DEFAULT_FPS.toFloat()
        }
        return rate.roundToInt().coerceIn(1, MAX_FPS)
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private companion object {
        const val TAG = "ReversedVideos"
        const val DEFAULT_FPS = 30
        const val MAX_FPS = 120

        /** One copy at a time: each holds a decoder, an encoder and its kept frames. */
        val WORKER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "clipra-reversed").apply { isDaemon = true }
        }

        /** Shared by every instance: a test's and the editor's may make the same file. */
        val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

package tech.idct.clipra.core.engine.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.SoundAnalyzer
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.render.CompositionFactory
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.engine.render.RenderTimeline
import tech.idct.clipra.core.engine.render.StillRenderer
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why an export failed, in words the export screen can show. */
enum class ExportFailure {
    /** The encoder can't do this codec, size or frame rate. */
    Unsupported,

    /** A clip's media couldn't be read. */
    MissingMedia,

    /** No room left to save the video. */
    Storage,
    Other,
}

class ExportFailedException(val failure: ExportFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/**
 * Renders a project from its original media (#57): the same layer compositor as the preview, at
 * the export size, frame rate, codec and bitrate of [ExportSettings], with Transformer. The video
 * is written to a cache file, then published to MediaStore in Movies/Clipra. Cleaned voices
 * (#39) that are not ready yet are made first, so the export never has the noisy sound.
 */
@OptIn(UnstableApi::class)
class ExportRenderer(
    private val context: Context,
    private val cache: MediaCache,
    private val voices: VoiceCleaner? = null,
    private val cutouts: Cutouts? = null,
) {
    /** The rendered file, before it is published. */
    data class Rendered(val file: File, val width: Int, val height: Int)

    /**
     * Renders [project] to a cache file. [onProgress] gets 0 to 1 on the main thread; closing
     * [gate] pauses. Cancelling the coroutine cancels the export.
     */
    suspend fun render(
        project: Project,
        settings: ExportSettings,
        gate: PauseGate,
        onProgress: (Float) -> Unit,
    ): Rendered {
        val size = settings.outputSize(project)
        voices?.let { prepareVoices(it, project) }
        cutouts?.let { prepareCutouts(it, project) }
        val timeline = timeline(project, settings)
        val composition = withContext(Dispatchers.IO) {
            CompositionFactory.create(
                context,
                timeline,
                gate = gate,
            )
        }
        val output = File(context.cacheDir, "exports/${project.id}.mp4").apply {
            parentFile?.mkdirs()
            delete()
        }
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        val progress = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progress.progress / 100f)
                }
                main.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        try {
            suspendCancellableCoroutine { continuation ->
                main.post {
                    transformer = buildTransformer(settings, continuation).also {
                        it.start(composition, output.path)
                    }
                    main.post(poll)
                }
                continuation.invokeOnCancellation {
                    main.post { transformer?.cancel() }
                    gate.open()
                }
            }
        } finally {
            main.removeCallbacks(poll)
        }
        return Rendered(output, size.width, size.height)
    }

    /**
     * Copies [file] into the gallery (Movies/Clipra) as [displayName]; returns its content URI.
     * The file stays, for the extras made from it (#59); the caller deletes it.
     */
    suspend fun publish(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MimeTypes.VIDEO_MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Clipra")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(
            collection,
            values,
        ) ?: throw ExportFailedException(ExportFailure.Storage)
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: java.io.IOException) {
            resolver.delete(uri, null, null)
            throw ExportFailedException(ExportFailure.Storage, e)
        }
        uri
    }

    /**
     * The frame at [ExportSettings.coverUs] as the video shows it, at the export size: the cover
     * saved next to it (#59).
     */
    suspend fun cover(project: Project, settings: ExportSettings): Bitmap {
        val timeline = timeline(project, settings)
        val stills = StillRenderer(context)
        try {
            val at = settings.coverUs.coerceIn(0, maxOf(0, timeline.durationUs - 1))
            return stills.render(timeline, at)
        } finally {
            stills.release()
        }
    }

    /** The project at the export size from original media, captions only when burned in. */
    private fun timeline(project: Project, settings: ExportSettings): RenderTimeline {
        val size = settings.outputSize(project)
        val media = ProjectMediaResolver(
            context,
            cache,
            forExport = true,
            maxImageSide = maxOf(size.width, size.height),
        )
        return ProjectRender.timeline(
            settings.drawn(project),
            media,
            size.width,
            size.height,
            settings.frameRate.fps,
        )
    }

    private fun buildTransformer(
        settings: ExportSettings,
        continuation: CancellableContinuation<Unit>,
    ): Transformer = Transformer.Builder(context)
        .setVideoMimeType(settings.codec.mimeType)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(settings.videoBitrate).build(),
                )
                .build(),
        )
        // A paused export sends no samples for a while; that is not an error.
        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ExportFailedException(
                            failureOf(exportException),
                            exportException,
                        ),
                    )
                }
            }
        })
        .build()

    private fun failureOf(e: ExportException): ExportFailure = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> ExportFailure.Unsupported

        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION,
        ExportException.ERROR_CODE_IO_UNSPECIFIED,
        -> ExportFailure.MissingMedia

        ExportException.ERROR_CODE_MUXING_FAILED -> ExportFailure.Storage

        else -> ExportFailure.Other
    }

    /**
     * Every clip's cleaned voice (#39) and noise reduction (#38), made now where the background
     * work has not finished.
     */
    private suspend fun prepareVoices(cleaner: VoiceCleaner, project: Project) =
        withContext(Dispatchers.Default) {
            for (clip in project.layers.flatMap { it.clips }) {
                val cleanup = clip.audio.voiceCleanup
                val noise = clip.audio.noiseReduction
                if (cleanup == null && noise == null) continue
                val asset = clip.source?.let { project.asset(it.mediaId) } ?: continue
                if (!asset.hasAudio && clip.type != ClipType.Audio) continue
                ensureActive()
                cleaner.prepare(asset, cleanup, noise) { !isActive }
            }
            // The speech of every voice that ducks the others (#40).
            val analyzer = SoundAnalyzer(cache, cleaner)
            for (clip in project.layers.flatMap { it.clips }) {
                if (clip.audio.ducking == null) continue
                val asset = clip.source?.let { project.asset(it.mediaId) } ?: continue
                ensureActive()
                analyzer.prepareSpeech(asset)
            }
        }

    /** The person masks (#50) of every cut-out clip, made now where the background work hasn't. */
    private suspend fun prepareCutouts(cutouts: Cutouts, project: Project) =
        withContext(Dispatchers.Default) {
            for (clip in project.layers.flatMap { it.clips }) {
                if (clip.cutout == null || clip.cutout?.key != null) continue
                val asset = clip.source?.let { project.asset(it.mediaId) } ?: continue
                ensureActive()
                cutouts.prepare(asset) { !isActive }
            }
        }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

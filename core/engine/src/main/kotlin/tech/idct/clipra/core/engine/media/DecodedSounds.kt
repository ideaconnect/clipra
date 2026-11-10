package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExperimentalAnalyzerModeFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.idct.clipra.core.engine.render.MediaSources
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The media's sound decoded once to 16-bit PCM WAV below Android 12 (#268), which the preview
 * and the export play in place of the compressed sound. There Media3 decodes every sound the
 * preview plays with a synchronous `MediaCodec` on `CompositionPlayer`'s one playback thread,
 * which the canvas clip's pictures need too, so a preview with sound drops pictures (three AAC
 * tracks took a Galaxy S9 from 30 to about 15 fps, #243). PCM plays without a decoder.
 *
 * Each copy is made by the export's own decoder: Transformer, in analyzer mode, reads the
 * original with Media3's extractors and `MediaCodec` as an export does, and every sample it
 * decodes is written as it comes, at the media's own rate and channels, from the media's start
 * to its end in one pass. The mix is still made as the sound plays, by the same composition in
 * the preview and the export (volumes, fades, normalization, ducking, speed, the layers summed),
 * so an edit is heard at once, and the two mix the same samples: Transformer and
 * `CompositionPlayer` mix a project played from its copies to the same samples
 * (`DecodedSoundsTest`). Read from the originals, they don't: on a Galaxy S9 the preview played
 * an untrimmed AAC clip a frame (1024 samples) later than the export, and the export played a
 * trimmed one a frame late, from the moment before its in point (the decoder starts at the
 * frame after the in point, without the one before it). A copy is decoded in one pass from the
 * media's start, so it has neither.
 *
 * The editor queues the copies of a project's media as clips start to play them ([requestAll]),
 * one at a time in the background, and [MediaCache.voiceVersion] changes when one is ready;
 * until then the preview plays the compressed sound. The export makes the copies it lacks first
 * ([prepareAll]). From Android 12 Media3 decodes sound asynchronously, off the playback thread,
 * so there no copies are made and both read the originals ([NEEDED]). A copy takes about 11.5 MB
 * a minute of stereo sound at 48 kHz (half that in mono); sound that already is PCM needs none,
 * and media longer than [MAX_US] gets none. Nothing leaves the device.
 */
@Singleton
@OptIn(UnstableApi::class, ExperimentalApi::class)
class DecodedSounds @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val resolver = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 1)

    /**
     * Media queued or being copied, media whose copy failed in this run of the app, and media
     * whose sound needs no copy, by id.
     */
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val plain = ConcurrentHashMap.newKeySet<String>()

    /**
     * Queues the copy of every media whose sound a clip of [project] plays and that has none
     * yet, and returns. Does nothing from Android 12 on.
     */
    fun requestAll(project: Project) {
        if (!NEEDED) return
        for (asset in soundsOf(project)) {
            val id = asset.id
            if (id in plain || id in failed || cache.decodedSound(id).exists()) continue
            if (!queued.add(id)) continue
            WORKER.execute {
                try {
                    // An original that can't be read now (missing media) is tried again later.
                    if (!prepare(asset) && resolver.source(asset) != null) failed += id
                } finally {
                    queued -= id
                }
            }
        }
    }

    /**
     * Makes the copies [project]'s clips play that are not made yet, blocking, before an export:
     * so the export plays what the preview played. A copy that can't be made leaves the clip
     * reading its original. Does nothing from Android 12 on.
     */
    suspend fun prepareAll(project: Project) {
        if (!NEEDED) return
        for (asset in soundsOf(project)) {
            currentCoroutineContext().ensureActive()
            prepare(asset)
        }
    }

    /**
     * Makes [asset]'s copy now unless it is there, blocking; not on the main thread. True when it
     * is there or none is needed (no sound, PCM already, too long), false when the original
     * can't be read or decoded.
     */
    fun prepare(asset: MediaAsset): Boolean {
        if (!hasSound(asset) || asset.id in plain) return true
        val file = cache.decodedSound(asset.id)
        return synchronized(LOCKS.getOrPut(file.path) { Any() }) {
            if (file.exists()) return@synchronized true
            val source = resolver.source(asset) ?: return@synchronized false
            try {
                if (!needsCopy(asset, source)) {
                    plain += asset.id
                    return@synchronized true
                }
                runBlocking { write(source, file) }
                cache.voiceReady()
                true
            } catch (e: Exception) {
                Log.w(TAG, "No sound copy of ${asset.id}", e)
                false
            }
        }
    }

    /**
     * The media whose own sound a clip of [project] plays forwards: videos with sound and
     * sounds. Reversed clips play a file of their own (`ReversedSounds`), and held frames are
     * silent. Muted layers and quiet clips count, as they are heard again with one tap.
     */
    private fun soundsOf(project: Project): List<MediaAsset> = project.layers.flatMap { it.clips }
        .filter { clip ->
            (clip.type == ClipType.Video || clip.type == ClipType.Audio) &&
                !clip.speed.reverse && (clip.speed.rate > 0 || clip.speed.ramp != null)
        }
        .mapNotNull { clip -> clip.source?.let { project.asset(it.mediaId) } }
        .filter(::hasSound)
        .distinctBy { it.id }

    private fun hasSound(asset: MediaAsset): Boolean =
        asset.kind == MediaKind.Audio || (asset.kind == MediaKind.Video && asset.hasAudio)

    /**
     * Whether [source]'s sound needs a copy: it is compressed and no longer than [MAX_US].
     * A WAV file is played as it is.
     */
    private fun needsCopy(asset: MediaAsset, source: String): Boolean {
        if (asset.durationUs > MAX_US) return false
        val extractor = MediaSources.open(context, source)
        try {
            val mimes = (0 until extractor.trackCount).mapNotNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
            }
            val sound = mimes.firstOrNull { it.startsWith("audio/") } ?: return false
            return sound != MimeTypes.AUDIO_RAW
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes the sound of [source] as an export does and writes it to [out]: Transformer in
     * analyzer mode (nothing is encoded or written but by [PcmTap]) with the tap as the item's
     * only audio effect, so it gets the decoder's samples before anything changes them.
     */
    private suspend fun write(source: String, out: File) {
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part").apply { delete() }
        val tap = PcmTap(part)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(MediaSources.uri(source)))
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(tap), emptyList()))
            .build()
        // The analyzer writes no file; Transformer still wants a path.
        val none = File(out.path + ".none")
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        try {
            suspendCancellableCoroutine { continuation ->
                // Transformer runs on the thread that builds it; the main looper is always there.
                main.post {
                    transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(context)
                        .buildUpon()
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
                        .also { it.start(item, none.path) }
                }
                continuation.invokeOnCancellation { main.post { transformer?.cancel() } }
            }
            check(tap.finish()) { "The decoder gave no sound" }
            check(part.renameTo(out)) { "Couldn't move the sound copy into place" }
        } finally {
            tap.finish()
            part.delete()
            none.delete()
        }
    }

    /**
     * Writes every sample that passes into [file] as 16-bit PCM WAV, and passes it on unchanged.
     * The decoders Media3 uses give 16-bit samples; another encoding fails the copy, and the
     * clips keep reading the compressed sound.
     */
    private class PcmTap(private val file: File) : BaseAudioProcessor() {
        private val out = BufferedOutputStream(file.outputStream(), BUFFER).apply {
            write(ByteArray(PcmWav.HEADER))
        }
        private var bytes = ByteArray(0)
        private var shape: AudioFormat? = null
        private var written = 0L
        private var finished = false

        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            // One file holds sound of one rate and channel count.
            val known = shape
            if (known != null &&
                (
                    known.sampleRate != inputAudioFormat.sampleRate ||
                        known.channelCount != inputAudioFormat.channelCount
                    )
            ) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            shape = inputAudioFormat
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val count = inputBuffer.remaining()
            if (count == 0) return
            if (bytes.size < count) bytes = ByteArray(count)
            // Samples come in the native byte order, little-endian on Android, as WAV wants.
            inputBuffer.get(bytes, 0, count)
            synchronized(this) {
                if (!finished) {
                    out.write(bytes, 0, count)
                    written += count
                }
            }
            val output = replaceOutputBuffer(count)
            output.put(bytes, 0, count)
            output.flip()
        }

        /** Closes the file and writes its header; false when no sound came. */
        @Synchronized
        fun finish(): Boolean {
            if (!finished) {
                finished = true
                out.close()
                val format = shape ?: return false
                val frames = written / (format.channelCount * 2)
                RandomAccessFile(file, "rw").use {
                    it.write(PcmWav.header(format.sampleRate, format.channelCount, frames))
                }
            }
            return shape != null && written > 0
        }
    }

    companion object {
        private const val TAG = "DecodedSounds"
        private const val BUFFER = 64 * 1024

        /**
         * Whether clips play copies: below Android 12 (API 31), where Media3 decodes sound with
         * a synchronous `MediaCodec` (`DefaultMediaCodecAdapterFactory`).
         */
        val NEEDED: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        /** An hour: about 690 MB of stereo sound at 48 kHz. Longer media plays as it is. */
        const val MAX_US = 3_600_000_000L

        /** One copy at a time, on a thread of its own. */
        private val WORKER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "clipra-sounds").apply { isDaemon = true }
        }

        /** Shared by every instance: a test's and the editor's may make the same file. */
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

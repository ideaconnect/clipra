package tech.idct.clipra.core.engine.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import java.io.File

/**
 * Proxies, thumbnails, filmstrip frames, waveforms and cleaned voices, keyed by media id. They live in the cache directory:
 * the system may clear them, and Clipra makes them again when they are needed. Project documents
 * never store these paths.
 */
class MediaCache(private val root: File) {
    /** The bytes the cache takes now (Settings, #74). */
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Deletes every proxy, thumbnail, waveform and cleaned voice. Projects and exported videos
     * live elsewhere and stay; Clipra makes these files again when it needs them.
     */
    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        voiceChanges.update { it + 1 }
    }

    /** The 720p H.264 proxy the editor plays instead of the original. */
    fun proxy(mediaId: String): File = File(root, "proxies/$mediaId.mp4")

    /** A still for Home and the media bin. */
    fun thumbnail(mediaId: String): File = File(root, "thumbnails/$mediaId.jpg")

    /** The waveform's peaks (#36), one byte per 20 ms. */
    fun waveform(mediaId: String): File = File(root, "waveforms/$mediaId.peaks")

    /** One filmstrip frame per [FILMSTRIP_STEP_US] of the media, for the timeline. */
    fun filmstripFrame(mediaId: String, index: Int): File =
        File(root, "filmstrips/$mediaId/$index.jpg")

    /** AI voice cleanup (#39): the media's sound as recorded, mono at 48 kHz. */
    fun voiceRecorded(mediaId: String): File = File(root, "voice/$mediaId.wav")

    /** The same sound as [engine] cleaned it, sample for sample. */
    fun voiceCleaned(mediaId: String, engine: VoiceEngine): File =
        File(root, "voice/$mediaId-${engine.name}.wav")

    /** The cleaned sound mixed with the recorded one at a strength: what plays and exports. */
    fun voiceMix(mediaId: String, cleanup: VoiceCleanup): File =
        File(root, "voice/$mediaId-${cleanup.engine.name}-${cleanup.percent}.wav")

    /**
     * What plays with noise reduction (#38): the recorded sound, or its [cleanup] mix, through
     * the spectral gate at [percent] strength.
     */
    fun voiceGated(mediaId: String, cleanup: VoiceCleanup?, percent: Int): File {
        val cleaned = cleanup?.let { "-${it.engine.name}-${it.percent}" }.orEmpty()
        return File(root, "voice/$mediaId$cleaned-gate$percent.wav")
    }

    /** Where [mediaId] speaks (#40), as microsecond ranges, one per line. */
    fun speech(mediaId: String): File = File(root, "analysis/$mediaId.speech")

    /** The beats of [mediaId] (#40): its tempo, then a beat per line, in microseconds. */
    fun beats(mediaId: String): File = File(root, "analysis/$mediaId.beats")

    /** The camera's motion in [mediaId] (#54): the picture's shape, then a step per frame. */
    fun stabilize(mediaId: String): File = File(root, "stabilize/$mediaId.motion")

    /** The person masks of [mediaId] (#50), frame by frame (see `CutoutMasks`). */
    fun cutout(mediaId: String): File = File(root, "cutout/$mediaId.masks")

    /** The waveform of a voice [mix]. */
    fun voiceWaveform(mix: File): File = File(mix.path.removeSuffix(".wav") + ".peaks")

    private val voiceChanges = MutableStateFlow(0)

    /**
     * Changes each time a cleaned voice, an analysis, person masks (#50) or a camera motion
     * (#54) are ready, so the preview can pick them up.
     */
    val voiceVersion: StateFlow<Int> = voiceChanges.asStateFlow()

    internal fun voiceReady() = voiceChanges.update { it + 1 }

    companion object {
        const val FILMSTRIP_STEP_US = 1_000_000L
    }
}

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

    /** The waveform of a voice [mix]. */
    fun voiceWaveform(mix: File): File = File(mix.path.removeSuffix(".wav") + ".peaks")

    private val voiceChanges = MutableStateFlow(0)

    /** Changes each time a cleaned voice is ready, so the preview can pick it up. */
    val voiceVersion: StateFlow<Int> = voiceChanges.asStateFlow()

    internal fun voiceReady() = voiceChanges.update { it + 1 }

    companion object {
        const val FILMSTRIP_STEP_US = 1_000_000L
    }
}

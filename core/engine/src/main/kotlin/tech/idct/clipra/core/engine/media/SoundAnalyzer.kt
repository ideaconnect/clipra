package tech.idct.clipra.core.engine.media

import tech.idct.clipra.core.audio.BeatDetector
import tech.idct.clipra.core.audio.Beats
import tech.idct.clipra.core.audio.SpeechDetector
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.ml.voice.DeepFilterNet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a media speaks and where its beats are (#40), worked out once from its sound (mono at
 * 48 kHz, as voice cleanup decodes it) and kept in [MediaCache]. Nothing leaves the device.
 */
@Singleton
class SoundAnalyzer @Inject constructor(
    private val cache: MediaCache,
    private val voices: VoiceCleaner,
) {
    /** The speech of [asset], in microseconds of the media, once found. */
    fun speech(asset: MediaAsset): List<LongRange>? = readSpeech(cache, asset.id)

    /** The beats of [asset], in microseconds of the media, once found. */
    fun beats(asset: MediaAsset): Beats? = readBeats(cache, asset.id)

    /** Finds the speech now unless it is known; null without sound. */
    fun prepareSpeech(asset: MediaAsset): List<LongRange>? {
        speech(asset)?.let { return it }
        val sound = voices.recordedSound(asset) ?: return null
        val detector = SpeechDetector(DeepFilterNet.SAMPLE_RATE)
        read(sound) { block, n -> detector.add(block, n) }
        val found = detector.segments()
        write(cache.speech(asset.id), found.joinToString("\n") { "${it.first} ${it.last}" })
        cache.voiceReady()
        return found
    }

    /** Finds the beats now unless they are known; null without sound or a steady pulse. */
    fun prepareBeats(asset: MediaAsset): Beats? {
        beats(asset)?.let { return it }
        val sound = voices.recordedSound(asset) ?: return null
        val detector = BeatDetector(DeepFilterNet.SAMPLE_RATE)
        read(sound) { block, n -> detector.add(block, n) }
        val found = detector.beats() ?: Beats(emptyList(), 0f)
        write(
            cache.beats(asset.id),
            (
                listOf(found.bpm.toString()) +
                    found.timesUs.map { it.toString() }
                ).joinToString("\n"),
        )
        cache.voiceReady()
        return found
    }

    private fun read(sound: File, add: (FloatArray, Int) -> Unit) {
        val block = FloatArray(BLOCK)
        WavReader(sound).use { reader ->
            while (true) {
                val n = reader.read(block, 0, BLOCK)
                if (n < 0) break
                add(block, n)
            }
        }
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeText(text)
        part.renameTo(file)
    }

    companion object {
        private const val BLOCK = 8_192

        /** The speech of [mediaId] in [cache], if it was found. */
        fun readSpeech(cache: MediaCache, mediaId: String): List<LongRange>? {
            val file = cache.speech(mediaId).takeIf { it.exists() } ?: return null
            return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val (from, to) = line.split(' ').mapNotNull { it.toLongOrNull() }.takeIf {
                    it.size ==
                        2
                }
                    ?: return@mapNotNull null
                from..to
            }
        }

        /** The beats of [mediaId] in [cache], if they were found. */
        fun readBeats(cache: MediaCache, mediaId: String): Beats? {
            val lines = cache.beats(mediaId).takeIf { it.exists() }?.readLines() ?: return null
            val bpm = lines.firstOrNull()?.toFloatOrNull() ?: return null
            return Beats(lines.drop(1).mapNotNull { it.toLongOrNull() }, bpm)
        }
    }
}

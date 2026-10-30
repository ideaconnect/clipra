package tech.idct.clipra.core.engine.media

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Fillers
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.WordKind
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelStore
import tech.idct.clipra.ml.voice.Resampler
import tech.idct.clipra.ml.voice.SampleSource
import tech.idct.clipra.ml.whisper.Whisper
import tech.idct.clipra.ml.whisper.WordTiming
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.log10

/**
 * Auto captions' transcription (#44) on the device, as the whisper spike recommends
 * (docs/spikes/whisper.md):
 * - each clip's stretch of sound, its cleaned voice (#39) when it has one, goes to 16 kHz mono;
 * - one whisper.cpp call per clip, or per 10 minutes of long media, split at the quietest half
 *   second near the mark so no word is cut;
 * - word edges move to the pauses around them, fillers are marked, and times are in the clip's
 *   media time.
 */
@Singleton
class Transcriber @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
    private val models: ModelStore,
) {
    /**
     * Transcribes the clips [clipIds] of [project] with [model] in [language] (an ISO 639-1
     * code, or "auto"), in English if [translate]. [onProgress] gets 0 to 1; [stopped] cancels,
     * and then this throws [Cancelled]. Clips without sound are left out.
     */
    fun transcribe(
        project: Project,
        clipIds: List<String>,
        model: AiModel,
        language: String,
        translate: Boolean,
        onProgress: (Float) -> Unit = {},
        stopped: () -> Boolean = { false },
    ): List<Transcript> {
        val file = model.download?.let { models.file(model, it.name) }
            ?: error("${model.name} is not installed")
        val sources = clipIds.mapNotNull { id ->
            val clip = project.findClip(id)?.second ?: return@mapNotNull null
            val asset = clip.source?.let { project.asset(it.mediaId) } ?: return@mapNotNull null
            (clip to asset).takeIf { asset.hasAudio }
        }
        val total = sources.sumOf { it.first.durationUs }.coerceAtLeast(1)
        var before = 0L
        val started = System.nanoTime()
        return Whisper.load(file).use { whisper ->
            var done = false
            val watcher = thread(name = "clipra-transcribe-cancel") {
                while (!done) {
                    if (stopped()) whisper.cancel()
                    Thread.sleep(CANCEL_POLL_MS)
                }
            }
            try {
                sources.map { (clip, asset) ->
                    if (stopped()) throw Cancelled()
                    val share = clip.durationUs.toFloat() / total
                    val offset = before.toFloat() / total
                    before += clip.durationUs
                    transcribe(whisper, clip, asset, language, translate) {
                        onProgress(offset + share * it)
                    }.copy(model = model.id, detected = language == AUTO && !translate)
                }.also {
                    val seconds = (System.nanoTime() - started) / 1e9
                    Log.i(
                        TAG,
                        "${model.name}: %.1f s of sound in %.1f s".format(total / 1e6, seconds),
                    )
                }
            } catch (_: Whisper.CancelledException) {
                throw Cancelled()
            } finally {
                done = true
                watcher.join()
            }
        }
    }

    private fun transcribe(
        whisper: Whisper,
        clip: Clip,
        asset: MediaAsset,
        language: String,
        translate: Boolean,
        onProgress: (Float) -> Unit,
    ): Transcript {
        val ref = clip.source!!
        val fromUs = ref.inPointUs
        val toUs = ref.outPointUs
        val wav = File(context.cacheDir, "transcribe/${clip.id}.wav")
        wav.parentFile?.mkdirs()
        try {
            val voice = clip.audio.voiceCleanup?.let { cache.voiceMix(asset.id, it) }
            if (voice != null && voice.exists()) {
                // The cleaned voice covers the whole media from its start.
                WavReader(voice).use { reader ->
                    reader.skip(fromUs * reader.sampleRate / 1_000_000)
                    val frames = (toUs - fromUs) * reader.sampleRate / 1_000_000
                    WavWriter(wav, Whisper.SAMPLE_RATE).use { out ->
                        val limited = limit(reader, frames)
                        MonoAudio.copy(
                            Resampler(limited, reader.sampleRate, Whisper.SAMPLE_RATE),
                            out,
                        )
                    }
                }
            } else if (!MonoAudio.write(
                    context,
                    asset.uri,
                    fromUs,
                    toUs,
                    Whisper.SAMPLE_RATE,
                    wav,
                ) {
                    onProgress(DECODE_SHARE * it)
                }
            ) {
                return Transcript(clip.id, language.takeIf { it != AUTO }.orEmpty())
            }
            var detected = language.takeIf { it != AUTO }
            val words = mutableListOf<Word>()
            WavReader(wav).use { reader ->
                val length = reader.frames.coerceAtLeast(1)
                var start = 0L
                var carry = FloatArray(0)
                while (true) {
                    val chunk = nextChunk(reader, carry)
                    if (chunk.isEmpty()) break
                    val cut = if (chunk.size < CHUNK_SAMPLES) chunk.size else quietestCut(chunk)
                    val samples = chunk.copyOf(cut)
                    carry = chunk.copyOfRange(cut, chunk.size)
                    val result = whisper.transcribe(
                        samples,
                        detected ?: AUTO,
                        threads(),
                        translate,
                    ) { percent ->
                        val done = (start + cut * percent / 100f) / length
                        onProgress(DECODE_SHARE + (1 - DECODE_SHARE) * done)
                    }
                    if (detected == null) detected = result.language
                    val refined = WordTiming.refine(
                        result.words,
                        WordTiming.pauses(samples, Whisper.SAMPLE_RATE),
                    )
                    val chunkUs = start * 1_000_000 / Whisper.SAMPLE_RATE
                    val spoken = if (translate) ENGLISH else detected.orEmpty()
                    refined.mapTo(words) {
                        Word(
                            text = it.text,
                            startUs = fromUs + chunkUs + it.startUs,
                            endUs = fromUs + chunkUs + it.endUs,
                            confidence = it.probability,
                            kind = if (Fillers.isFiller(
                                    it.text,
                                    spoken,
                                )
                            ) {
                                WordKind.Filler
                            } else {
                                WordKind.Word
                            },
                        )
                    }
                    start += cut
                    if (carry.isEmpty() && chunk.size < CHUNK_SAMPLES) break
                }
            }
            val language = if (translate) ENGLISH else detected.orEmpty()
            return Transcript(
                clipId = clip.id,
                language = language,
                words = Fillers.withPhrases(words, language),
            )
        } finally {
            wav.delete()
        }
    }

    /** Up to [CHUNK_SAMPLES]: what was left over from the last chunk, then fresh sound. */
    private fun nextChunk(reader: WavReader, carry: FloatArray): FloatArray {
        val chunk = FloatArray(CHUNK_SAMPLES)
        carry.copyInto(chunk)
        var filled = carry.size
        while (filled < CHUNK_SAMPLES) {
            val n = reader.read(chunk, filled, CHUNK_SAMPLES - filled)
            if (n < 0) break
            filled += n
        }
        return chunk.copyOf(filled)
    }

    /** Where to end a full chunk: the middle of the quietest half second in its last 30 s. */
    private fun quietestCut(chunk: FloatArray): Int {
        val window = Whisper.SAMPLE_RATE / 2
        val step = Whisper.SAMPLE_RATE / 100
        var best = chunk.size
        var quietest = Double.MAX_VALUE
        var from = chunk.size - SPLIT_SEARCH_SAMPLES
        while (from + window <= chunk.size) {
            var energy = 0.0
            for (i in from until from + window) energy += chunk[i] * chunk[i]
            if (energy < quietest) {
                quietest = energy
                best = from + window / 2
            }
            from += step
        }
        Log.d(
            TAG,
            "Chunk cut at ${best / Whisper.SAMPLE_RATE} s, %.0f dB"
                .format(10 * log10(quietest / window + 1e-12)),
        )
        return best
    }

    private fun limit(source: SampleSource, frames: Long): SampleSource {
        var left = frames
        return SampleSource { buffer, offset, count ->
            if (left <= 0) return@SampleSource -1
            val n = source.read(buffer, offset, minOf(count.toLong(), left).toInt())
            if (n > 0) left -= n
            n
        }
    }

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

    /** Thrown by [transcribe] when it was stopped. */
    class Cancelled : Exception("Transcription cancelled")

    companion object {
        /** Ask Whisper to detect the language. */
        const val AUTO = "auto"
        private const val ENGLISH = "en"
        private const val TAG = "Transcriber"
        private const val MAX_THREADS = 4
        private const val CANCEL_POLL_MS = 100L
        private const val DECODE_SHARE = 0.1f

        // Ten minutes at 16 kHz (38 MB of samples), split within its last 30 s.
        private const val CHUNK_SAMPLES = 10 * 60 * Whisper.SAMPLE_RATE
        private const val SPLIT_SEARCH_SAMPLES = 30 * Whisper.SAMPLE_RATE
    }
}

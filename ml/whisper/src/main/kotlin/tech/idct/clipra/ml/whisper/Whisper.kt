package tech.idct.clipra.ml.whisper

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * whisper.cpp on the device (#7, #44): a loaded ggml model that turns 16 kHz mono speech into
 * words with their times and how sure the model is of each. [cancel] may be called from any
 * thread while [transcribe] runs.
 */
class Whisper private constructor(private var handle: Long) : AutoCloseable {
    /** A word, from [startUs] to [endUs] of the audio, with its mean token [probability]. */
    data class Word(
        val text: String,
        val startUs: Long,
        val endUs: Long,
        val probability: Float,
        /** The DTW time of its first token, or -1 when the model was loaded without DTW. */
        val dtwUs: Long = -1,
    )

    /** What [transcribe] heard: the [language] spoken (or detected) and the words. */
    data class Result(val language: String, val words: List<Word>)

    /** A [language] Whisper heard and how sure it is of it, 0 to 1. */
    data class Detection(val language: String, val probability: Float)

    /** Gets whisper.cpp's progress through one call, 0 to 100. */
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    /** Thrown by [transcribe] after [cancel]. */
    class CancelledException : RuntimeException("Transcription cancelled")

    /**
     * Transcribes [samples] (16 kHz mono, -1 to 1) in [language] ("en", "pl", ..., or "auto"
     * to detect it) on [threads] threads; [translate] gives English words instead.
     */
    fun transcribe(
        samples: FloatArray,
        language: String,
        threads: Int,
        translate: Boolean = false,
        listener: ProgressListener? = null,
    ): Result {
        check(handle != 0L) { "Whisper is closed" }
        val status = transcribe(handle, samples, language, translate, threads, listener)
        if (status == CANCELLED) throw CancelledException()
        check(status == 0) { "whisper_full failed: $status" }
        val words = mutableListOf<Word>()
        val bytes = ByteArrayOutputStream()
        var start = 0L
        var end = 0L
        var dtw = -1L
        var probability = 0f
        var tokens = 0
        fun flush() {
            val text = bytes.toString(Charsets.UTF_8.name()).trim()
            if (text.isNotEmpty()) words += Word(text, start, end, probability / tokens, dtw)
            bytes.reset()
            probability = 0f
            tokens = 0
        }
        for (segment in 0 until segmentCount(handle)) {
            for (token in 0 until tokenCount(handle, segment)) {
                if (isSpecial(handle, segment, token)) continue
                val text = tokenBytes(handle, segment, token)
                val (t0, t1, tDtw) = tokenTimes(handle, segment, token).toList()
                // A token that starts with a space starts a new word.
                if (text.isNotEmpty() && text[0] == ' '.code.toByte() && bytes.size() > 0) flush()
                if (bytes.size() == 0) {
                    start = t0 * CENTISECOND_US
                    dtw = if (tDtw >= 0) tDtw * CENTISECOND_US else -1
                }
                bytes.write(text)
                end = t1 * CENTISECOND_US
                probability += tokenProbability(handle, segment, token)
                tokens++
            }
            flush()
        }
        return Result(language(handle), words)
    }

    /**
     * The language spoken in the first 30 s of [samples] (16 kHz mono) and how sure Whisper is
     * of it, or null when it can't tell.
     */
    fun detectLanguage(samples: FloatArray, threads: Int): Detection? {
        check(handle != 0L) { "Whisper is closed" }
        val probabilities = detectLanguage(handle, samples, threads)
        val id = probabilities.indices.maxByOrNull { probabilities[it] } ?: return null
        return Detection(languageCode(id), probabilities[id])
    }

    /** Stops a [transcribe] that is running (or the next one) as soon as it can. */
    fun cancel() {
        if (handle != 0L) cancel(handle)
    }

    override fun close() {
        if (handle != 0L) free(handle)
        handle = 0
    }

    /** whisper.cpp's alignment heads for DTW word timing, by model. */
    enum class AlignmentHeads(val preset: Int) {
        Tiny(4),
        Base(6),
        Small(8),
        LargeV3Turbo(14),
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CENTISECOND_US = 10_000L
        private const val CANCELLED = -100

        init {
            System.loadLibrary("clipra_whisper")
        }

        /** Loads [model]; [aheads] adds DTW timestamps with that model's alignment heads. */
        fun load(model: File, aheads: AlignmentHeads? = null): Whisper {
            val handle = load(model.path, aheads?.preset ?: -1)
            check(handle != 0L) { "Could not load ${model.name}" }
            return Whisper(handle)
        }

        /** The CPU features whisper.cpp was built with and found (AVX2, NEON, ...). */
        fun systemInfo(): String = nativeSystemInfo()

        @JvmStatic
        private external fun load(path: String, aheads: Int): Long

        @JvmStatic
        private external fun free(handle: Long)

        @JvmStatic
        private external fun cancel(handle: Long)

        @JvmStatic
        private external fun transcribe(
            handle: Long,
            samples: FloatArray,
            language: String,
            translate: Boolean,
            threads: Int,
            listener: ProgressListener?,
        ): Int

        @JvmStatic
        private external fun detectLanguage(
            handle: Long,
            samples: FloatArray,
            threads: Int,
        ): FloatArray

        @JvmStatic
        private external fun languageCode(id: Int): String

        @JvmStatic
        private external fun language(handle: Long): String

        @JvmStatic
        private external fun segmentCount(handle: Long): Int

        @JvmStatic
        private external fun tokenCount(handle: Long, segment: Int): Int

        @JvmStatic
        private external fun isSpecial(handle: Long, segment: Int, token: Int): Boolean

        @JvmStatic
        private external fun tokenBytes(handle: Long, segment: Int, token: Int): ByteArray

        @JvmStatic
        private external fun tokenTimes(handle: Long, segment: Int, token: Int): LongArray

        @JvmStatic
        private external fun tokenProbability(handle: Long, segment: Int, token: Int): Float

        @JvmStatic
        private external fun nativeSystemInfo(): String
    }
}

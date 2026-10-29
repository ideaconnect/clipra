package tech.idct.clipra.ml.whisper

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * whisper.cpp on the device (#7): a loaded ggml model that turns 16 kHz mono speech into
 * words with their times and how sure the model is of each.
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

    /**
     * Transcribes [samples] (16 kHz mono, -1 to 1) in [language] ("en", "pl", ...) on
     * [threads] threads.
     */
    fun transcribe(samples: FloatArray, language: String, threads: Int): List<Word> {
        check(handle != 0L) { "Whisper is closed" }
        val status = transcribe(handle, samples, language, threads)
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
        return words
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
        private external fun transcribe(
            handle: Long,
            samples: FloatArray,
            language: String,
            threads: Int,
        ): Int

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

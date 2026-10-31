package tech.idct.clipra.ml.translate

import java.io.File

/**
 * One translation direction on Marian NMT (ADR 0007): a Firefox Translations [model], its
 * SentencePiece [vocab] and its lexical [shortlist], run on the CPU through JNI. Nothing leaves
 * the phone. Not thread-safe: translate from one thread at a time.
 */
class Marian private constructor(private var handle: Long) : AutoCloseable {
    /**
     * Translates [lines], one sentence or caption per line, in one batch; the result has a line
     * for each. Line breaks inside a line become spaces.
     */
    fun translate(lines: List<String>): List<String> {
        check(handle != 0L) { "Closed" }
        if (lines.isEmpty()) return emptyList()
        val input = lines.joinToString("\n") { it.replace('\n', ' ').replace('\r', ' ').trim() }
        val output = translate(handle, input.encodeToByteArray()).decodeToString()
        val result = output.split('\n')
        check(result.size == lines.size) { "${result.size} lines back for ${lines.size}" }
        return result
    }

    override fun close() {
        if (handle != 0L) close(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("clipra_translate")
        }

        /** Loads a direction; slow (a second or more), so call it off the main thread. */
        fun open(model: File, vocab: File, shortlist: File, threads: Int = 1): Marian =
            Marian(open(model.path, vocab.path, shortlist.path, threads))

        @JvmStatic
        private external fun open(
            model: String,
            vocab: String,
            shortlist: String,
            threads: Int,
        ): Long

        @JvmStatic
        private external fun translate(handle: Long, text: ByteArray): ByteArray

        @JvmStatic
        private external fun close(handle: Long)
    }
}

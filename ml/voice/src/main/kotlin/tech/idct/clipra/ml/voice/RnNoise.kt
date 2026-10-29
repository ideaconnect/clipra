package tech.idct.clipra.ml.voice

/**
 * RNNoise (xiph/rnnoise v0.2, BSD-3-Clause), the lite engine of AI voice cleanup (#39): a small
 * recurrent network over 480-sample frames at 48 kHz, built into the app with its weights.
 * RNNoise delays its output by [DELAY] samples; this takes the delay out, so the output lines
 * up with the input.
 */
class RnNoise : VoiceDenoiser {
    private var state = create()

    override fun process(
        source: SampleSource,
        length: Long,
        sink: SampleSink,
        onProgress: (Float) -> Unit,
        stopped: () -> Boolean,
    ) {
        val size = frameSize()
        val input = FloatArray(size * FRAMES)
        val output = FloatArray(size * FRAMES)
        var skip = DELAY
        var left = length
        var read = 0L
        var ended = false
        while (left > 0 && !stopped()) {
            var filled = 0
            while (filled < input.size && !ended) {
                val n = source.read(input, filled, input.size - filled)
                if (n < 0) ended = true else filled += n
            }
            // After the end, silence pushes the last samples through the delay.
            input.fill(0f, filled, input.size)
            read += filled
            for (i in input.indices) input[i] *= SCALE
            process(state, input, output, FRAMES)
            for (i in output.indices) output[i] /= SCALE
            val from = minOf(skip, output.size)
            skip -= from
            val n = minOf((output.size - from).toLong(), left).toInt()
            if (n > 0) {
                sink.write(if (from == 0) output else output.copyOfRange(from, from + n), n)
                left -= n
            }
            onProgress((read.toFloat() / length).coerceAtMost(1f))
        }
    }

    override fun close() {
        if (state != 0L) destroy(state)
        state = 0
    }

    companion object {
        /**
         * The samples RNNoise's output lags its input: one frame for its STFT, and one more,
         * as v0.2 applies each frame's gains to the frame before it (`delayed_X`).
         */
        const val DELAY = 960

        private const val FRAMES = 100
        private const val SCALE = 32768f

        init {
            System.loadLibrary("clipra_rnnoise")
        }

        @JvmStatic
        private external fun create(): Long

        @JvmStatic
        private external fun destroy(state: Long)

        @JvmStatic
        private external fun frameSize(): Int

        @JvmStatic
        private external fun process(
            state: Long,
            input: FloatArray,
            output: FloatArray,
            frames: Int,
        )
    }
}

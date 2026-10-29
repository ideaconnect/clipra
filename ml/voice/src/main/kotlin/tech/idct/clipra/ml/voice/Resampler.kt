package tech.idct.clipra.ml.voice

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts a mono [source] at [inputRate] to [outputRate] as it is read: a windowed-sinc
 * filter (Blackman window, cut a little under the lower Nyquist frequency) from a table of
 * [PHASES] fractional positions. Equal rates pass straight through.
 */
class Resampler(
    private val source: SampleSource,
    private val inputRate: Int,
    private val outputRate: Int,
) : SampleSource {
    private val cutoff = minOf(1.0, outputRate.toDouble() / inputRate) * CUTOFF
    private val half = ceil(HALF_TAPS / cutoff).toInt()
    private val taps = 2 * half
    private val table = Array(PHASES + 1) { phase ->
        val frac = phase.toDouble() / PHASES
        FloatArray(taps) { k ->
            // Tap k sits at input offset k - half + 1 from the sample before the position.
            val x = (k - half + 1) - frac
            val window = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
            val arg = PI * cutoff * x
            val sinc = if (x == 0.0) 1.0 else sin(arg) / arg
            (cutoff * sinc * window).toFloat()
        }
    }

    // Input samples from absolute index bufferStart on.
    private var buffer = FloatArray(BLOCK + taps)
    private var bufferStart = -half.toLong() + 1
    private var bufferEnd = bufferStart + half - 1 // starts with half - 1 zeros of history
    private var ended = false
    private var produced = 0L

    override fun read(buffer: FloatArray, offset: Int, count: Int): Int {
        if (inputRate == outputRate) return source.read(buffer, offset, count)
        var written = 0
        while (written < count) {
            val numerator = produced * inputRate
            val index = numerator / outputRate
            val phase = ((numerator % outputRate) * PHASES / outputRate).toInt()
            if (!ensure(index + half)) break
            val kernel = table[phase]
            var sum = 0f
            val base = (index - half + 1 - bufferStart).toInt()
            for (k in 0 until taps) sum += this.buffer[base + k] * kernel[k]
            buffer[offset + written] = sum
            written++
            produced++
        }
        return if (written == 0 && ended) -1 else written
    }

    /** Loads input up to [last]; false once the input (and its silent tail) has run out. */
    private fun ensure(last: Long): Boolean {
        while (bufferEnd <= last) {
            if (ended) {
                // Past the end, the filter sees silence until its window has left the input.
                if (bufferEnd - half >= endIndex) return false
                append(FloatArray(1), 1)
                continue
            }
            val block = FloatArray(BLOCK)
            val n = source.read(block, 0, BLOCK)
            if (n < 0) {
                ended = true
                endIndex = bufferEnd
            } else {
                append(block, n)
            }
        }
        return true
    }

    private var endIndex = Long.MAX_VALUE

    private fun append(samples: FloatArray, count: Int) {
        // Keep the filter's history; drop what lies before it.
        val keepFrom = produced * inputRate / outputRate - half + 1
        val drop = (keepFrom - bufferStart).toInt().coerceIn(0, (bufferEnd - bufferStart).toInt())
        val kept = (bufferEnd - bufferStart).toInt() - drop
        if (kept + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, kept + count))
        buffer.copyInto(buffer, 0, drop, drop + kept)
        bufferStart += drop
        samples.copyInto(buffer, kept, 0, count)
        bufferEnd += count
    }

    private companion object {
        const val PHASES = 256
        const val HALF_TAPS = 16
        const val CUTOFF = 0.95
        const val BLOCK = 4_096
    }
}

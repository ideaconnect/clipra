package tech.idct.clipra.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Noise reduction (#38): a spectral gate for steady hum and hiss. The sound is cut into
 * overlapping windows (2048 samples, a quarter apart) and taken to frequencies. A [NoiseProfile]
 * learns, for each frequency, the level it keeps falling back to (a low percentile over the whole
 * clip): steady hum and hiss sit there, speech and music rise above it. The gate lowers each
 * frequency that stays near its floor by up to [maxReductionDb] times the strength, opening at
 * once when the sound rises and closing smoothly, with gains smoothed across neighbouring
 * frequencies so the remaining noise doesn't warble.
 *
 * Mono; [process] streams any length through, with [finish] to flush the end. The output is as
 * long as the input and lined up with it.
 */
class SpectralGate(noiseFloor: FloatArray, strength: Float) {
    private val reduction = 10f.pow(-strength.coerceIn(0f, 1f) * MAX_REDUCTION_DB / 20)
    private val threshold = FloatArray(BINS) { noiseFloor[it] * OPEN }
    private val window = Stft.window()
    private val frame = FloatArray(SIZE)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val gains = FloatArray(BINS) { 1f }
    private val raw = FloatArray(BINS)
    private val overlap = FloatArray(SIZE)
    private val out = FloatArray(HOP)
    private var filled = SIZE - HOP
    private var latency = SIZE - HOP
    private var taken = 0L
    private var given = 0L

    /** Feeds [count] samples of [input]; [emit] receives gated samples as they are ready. */
    fun process(input: FloatArray, count: Int, emit: (FloatArray, Int) -> Unit) {
        var i = 0
        while (i < count) {
            val n = minOf(SIZE - filled, count - i)
            System.arraycopy(input, i, frame, filled, n)
            filled += n
            i += n
            taken += n
            if (filled == SIZE) step(emit)
        }
    }

    /** Flushes the last samples, so the output is as long as the input. */
    fun finish(emit: (FloatArray, Int) -> Unit) {
        val zeros = FloatArray(SIZE)
        val wanted = taken
        taken = Long.MAX_VALUE // what follows is padding: emit only up to [wanted]
        var left = SIZE.toLong()
        while (given < wanted && left > 0) {
            val n = minOf(SIZE - filled, left.toInt())
            System.arraycopy(zeros, 0, frame, filled, n)
            filled += n
            left -= n
            if (filled == SIZE) step(emit, wanted)
        }
    }

    private fun step(emit: (FloatArray, Int) -> Unit, limit: Long = Long.MAX_VALUE) {
        for (k in 0 until SIZE) {
            re[k] = frame[k] * window[k]
            im[k] = 0f
        }
        Fft.transform(re, im, inverse = false)
        // Each frequency's gain: closed near the floor, open well above it.
        for (k in 0 until BINS) {
            val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
            val t = threshold[k]
            raw[k] = when {
                magnitude <= t -> reduction

                magnitude >= t * KNEE -> 1f

                else -> {
                    val x = ln(magnitude / t) / ln(KNEE)
                    reduction + (1 - reduction) * x * x * (3 - 2 * x)
                }
            }
        }
        for (k in 0 until BINS) {
            var sum = 0f
            var weight = 0f
            for (d in -SPREAD..SPREAD) {
                val j = k + d
                if (j in 0 until BINS) {
                    sum += raw[j]
                    weight += 1f
                }
            }
            val target = sum / weight
            // Open at once, close smoothly.
            gains[k] = if (target > gains[k]) target else gains[k] + (target - gains[k]) * RELEASE
        }
        for (k in 0 until BINS) {
            re[k] *= gains[k]
            im[k] *= gains[k]
            if (k in 1 until SIZE - BINS + 1) {
                re[SIZE - k] = re[k]
                im[SIZE - k] = -im[k]
            }
        }
        Fft.transform(re, im, inverse = true)
        for (k in 0 until SIZE) overlap[k] += re[k] * window[k] / OVERLAP_GAIN
        System.arraycopy(overlap, 0, out, 0, HOP)
        System.arraycopy(overlap, HOP, overlap, 0, SIZE - HOP)
        overlap.fill(0f, SIZE - HOP, SIZE)
        System.arraycopy(frame, HOP, frame, 0, SIZE - HOP)
        filled = SIZE - HOP
        // The first windows only fill the overlap: skip that much, once.
        var start = 0
        if (latency > 0) {
            start = minOf(latency, HOP)
            latency -= start
        }
        val n = (minOf(HOP.toLong(), limit - given + start) - start).toInt()
        if (n > 0) {
            if (start > 0) System.arraycopy(out, start, out, 0, n)
            emit(out, n)
            given += n
        }
    }

    companion object {
        const val SIZE = 2048
        const val HOP = SIZE / 4
        const val BINS = SIZE / 2 + 1

        /** The most a frequency is lowered, at full strength. */
        const val MAX_REDUCTION_DB = 36f

        /** The noise level times this is where the gate starts to open; [KNEE] times more, fully. */
        private const val OPEN = 3f
        private const val KNEE = 2f
        private const val SPREAD = 2
        private const val RELEASE = 0.25f

        /** A Hann window squared, a quarter apart, sums to 1.5. */
        private const val OVERLAP_GAIN = 1.5f
    }
}

/**
 * The noise floor of a sound, for [SpectralGate]: for each frequency, the level the sound falls
 * back to most of the time, the [percentile] of its levels over every window, kept in 1 dB steps.
 */
class NoiseProfile(private val percentile: Float = 0.3f) {
    private val histograms = Array(SpectralGate.BINS) { IntArray(LEVELS) }
    private val window = Stft.window()
    private val frame = FloatArray(SpectralGate.SIZE)
    private val re = FloatArray(SpectralGate.SIZE)
    private val im = FloatArray(SpectralGate.SIZE)
    private var filled = 0
    private var frames = 0

    fun add(input: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(SpectralGate.SIZE - filled, count - i)
            System.arraycopy(input, i, frame, filled, n)
            filled += n
            i += n
            if (filled == SpectralGate.SIZE) {
                measure()
                System.arraycopy(
                    frame,
                    SpectralGate.HOP,
                    frame,
                    0,
                    SpectralGate.SIZE - SpectralGate.HOP,
                )
                filled = SpectralGate.SIZE - SpectralGate.HOP
            }
        }
    }

    /** The floor per frequency, as magnitudes; silence when nothing was added. */
    fun floor(): FloatArray = FloatArray(SpectralGate.BINS) { k ->
        val histogram = histograms[k]
        val wanted = max(1, (frames * percentile).toInt())
        var seen = 0
        var level = 0
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen >= wanted) {
                level = i
                break
            }
        }
        if (frames == 0) 0f else 10f.pow((level + FLOOR_DB) / 20f)
    }

    private fun measure() {
        for (k in 0 until SpectralGate.SIZE) {
            re[k] = frame[k] * window[k]
            im[k] = 0f
        }
        Fft.transform(re, im, inverse = false)
        for (k in 0 until SpectralGate.BINS) {
            val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
            val db = 20 * kotlin.math.log10(max(magnitude, 1e-12f))
            val level = (db - FLOOR_DB).toInt().coerceIn(0, LEVELS - 1)
            histograms[k][level]++
        }
        frames++
    }

    private companion object {
        const val FLOOR_DB = -140
        const val LEVELS = 200
    }
}

/** Windows for the short-time transforms. */
internal object Stft {
    /** A periodic Hann window of [SpectralGate.SIZE]. */
    fun window(): FloatArray = FloatArray(SpectralGate.SIZE) {
        (0.5 - 0.5 * cos(2 * PI * it / SpectralGate.SIZE)).toFloat()
    }
}

/** An in-place radix-2 FFT. */
object Fft {
    /** Transforms [re] and [im] (a power of two long); the inverse divides by the length. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2 else -2) * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var cRe = 1f
                var cIm = 0f
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val xRe = re[b] * cRe - im[b] * cIm
                    val xIm = re[b] * cIm + im[b] * cRe
                    re[b] = re[a] - xRe
                    im[b] = im[a] - xIm
                    re[a] += xRe
                    im[a] += xIm
                    val next = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = next
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}

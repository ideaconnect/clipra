package tech.idct.clipra.ml.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads mono 48 kHz samples into [buffer] from [offset]: up to [count], or -1 at the end. */
fun interface SampleSource {
    fun read(buffer: FloatArray, offset: Int, count: Int): Int
}

/** Takes the processed samples in order. */
fun interface SampleSink {
    fun write(buffer: FloatArray, count: Int)
}

/** A voice cleanup engine: mono 48 kHz in, the same length out, sample for sample. */
interface VoiceDenoiser : AutoCloseable {
    fun process(
        source: SampleSource,
        length: Long,
        sink: SampleSink,
        onProgress: (Float) -> Unit = {},
        stopped: () -> Boolean = { false },
    )

    override fun close() = Unit
}

/**
 * The network of DeepFilterNet 3: for `frames` frames of features, the ERB gains and the deep
 * filter coefficients. [erb] is `[frames][32]`; [spec] is the complex features as
 * `[2][frames][96]` (real parts, then imaginary parts).
 */
fun interface DeepFilterModel {
    fun run(erb: FloatArray, spec: FloatArray, frames: Int): Estimate

    /** [gains] `[frames][32]`; [coefs] `[frames][96][5][2]` (order, then real and imaginary). */
    class Estimate(val gains: FloatArray, val coefs: FloatArray)
}

/**
 * DeepFilterNet 3 speech enhancement (#39), as libDF runs it (Rikorose/DeepFilterNet at v0.5.6,
 * MIT or Apache-2.0), ported to Kotlin: a 960-point STFT with 480 hop at 48 kHz, ERB and
 * complex features with exponential normalization, then the network's ERB gains for the whole
 * band and its deep filter (five frames, two of lookahead) for the lowest 96 bins.
 *
 * The official ONNX export runs whole sequences, so the sound goes through in chunks of
 * [CHUNK] frames, each with [WARM_UP] frames before it for the recurrent layers to settle, and
 * streams through: memory stays the same for any length. The output lines up with the input
 * sample for sample.
 */
class DeepFilterNet(private val model: DeepFilterModel) : VoiceDenoiser {
    private val fft = Fft(FFT)
    private val window = FloatArray(FFT) { i ->
        val s = sin(0.5 * PI * (i + 0.5) / (FFT / 2))
        sin(0.5 * PI * s * s).toFloat()
    }

    /** Enhances [length] samples from [source] into [sink]. [onProgress] gets 0 to 1. */
    override fun process(
        source: SampleSource,
        length: Long,
        sink: SampleSink,
        onProgress: (Float) -> Unit,
        stopped: () -> Boolean,
    ) {
        val frames = ((length + FFT) / HOP).toInt()
        val analysis = Analysis(source)
        val history = FrameHistory(WARM_UP + CHUNK + TAIL + CONV_LOOKAHEAD + ORDER + 2)
        val synthesis = Synthesis(sink, skip = FFT - HOP, length)
        var produced = 0
        var start = 0
        while (start < frames && !stopped()) {
            val end = minOf(frames, start + CHUNK)
            // The network needs features up to its tail plus its lookahead; the deep filter
            // needs spectra two frames ahead.
            val need = minOf(frames, end + TAIL + CONV_LOOKAHEAD)
            while (produced < need) {
                analysis.next(history.add(produced))
                produced++
            }
            val from = maxOf(0, start - WARM_UP)
            val to = minOf(frames, end + TAIL)
            val estimate = runModel(history, from, to, frames)
            for (t in start until end) {
                enhance(history, t, frames, estimate, t - from)
                synthesis.add(history.output)
            }
            onProgress(end.toFloat() / frames)
            start = end
        }
    }

    override fun close() {
        (model as? AutoCloseable)?.close()
    }

    /** Runs the network on frames [from] to [to], with the features shifted by its lookahead. */
    private fun runModel(
        history: FrameHistory,
        from: Int,
        to: Int,
        frames: Int,
    ): DeepFilterModel.Estimate {
        val count = to - from
        val erb = FloatArray(count * NB_ERB)
        val spec = FloatArray(2 * count * NB_DF)
        for (i in 0 until count) {
            val t = from + i + CONV_LOOKAHEAD
            if (t >= frames) continue
            val frame = history[t]
            frame.erb.copyInto(erb, i * NB_ERB)
            for (f in 0 until NB_DF) {
                spec[i * NB_DF + f] = frame.featRe[f]
                spec[count * NB_DF + i * NB_DF + f] = frame.featIm[f]
            }
        }
        return model.run(erb, spec, count)
    }

    /** Frame [t]'s enhanced spectrum: ERB gains above the deep filter, the deep filter below. */
    private fun enhance(
        history: FrameHistory,
        t: Int,
        frames: Int,
        estimate: DeepFilterModel.Estimate,
        row: Int,
    ) {
        val frame = history[t]
        val outRe = history.outputRe
        val outIm = history.outputIm
        var bin = 0
        for (band in 0 until NB_ERB) {
            val gain = estimate.gains[row * NB_ERB + band]
            repeat(WIDTHS[band]) {
                outRe[bin] = frame.re[bin] * gain
                outIm[bin] = frame.im[bin] * gain
                bin++
            }
        }
        for (f in 0 until NB_DF) {
            var re = 0f
            var im = 0f
            for (k in 0 until ORDER) {
                val source = t - (ORDER - 1 - DF_LOOKAHEAD) + k
                if (source < 0 || source >= frames) continue
                val s = history[source]
                val c = ((row * NB_DF + f) * ORDER + k) * 2
                val cr = estimate.coefs[c]
                val ci = estimate.coefs[c + 1]
                re += s.re[f] * cr - s.im[f] * ci
                im += s.re[f] * ci + s.im[f] * cr
            }
            outRe[f] = re
            outIm[f] = im
        }
    }

    /** One frame: its spectrum and its two feature sets. */
    private class Frame {
        val re = FloatArray(FREQS)
        val im = FloatArray(FREQS)
        val erb = FloatArray(NB_ERB)
        val featRe = FloatArray(NB_DF)
        val featIm = FloatArray(NB_DF)
    }

    /** The last [capacity] frames, by absolute index, and the output spectrum. */
    private class FrameHistory(private val capacity: Int) {
        private val frames = Array(capacity) { Frame() }
        val outputRe = FloatArray(FREQS)
        val outputIm = FloatArray(FREQS)
        val output = outputRe to outputIm

        fun add(index: Int): Frame = frames[index % capacity]

        operator fun get(index: Int): Frame = frames[index % capacity]
    }

    /** libDF's frame analysis and features: windowed FFT, ERB energies and unit-normed bins. */
    private inner class Analysis(private val source: SampleSource) {
        private val memory = FloatArray(FFT - HOP)
        private val input = FloatArray(HOP)
        private val re = DoubleArray(FFT)
        private val im = DoubleArray(FFT)
        private val meanNorm = FloatArray(NB_ERB) { -60f + it * (-90f + 60f) / (NB_ERB - 1) }
        private val unitNorm = FloatArray(NB_DF) { 0.001f + it * (0.0001f - 0.001f) / (NB_DF - 1) }
        private var ended = false

        fun next(frame: Frame) {
            fill()
            for (i in 0 until FFT - HOP) re[i] = (memory[i] * window[i]).toDouble()
            for (i in 0 until HOP) re[FFT - HOP + i] = (input[i] * window[FFT - HOP + i]).toDouble()
            im.fill(0.0)
            input.copyInto(memory)
            fft.transform(re, im)
            for (f in 0 until FREQS) {
                frame.re[f] = (re[f] * WNORM).toFloat()
                frame.im[f] = (im[f] * WNORM).toFloat()
            }
            var bin = 0
            for (band in 0 until NB_ERB) {
                var energy = 0f
                val width = WIDTHS[band]
                repeat(width) {
                    energy += frame.re[bin] * frame.re[bin] + frame.im[bin] * frame.im[bin]
                    bin++
                }
                val db = log10(energy / width + 1e-10f) * 10f
                meanNorm[band] = db * (1 - ALPHA) + meanNorm[band] * ALPHA
                frame.erb[band] = (db - meanNorm[band]) / 40f
            }
            for (f in 0 until NB_DF) {
                val r = frame.re[f]
                val i = frame.im[f]
                unitNorm[f] = sqrt(r * r + i * i) * (1 - ALPHA) + unitNorm[f] * ALPHA
                val scale = 1f / sqrt(unitNorm[f])
                frame.featRe[f] = r * scale
                frame.featIm[f] = i * scale
            }
        }

        /** The next hop of input, padded with silence after the end. */
        private fun fill() {
            var filled = 0
            while (filled < HOP && !ended) {
                val n = source.read(input, filled, HOP - filled)
                if (n < 0) ended = true else filled += n
            }
            input.fill(0f, filled, HOP)
        }
    }

    /** Inverse FFT, window and overlap-add; drops the STFT's delay and stops at [length]. */
    private inner class Synthesis(
        private val sink: SampleSink,
        private var skip: Int,
        private var left: Long,
    ) {
        private val memory = FloatArray(FFT - HOP)
        private val re = DoubleArray(FFT)
        private val im = DoubleArray(FFT)
        private val out = FloatArray(HOP)

        fun add(spectrum: Pair<FloatArray, FloatArray>) {
            val (sRe, sIm) = spectrum
            // The full spectrum from the half one, then the unnormalized inverse as realfft does.
            for (f in 0 until FREQS) {
                re[f] = sRe[f].toDouble()
                im[f] = sIm[f].toDouble()
            }
            for (f in FREQS until FFT) {
                re[f] = sRe[FFT - f].toDouble()
                im[f] = -sIm[FFT - f].toDouble()
            }
            fft.transform(re, im, inverse = true)
            for (i in 0 until HOP) out[i] = (re[i] * window[i]).toFloat() + memory[i]
            for (i in 0 until FFT - HOP) memory[i] = (re[HOP + i] * window[HOP + i]).toFloat()
            emit(out, HOP)
        }

        private fun emit(samples: FloatArray, count: Int) {
            var from = 0
            if (skip > 0) {
                from = minOf(skip, count)
                skip -= from
            }
            val n = minOf((count - from).toLong(), left).toInt()
            if (n <= 0) return
            if (from == 0) {
                sink.write(samples, n)
            } else {
                sink.write(samples.copyOfRange(from, from + n), n)
            }
            left -= n
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT = 960
        const val HOP = 480
        const val FREQS = FFT / 2 + 1
        const val NB_ERB = 32
        const val NB_DF = 96
        const val ORDER = 5
        const val DF_LOOKAHEAD = 2
        const val CONV_LOOKAHEAD = 2

        /** Frames per network run (10 s), the frames before it to settle, and after it. */
        const val CHUNK = 1_000
        const val WARM_UP = 200
        const val TAIL = 8

        private const val WNORM = 1.0 / (FFT.toDouble() * FFT / (2 * HOP))

        // libDF's exponential normalization over 1 s: exp(-hop / sr), rounded as libDF does.
        private const val ALPHA = 0.99f

        /** The ERB bands' widths in bins, as libDF's erb_fb computes them. */
        val WIDTHS: IntArray = erbWidths(SAMPLE_RATE, FFT, NB_ERB, minWidth = 2)

        internal fun erbWidths(sampleRate: Int, fftSize: Int, bands: Int, minWidth: Int): IntArray {
            fun freqToErb(hz: Double) = 9.265 * ln1p(hz / (24.7 * 9.265))
            fun erbToFreq(erb: Double) = 24.7 * 9.265 * (exp(erb / 9.265) - 1)
            val low = freqToErb(0.0)
            val high = freqToErb(sampleRate / 2.0)
            val step = (high - low) / bands
            val widths = IntArray(bands)
            var previous = 0
            var over = 0
            for (i in 1..bands) {
                val bin = (erbToFreq(low + i * step) / (sampleRate.toDouble() / fftSize))
                    .roundToInt()
                var width = bin - previous - over
                if (width < minWidth) {
                    over = minWidth - width
                    width = minWidth
                } else {
                    over = 0
                }
                widths[i - 1] = width
                previous = bin
            }
            widths[bands - 1] += 1
            val tooLarge = widths.sum() - (fftSize / 2 + 1)
            if (tooLarge > 0) widths[bands - 1] -= tooLarge
            return widths
        }
    }
}

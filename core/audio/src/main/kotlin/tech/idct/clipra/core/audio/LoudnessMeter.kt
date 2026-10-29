package tech.idct.clipra.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Integrated loudness and true peak (#37), as ITU-R BS.1770-4 and EBU R128 define them: the
 * sound is K-weighted, cut into 400 ms blocks overlapping by 75%, gated at -70 LUFS and then
 * 10 LU below the ungated level, and averaged. True peak is measured on the sound oversampled
 * four times. Our own implementation; feed it 16-bit interleaved samples as they decode.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { Biquad.shelf(sampleRate) }
    private val highPass = Array(channels) { Biquad.highPass(sampleRate) }
    private val oversampler = Array(channels) { Oversampler() }

    // Sums of squares per 100 ms step; a block is four steps.
    private val stepFrames = sampleRate / 10
    private val stepSums = ArrayList<Double>()
    private var stepSum = 0.0
    private var stepFill = 0
    private var channel = 0
    private var peak = 0.0

    /** Adds [count] interleaved samples. */
    fun add(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val x = samples[i] / 32768.0
            peak = max(peak, oversampler[channel].peak(x))
            val weighted = highPass[channel].process(shelf[channel].process(x))
            // Left, right and centre all weigh 1; surrounds are rare on phones.
            stepSum += weighted * weighted
            channel++
            if (channel == channels) {
                channel = 0
                stepFill++
                if (stepFill == stepFrames) {
                    stepSums += stepSum / stepFrames
                    stepSum = 0.0
                    stepFill = 0
                }
            }
        }
    }

    /** Integrated loudness in LUFS, or null when everything is below the -70 LUFS gate. */
    fun integratedLufs(): Double? {
        val blocks = (0..stepSums.size - BLOCK_STEPS).map { start ->
            (start until start + BLOCK_STEPS).sumOf { stepSums[it] } / BLOCK_STEPS
        }
        val loud = blocks.filter { lufs(it) > ABSOLUTE_GATE }
        if (loud.isEmpty()) return null
        val relativeGate = lufs(loud.average()) - RELATIVE_GATE
        val kept = loud.filter { lufs(it) > relativeGate }
        if (kept.isEmpty()) return null
        return lufs(kept.average())
    }

    /** The true peak in dBTP (0 is full scale). */
    fun truePeakDb(): Double = if (peak > 0) 20 * log10(peak) else Double.NEGATIVE_INFINITY

    private fun lufs(meanSquare: Double): Double = -0.691 + 10 * log10(max(meanSquare, 1e-12))

    /** A two-pole filter, direct form I. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            // BS.1770's stage-one shelf and stage-two high-pass, for any sample rate.
            fun shelf(rate: Int): Biquad {
                val f0 = 1681.974450955533
                val gain = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / rate)
                val vh = 10.0.pow(gain / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0,
                    (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(rate: Int): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / rate)
                val a0 = 1 + k / q + k * k
                return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
        }
    }

    /** Four times oversampling with a windowed sinc, for the peaks between samples. */
    private class Oversampler {
        private val history = DoubleArray(TAPS_PER_PHASE)
        private var position = 0

        fun peak(x: Double): Double {
            history[position] = x
            var highest = abs(x)
            for (phase in 1 until FACTOR) {
                var sum = 0.0
                for (t in 0 until TAPS_PER_PHASE) {
                    val sample = history[(position - t + TAPS_PER_PHASE) % TAPS_PER_PHASE]
                    sum += sample * KERNEL[t * FACTOR + phase]
                }
                highest = max(highest, abs(sum))
            }
            position = (position + 1) % TAPS_PER_PHASE
            return highest
        }

        companion object {
            const val FACTOR = 4
            const val TAPS_PER_PHASE = 12

            // h(n) = sinc((n - centre) / 4) × Hann, 48 taps.
            val KERNEL = DoubleArray(FACTOR * TAPS_PER_PHASE) { n ->
                val centre = (FACTOR * TAPS_PER_PHASE - 1) / 2.0 + 0.5
                val t = (n - centre) / FACTOR
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                val window = 0.5 - 0.5 * cos(2 * PI * n / (FACTOR * TAPS_PER_PHASE - 1))
                sinc * window
            }
        }
    }

    private companion object {
        const val BLOCK_STEPS = 4
        const val ABSOLUTE_GATE = -70.0
        const val RELATIVE_GATE = 10.0
    }
}

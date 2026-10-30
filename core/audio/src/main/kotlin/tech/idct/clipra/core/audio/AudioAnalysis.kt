package tech.idct.clipra.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a voice speaks (#40), for ducking: the sound is measured in 10 ms frames for its energy
 * in the speech band (300 Hz to 3.4 kHz) against a floor that follows the quietest stretches.
 * Frames well above the floor are speech; a short hangover bridges the gaps between words, and
 * stretches too short to be words are dropped. Mono at [rate]; [add] streams any length.
 */
class SpeechDetector(private val rate: Int) {
    private val frameSize = rate / 100
    private val low = Biquad.highPass(rate, LOW_HZ)
    private val high = Biquad.lowPass(rate, HIGH_HZ)
    private val levels = ArrayList<Float>()
    private var sum = 0.0
    private var count = 0

    fun add(input: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = high.process(low.process(input[i]))
            sum += x * x
            if (++count == frameSize) {
                levels += (10 * log10(max(sum / count, 1e-12))).toFloat()
                sum = 0.0
                count = 0
            }
        }
    }

    /** The speech, as microsecond ranges of the sound. */
    fun segments(): List<LongRange> {
        if (levels.isEmpty()) return emptyList()
        // The floor: a low percentile of the levels, so pauses and room tone set it.
        val sorted = levels.sorted()
        val floor = sorted[(sorted.size * FLOOR_PERCENTILE).toInt().coerceAtMost(sorted.size - 1)]
        val loudest = sorted.last()
        val threshold = max(floor + ABOVE_FLOOR_DB, loudest - BELOW_PEAK_DB)
        val speech = BooleanArray(levels.size) { levels[it] > threshold }
        // Bridge the gaps between words, then drop what is too short to be speech.
        val ranges = ArrayList<IntRange>()
        var start = -1
        var lastOn = -HANGOVER_FRAMES
        for (i in speech.indices) {
            if (speech[i]) {
                if (start < 0) start = i
                lastOn = i
            } else if (start >= 0 && i - lastOn > HANGOVER_FRAMES) {
                ranges += start..lastOn
                start = -1
            }
        }
        if (start >= 0) ranges += start..lastOn
        return ranges.filter { it.last - it.first + 1 >= MIN_FRAMES }
            .map { it.first * FRAME_US until (it.last + 1) * FRAME_US }
    }

    private companion object {
        const val LOW_HZ = 300.0
        const val HIGH_HZ = 3400.0
        const val FRAME_US = 10_000L
        const val FLOOR_PERCENTILE = 0.1f
        const val ABOVE_FLOOR_DB = 12f
        const val BELOW_PEAK_DB = 30f
        const val HANGOVER_FRAMES = 25
        const val MIN_FRAMES = 12
    }
}

/** A beat grid (#40): the beats, in microseconds of the sound, and its tempo. */
data class Beats(val timesUs: List<Long>, val bpm: Float)

/**
 * Beats on music (#40): onsets from the spectral flux (how much each frequency's level rises)
 * in 10 ms steps, the tempo from the flux's autocorrelation (60 to 180 BPM, leaning towards
 * 120 so half and double tempos lose), then the beats by dynamic programming: the line of onsets
 * that keeps closest to the tempo. Mono at [rate]; [add] streams any length.
 */
class BeatDetector(private val rate: Int) {
    private val hop = rate / 100
    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / SIZE)).toFloat() }
    private val frame = FloatArray(SIZE)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private var previous = FloatArray(BINS)
    private var filled = 0
    private var frames = 0
    private val flux = ArrayList<Float>()
    private val topBin = (MAX_HZ * SIZE / rate).toInt().coerceAtMost(BINS - 1)
    private val bands = BAND_EDGES_HZ.zipWithNext().map { (from, to) ->
        val first = (from * SIZE / rate).toInt().coerceAtLeast(1)
        val last = (to * SIZE / rate).toInt().coerceIn(first, topBin)
        first..last
    }

    fun add(input: FloatArray, n: Int) {
        var i = 0
        while (i < n) {
            val take = min(SIZE - filled, n - i)
            System.arraycopy(input, i, frame, filled, take)
            filled += take
            i += take
            if (filled == SIZE) {
                measure()
                System.arraycopy(frame, hop, frame, 0, SIZE - hop)
                filled = SIZE - hop
            }
        }
    }

    /** The beats, or null when the sound is too short or has no steady pulse. */
    fun beats(): Beats? {
        if (flux.size < MIN_FRAMES) return null
        val onset = normalized(flux)
        val period = tempo(onset) ?: return null
        val beats = track(onset, period)
        if (beats.size < 2) return null
        // Frame k is the window from k * hop; an onset rises most once it reaches the window's
        // middle.
        val shift = SIZE / 2.0 / rate
        val times = beats.map { ((it * hop.toDouble() / rate + shift) * 1e6).toLong() }
        return Beats(times, (60.0 * 100 / period).toFloat())
    }

    private fun measure() {
        for (k in 0 until SIZE) {
            re[k] = frame[k] * window[k]
            im[k] = 0f
        }
        Fft.transform(re, im, inverse = false)
        val current = FloatArray(BINS)
        for (k in 1..topBin) {
            current[k] = ln(1f + COMPRESSION * sqrt(re[k] * re[k] + im[k] * im[k]))
        }
        // Each band's average rise counts alike, the lowest (kicks) twice: a broadband hi-hat
        // has many more bins than a kick but shouldn't decide the beat on its own.
        var rise = 0f
        if (frames > 0) {
            for ((band, edges) in bands.withIndex()) {
                var sum = 0f
                for (k in edges) sum += max(0f, current[k] - previous[k])
                rise += sum / edges.count() * if (band < LOW_BANDS) 2f else 1f
            }
        }
        previous = current
        flux += rise
        frames++
    }

    /** The flux less its local average, never below zero, and scaled to its spread. */
    private fun normalized(raw: List<Float>): FloatArray {
        val out = FloatArray(raw.size)
        val half = LOCAL_FRAMES / 2
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in raw.indices) {
            while (hi < min(raw.size - 1, i + half)) sum += raw[++hi]
            while (lo < i - half) sum -= raw[lo++]
            out[i] = max(0f, raw[i] - (sum / (hi - lo + 1)).toFloat())
        }
        val spread = sqrt(out.sumOf { (it * it).toDouble() } / out.size).toFloat()
        if (spread > 0) for (i in out.indices) out[i] /= spread
        return out
    }

    /** The beat period in frames (fractional), from the autocorrelation, or null. */
    private fun tempo(onset: FloatArray): Double? {
        val minLag = (6000.0 / MAX_BPM).toInt()
        val maxLag = (6000.0 / MIN_BPM).toInt()
        if (onset.size < maxLag * 3) return null
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            for (i in lag until onset.size) s += onset[i] * onset[i - lag]
            scores[lag] = s / (onset.size - lag)
        }
        var best = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            // A log-normal lean towards 120 BPM (50 frames), an octave wide.
            val octaves = ln(lag / PREFERRED_LAG) / ln(2.0)
            val weighted =
                scores[lag] * exp(-0.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            if (weighted > bestScore) {
                bestScore = weighted
                best = lag
            }
        }
        if (best < 0 || bestScore <= 0) return null
        // Between frames: the peak of a parabola through the best lag and its neighbours.
        val a = scores[best - 1]
        val b = scores[best]
        val c = scores[best + 1]
        val bend = a - 2 * b + c
        val offset = if (bend < 0) (0.5 * (a - c) / bend).coerceIn(-0.5, 0.5) else 0.0
        return best + offset
    }

    /** The beats as frames: the best line of onsets about [period] apart (Ellis, 2007). */
    private fun track(onset: FloatArray, period: Double): List<Int> {
        val n = onset.size
        val score = DoubleArray(n)
        val from = IntArray(n) { -1 }
        val lo = (period / 2).toInt().coerceAtLeast(1)
        val hi = (period * 2).toInt()
        for (t in 0 until n) {
            var best = 0.0
            var bestFrom = -1
            for (gap in lo..hi) {
                val p = t - gap
                if (p < 0) break
                val stretch = ln(gap / period)
                val s = score[p] - TIGHTNESS * stretch * stretch
                if (bestFrom < 0 || s > best) {
                    best = s
                    bestFrom = p
                }
            }
            score[t] = onset[t] + if (bestFrom >= 0) max(0.0, best) else 0.0
            from[t] = if (bestFrom >= 0 && best > 0) bestFrom else -1
        }
        // From the best of the last period back.
        var t = (max(0, n - period.toInt()) until n).maxByOrNull { score[it] } ?: return emptyList()
        val beats = ArrayList<Int>()
        while (t >= 0) {
            beats += t
            t = from[t]
        }
        return beats.reversed()
    }

    private companion object {
        const val SIZE = 1024
        const val BINS = SIZE / 2 + 1
        const val MAX_HZ = 8000.0
        const val COMPRESSION = 100f
        const val LOCAL_FRAMES = 50
        const val MIN_FRAMES = 300
        const val MIN_BPM = 60.0
        const val MAX_BPM = 180.0
        const val PREFERRED_LAG = 50.0
        const val PRIOR_OCTAVES = 1.0
        const val TIGHTNESS = 100.0
        val BAND_EDGES_HZ = listOf(30.0, 150.0, 400.0, 1000.0, 2500.0, 5000.0, 8000.0)
        const val LOW_BANDS = 2
    }
}

/** A second-order filter (RBJ's cookbook), for the speech band. */
internal class Biquad(
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

    fun process(x: Float): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x.toDouble()
        y2 = y1
        y1 = y
        return y
    }

    fun process(x: Double): Double = process(x.toFloat())

    companion object {
        private const val Q = 0.7071

        fun highPass(rate: Int, hz: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = kotlin.math.sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                (1 + c) / 2 / a0,
                -(1 + c) / a0,
                (1 + c) / 2 / a0,
                -2 * c / a0,
                (1 - alpha) / a0,
            )
        }

        fun lowPass(rate: Int, hz: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = kotlin.math.sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                (1 - c) / 2 / a0,
                (1 - c) / a0,
                (1 - c) / 2 / a0,
                -2 * c / a0,
                (1 - alpha) / a0,
            )
        }
    }
}

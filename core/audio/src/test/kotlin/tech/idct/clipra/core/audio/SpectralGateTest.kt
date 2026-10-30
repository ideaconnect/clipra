package tech.idct.clipra.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Noise reduction (#38): on a recording of 50 Hz hum with its harmonics and broadband hiss, with
 * a tone coming and going over it, the gate at its default strength takes the hum and the hiss
 * well down and leaves the tone as it was.
 */
class SpectralGateTest {
    private val rate = 48_000
    private val seconds = 6

    /** 50 Hz hum and hiss throughout; a 1 kHz tone in every other half second. */
    private fun recording(): Pair<FloatArray, FloatArray> {
        val random = Random(7)
        val noisy = FloatArray(rate * seconds)
        val tone = FloatArray(rate * seconds)
        for (i in noisy.indices) {
            val t = i.toDouble() / rate
            val hum =
                0.04 * sin(2 * PI * 50 * t) + 0.02 * sin(2 * PI * 100 * t) +
                    0.01 * sin(2 * PI * 150 * t)
            val hiss = 0.015 * random.nextGaussian()
            val on = (t * 2).toInt() % 2 == 1
            tone[i] = if (on) (0.3 * sin(2 * PI * 1000 * t)).toFloat() else 0f
            noisy[i] = (hum + hiss).toFloat() + tone[i]
        }
        return noisy to tone
    }

    private fun gate(input: FloatArray, strength: Float): FloatArray {
        val profile = NoiseProfile()
        for (start in input.indices step 4096) {
            val n = minOf(4096, input.size - start)
            profile.add(input.copyOfRange(start, start + n), n)
        }
        val gate = SpectralGate(profile.floor(), strength)
        val out = ArrayList<Float>(input.size)
        val emit = { block: FloatArray, n: Int -> for (i in 0 until n) out += block[i] }
        for (start in input.indices step 1000) {
            val n = minOf(1000, input.size - start)
            gate.process(input.copyOfRange(start, start + n), n, emit)
        }
        gate.finish(emit)
        return out.toFloatArray()
    }

    @Test
    fun humAndHissGoDownAndTheToneStays() {
        val (noisy, tone) = recording()
        val gated = gate(noisy, DEFAULT)
        assertEquals(noisy.size, gated.size)
        // In the pauses (the tone off), skipping the edges of each half second.
        val pauses = (0 until seconds * 2 step 2).map { half -> span(half, 0.1, 0.4) }
        val tones = (1 until seconds * 2 step 2).map { half -> span(half, 0.1, 0.4) }
        val humBefore = pauses.map { goertzel(noisy, it, 50.0) }.average()
        val humAfter = pauses.map { goertzel(gated, it, 50.0) }.average()
        val noiseBefore = pauses.map { rms(noisy, it) }.average()
        val noiseAfter = pauses.map { rms(gated, it) }.average()
        val toneBefore = tones.map { goertzel(noisy, it, 1000.0) }.average()
        val toneAfter = tones.map { goertzel(gated, it, 1000.0) }.average()
        val humDb = db(humAfter / humBefore)
        val noiseDb = db(noiseAfter / noiseBefore)
        val toneDb = db(toneAfter / toneBefore)
        println("hum $humDb dB, noise in pauses $noiseDb dB, tone $toneDb dB")
        assertTrue("hum down $humDb dB", humDb < -10)
        assertTrue("noise down $noiseDb dB", noiseDb < -10)
        assertEquals("tone kept", 0.0, toneDb, 1.0)
        // No clicks: no jump between samples larger than the recording's own.
        fun jump(x: FloatArray) = (1 until x.size).maxOf { kotlin.math.abs(x[it] - x[it - 1]) }
        assertTrue("jumps ${jump(gated)} vs ${jump(noisy)}", jump(gated) <= jump(noisy) * 1.05f)
        // No clipping, and the tone is where it was (the output is lined up).
        assertTrue(gated.all { it in -1f..1f })
        val tonePart = tones.first()
        val correlation = tonePart.sumOf { (gated[it] * tone[it]).toDouble() } /
            tonePart.sumOf { (tone[it] * tone[it]).toDouble() }
        assertEquals(1.0, correlation, 0.1)
    }

    @Test
    fun strengthSetsHowFarTheNoiseGoesDown() {
        val (noisy, _) = recording()
        val pauses = (0 until seconds * 2 step 2).map { half -> span(half, 0.1, 0.4) }
        fun noiseAfter(strength: Float) = pauses.map { rms(gate(noisy, strength), it) }.average()
        val light = noiseAfter(0.2f)
        val strong = noiseAfter(1f)
        assertTrue("light $light, strong $strong", strong < light * 0.5)
        // At zero strength, the sound comes back as it went in.
        val untouched = gate(noisy, 0f)
        val worst = noisy.indices.maxOf { kotlin.math.abs(noisy[it] - untouched[it]) }
        assertTrue("off by $worst", worst < 1e-3f)
    }

    @Test
    fun theFftGoesThereAndBack() {
        val random = Random(1)
        val re = FloatArray(1024) { random.nextFloat() - 0.5f }
        val im = FloatArray(1024)
        val original = re.copyOf()
        Fft.transform(re, im, inverse = false)
        // A cosine of 3 cycles lands in bin 3.
        val cosine = FloatArray(1024) { cos(2 * PI * 3 * it / 1024).toFloat() }
        val cIm = FloatArray(1024)
        Fft.transform(cosine, cIm, inverse = false)
        assertEquals(512f, cosine[3], 1e-2f)
        Fft.transform(re, im, inverse = true)
        for (i in original.indices) assertEquals(original[i], re[i], 1e-4f)
    }

    /** Samples of half second [half] from [from] to [to] of it. */
    private fun span(half: Int, from: Double, to: Double): IntRange {
        val start = ((half * 0.5 + from) * rate).toInt()
        val end = ((half * 0.5 + to) * rate).toInt()
        return start until end
    }

    private fun rms(x: FloatArray, range: IntRange): Double =
        sqrt(range.sumOf { (x[it] * x[it]).toDouble() } / range.count())

    /** The amplitude of [hz] in [range] of [x]. */
    private fun goertzel(x: FloatArray, range: IntRange, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in range) {
            val phase = 2 * PI * hz * i / rate
            re += x[i] * cos(phase)
            im += x[i] * sin(phase)
        }
        return 2 * sqrt(re * re + im * im) / range.count()
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    private companion object {
        const val DEFAULT = 0.4f
    }
}

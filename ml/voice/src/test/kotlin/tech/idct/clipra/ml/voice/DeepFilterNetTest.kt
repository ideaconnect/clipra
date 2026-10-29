package tech.idct.clipra.ml.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The DeepFilterNet port against libDF: the FFT, the ERB bands, the features (values from the
 * Python prototype of libDF's pipeline), and a lossless round trip through the streaming STFT,
 * chunks included.
 */
class DeepFilterNetTest {
    @Test
    fun theFftMatchesTheDefinition() {
        val n = 960
        val random = Random(5)
        val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val fftRe = re.copyOf()
        val fftIm = im.copyOf()
        Fft(n).transform(fftRe, fftIm)
        for (k in listOf(0, 1, 7, 480, 959)) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2 * PI * k * t / n
                sr += re[t] * cos(a) - im[t] * sin(a)
                si += re[t] * sin(a) + im[t] * cos(a)
            }
            assertEquals(sr, fftRe[k], 1e-9)
            assertEquals(si, fftIm[k], 1e-9)
        }
        Fft(n).transform(fftRe, fftIm, inverse = true)
        for (t in 0 until n) assertEquals(re[t] * n, fftRe[t], 1e-8)
    }

    @Test
    fun erbBandsMatchLibDf() {
        val expected = intArrayOf(
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 7,
            7, 8, 10, 12, 13, 15, 18, 20, 24, 28, 31, 37, 42, 50, 56, 67,
        )
        assertArrayEquals(expected, DeepFilterNet.WIDTHS)
        assertEquals(481, DeepFilterNet.WIDTHS.sum())
    }

    @Test
    fun featuresMatchLibDf() {
        // A 440 Hz sine at half scale, 0.1 s. The network sees frame t at row t - 2.
        val sine = FloatArray(4_800) { (sin(2 * PI * 440 * it / 48_000) * 0.5).toFloat() }
        var erb = FloatArray(0)
        var spec = FloatArray(0)
        var rows = 0
        val model = DeepFilterModel { e, s, frames ->
            erb = e
            spec = s
            rows = frames
            identity(frames)
        }
        DeepFilterNet(model).process(source(sine), sine.size.toLong(), { _, _ -> })
        assertEquals(12, rows)
        val expected = floatArrayOf(-0.74102324f, -0.3968913f, -0.05709667f, 0.44443846f)
        for (b in expected.indices) assertEquals(expected[b], erb[3 * 32 + b], 1e-4f)
        assertEquals(-0.22654971f, erb[7 * 32 + 31], 1e-4f)
        assertEquals(-0.0010194705f, spec[3 * 96 + 9], 1e-4f)
        assertEquals(1.5637875f, spec[rows * 96 + 3 * 96 + 9], 1e-4f)
    }

    @Test
    fun aNetworkThatChangesNothingGivesTheInputBack() {
        // 21 s crosses two chunk boundaries; an odd length checks the end.
        val random = Random(9)
        val input = FloatArray(21 * 48_000 + 123) { i ->
            (0.3 * sin(2 * PI * 220 * i / 48_000) + 0.1 * random.nextDouble(-1.0, 1.0)).toFloat()
        }
        val output = FloatArray(input.size)
        var written = 0
        var chunks = 0
        val model = DeepFilterModel { _, _, frames ->
            chunks++
            identity(frames)
        }
        DeepFilterNet(model).process(source(input), input.size.toLong(), { buffer, count ->
            buffer.copyInto(output, written, 0, count)
            written += count
        })
        assertEquals(input.size, written)
        assertEquals(3, chunks)
        var worst = 0f
        for (i in input.indices) worst = maxOf(worst, abs(input[i] - output[i]))
        assertEquals(0f, worst, 1e-5f)
    }

    @Test
    fun aMaskOfZerosSilencesTheUpperBandsAndTheDeepFilterTheLowerOnes() {
        val input = FloatArray(48_000) { (sin(2 * PI * 5_000 * it / 48_000) * 0.5).toFloat() }
        var peak = 0f
        DeepFilterNet { _, _, frames ->
            DeepFilterModel.Estimate(FloatArray(frames * 32), FloatArray(frames * 96 * 10))
        }.process(source(input), input.size.toLong(), { buffer, count ->
            for (i in 0 until count) peak = maxOf(peak, abs(buffer[i]))
        })
        assertEquals(0f, peak, 1e-6f)
    }

    @Test
    fun theResamplerKeepsPitchAndLevel() {
        val rate = 44_100
        val tone = FloatArray(rate) { (0.5 * sin(2 * PI * 1_000 * it / rate)).toFloat() }
        val resampled = Resampler(source(tone), rate, 48_000)
        val out = FloatArray(60_000)
        var n = 0
        while (true) {
            val read = resampled.read(out, n, out.size - n)
            if (read < 0) break
            n += read
        }
        assertEquals(48_000.0, n.toDouble(), 2.0)
        // Away from the ends, the output is the same tone at 48 kHz.
        var worst = 0.0
        for (i in 1_000 until 47_000) {
            worst = maxOf(worst, abs(out[i] - 0.5 * sin(2 * PI * 1_000 * i / 48_000)))
        }
        assertEquals(0.0, worst, 2e-3)
        assertEquals(0.5, hypot(out[12_000].toDouble(), out[12_012].toDouble()), 0.01)
    }

    private fun identity(frames: Int): DeepFilterModel.Estimate {
        val coefs = FloatArray(frames * 96 * 10)
        // The deep filter's tap for the frame itself: k = order - 1 - lookahead = 2.
        for (row in 0 until frames) for (f in 0 until 96) coefs[(row * 96 + f) * 10 + 2 * 2] = 1f
        return DeepFilterModel.Estimate(FloatArray(frames * 32) { 1f }, coefs)
    }

    private fun source(samples: FloatArray): SampleSource {
        var position = 0
        return SampleSource { buffer, offset, count ->
            if (position >= samples.size) return@SampleSource -1
            val n = minOf(count, samples.size - position, 1_000)
            samples.copyInto(buffer, offset, position, position + n)
            position += n
            n
        }
    }
}

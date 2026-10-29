package tech.idct.clipra.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class LoudnessMeterTest {
    private val rate = 48_000

    /** Stereo, both channels a [hz] sine peaking at [dbfs], for [seconds]. */
    private fun sine(hz: Double, dbfs: Double, seconds: Double, phase: Double = 0.0): ShortArray {
        val amplitude = 10.0.pow(dbfs / 20) * 32767
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (amplitude * sin(2 * PI * hz * t + phase)).roundToInt().toShort()
        }
    }

    private fun measure(vararg parts: ShortArray): LoudnessMeter =
        LoudnessMeter(rate, 2).apply { parts.forEach { add(it, it.size) } }

    @Test
    fun aReferenceToneReadsItsLevel() {
        // EBU Tech 3341: a stereo 1 kHz sine at -23 dBFS reads -23 LUFS.
        assertEquals(-23.0, measure(sine(1000.0, -23.0, 20.0)).integratedLufs()!!, 0.1)
        assertEquals(-14.0, measure(sine(1000.0, -14.0, 20.0)).integratedLufs()!!, 0.1)
    }

    @Test
    fun silenceIsGatedOut() {
        val tone = sine(1000.0, -20.0, 10.0)
        val silence = ShortArray(rate * 2 * 10)
        assertEquals(-20.0, measure(tone, silence).integratedLufs()!!, 0.1)
        assertNull(measure(silence).integratedLufs())
    }

    @Test
    fun theKWeightingCutsLowBass() {
        // 40 Hz sits on the high-pass slope: it reads quieter than 1 kHz at the same level.
        val bass = measure(sine(40.0, -20.0, 10.0)).integratedLufs()!!
        assertEquals(true, bass < -20.5)
    }

    @Test
    fun truePeakFindsPeaksBetweenSamples() {
        // A quarter of the sample rate, 45 degrees off: samples reach 0.707, the wave reaches 1.
        val meter = measure(sine(rate / 4.0, -1.0, 1.0, phase = PI / 4))
        assertEquals(-1.0, meter.truePeakDb(), 0.6)
    }
}

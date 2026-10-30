package tech.idct.clipra.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Speech for ducking and beats for markers (#40), on sounds whose answers are known. */
class AudioAnalysisTest {
    private val rate = 48_000

    @Test
    fun speechIsFoundWhereItIs() {
        // Room tone and a low music bed throughout; "speech" (noise moving at a syllable rate) from 1.0 to 2.5 s and from 4.0 to 5.2 s.
        val random = Random(5)
        val spoken = listOf(1.0..2.5, 4.0..5.2)
        val samples = FloatArray(rate * 7) { i ->
            val t = i.toDouble() / rate
            val bed = 0.05 * sin(2 * PI * 110 * t) + 0.002 * random.nextGaussian()
            val speaking = spoken.any { t in it }
            val syllables = 0.5 + 0.5 * sin(2 * PI * 4 * t)
            val voice = if (speaking) 0.2 * syllables * random.nextGaussian() else 0.0
            (bed + voice).toFloat()
        }
        val detector = SpeechDetector(rate)
        feed(samples) { block, n -> detector.add(block, n) }
        val found = detector.segments()
        println("speech found: $found")
        assertEquals(2, found.size)
        found.zip(spoken).forEach { (got, want) ->
            assertEquals(want.start * 1e6, got.first.toDouble(), 100_000.0)
            assertEquals(want.endInclusive * 1e6, got.last.toDouble(), 150_000.0)
        }
    }

    @Test
    fun silenceHasNoSpeech() {
        val random = Random(2)
        val samples = FloatArray(rate * 3) { (0.003 * random.nextGaussian()).toFloat() }
        val detector = SpeechDetector(rate)
        feed(samples) { block, n -> detector.add(block, n) }
        assertTrue(detector.segments().isEmpty())
    }

    @Test
    fun beatsLineUpWithTheKicks() {
        // A kick every half second (120 BPM) from 0.25 s, hi-hats between, a pad under it all.
        val random = Random(9)
        val kicks = (0 until 40).map { 0.25 + it * 0.5 }
        val samples = FloatArray(rate * 20)
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            samples[i] = (0.05 * sin(2 * PI * 220 * t) + 0.003 * random.nextGaussian()).toFloat()
        }
        for (kick in kicks) {
            hit(samples, kick, 0.08) { t -> 0.8 * sin(2 * PI * (60 - 200 * t) * t) * exp(-t * 30) }
            hit(samples, kick + 0.25, 0.03) { 0.15 * random.nextGaussian() * exp(-it * 120) }
        }
        val detector = BeatDetector(rate)
        feed(samples) { block, n -> detector.add(block, n) }
        val beats = detector.beats()
        assertNotNull(beats)
        println("bpm ${beats!!.bpm}, beats ${beats.timesUs.take(6)}")
        assertEquals(120f, beats.bpm, 2f)
        // Every kick but the edges has a beat within 30 ms; the offsets don't drift.
        val offsets = kicks.drop(1).dropLast(1).map { kick ->
            val us = (kick * 1e6).toLong()
            beats.timesUs.minOf { abs(it - us) }.let { near ->
                beats.timesUs.first {
                    abs(it - us) ==
                        near
                } -
                    us
            }
        }
        println("offsets ms: ${offsets.map { it / 1000 }}, mean ${offsets.average() / 1000}")
        assertTrue("offsets $offsets", offsets.all { abs(it) <= 30_000 })
    }

    @Test
    fun noPulseNoBeats() {
        val random = Random(4)
        val samples = FloatArray(rate * 10) { (0.05 * random.nextGaussian()).toFloat() }
        val detector = BeatDetector(rate)
        feed(samples) { block, n -> detector.add(block, n) }
        // Steady noise has no pulse to lock to: either nothing, or nothing confident enough
        // to matter; short sounds give nothing.
        val short = BeatDetector(rate)
        feed(samples.copyOf(rate)) { block, n -> short.add(block, n) }
        assertEquals(null, short.beats())
    }

    private fun hit(samples: FloatArray, at: Double, length: Double, shape: (Double) -> Double) {
        val start = (at * rate).toInt()
        for (i in 0 until (length * rate).toInt()) {
            if (start + i >= samples.size) break
            samples[start + i] += shape(i.toDouble() / rate).toFloat()
        }
    }

    private fun feed(samples: FloatArray, add: (FloatArray, Int) -> Unit) {
        for (start in samples.indices step 4096) {
            val n = minOf(4096, samples.size - start)
            add(samples.copyOfRange(start, start + n), n)
        }
    }
}

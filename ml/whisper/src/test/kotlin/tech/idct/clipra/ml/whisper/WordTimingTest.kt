package tech.idct.clipra.ml.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Word edges snap to the pauses around them; noisy sound and far pauses leave them alone. */
class WordTimingTest {
    private val rate = 16_000

    /** Tone bursts (speech) at [bursts] (start to end, in seconds) over faint noise. */
    private fun sound(
        seconds: Double,
        bursts: List<Pair<Double, Double>>,
        noise: Float,
    ): FloatArray {
        val random = Random(1)
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            val speaking = bursts.any { (a, b) -> t in a..<b }
            val voice = if (speaking) 0.3 * sin(2 * PI * 220 * t) else 0.0
            (voice + noise * random.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    private fun word(text: String, start: Double, end: Double) =
        Whisper.Word(text, (start * 1e6).toLong(), (end * 1e6).toLong(), 0.9f)

    @Test
    fun findsThePausesBetweenWords() {
        val samples = sound(3.0, listOf(0.5 to 1.2, 1.6 to 2.4), noise = 0.001f)
        val pauses = WordTiming.pauses(samples, rate)
        assertEquals(3, pauses.size)
        assertEquals(1_200_000.0, pauses[1].startUs.toDouble(), 10_000.0)
        assertEquals(1_600_000.0, pauses[1].endUs.toDouble(), 10_000.0)
    }

    @Test
    fun wordEdgesSnapToNearbyPauses() {
        val samples = sound(3.0, listOf(0.5 to 1.2, 1.6 to 2.4), noise = 0.001f)
        // Whisper's times, a little off.
        val words = listOf(word("one", 0.3, 0.9), word("two", 0.9, 1.4), word("three", 1.8, 2.2))
        val refined = WordTiming.refine(words, WordTiming.pauses(samples, rate))
        assertEquals(500_000.0, refined[0].startUs.toDouble(), 10_000.0)
        assertEquals(900_000L, refined[0].endUs) // inside speech: unchanged
        assertEquals(1_200_000.0, refined[1].endUs.toDouble(), 10_000.0)
        assertEquals(1_600_000.0, refined[2].startUs.toDouble(), 10_000.0)
        assertEquals(2_400_000.0, refined[2].endUs.toDouble(), 10_000.0)
    }

    @Test
    fun speechStartsAfterTheOpeningQuietAndItsClicks() {
        // Quiet with two 20 ms clicks, then speech from 0.8 s.
        val samples = sound(3.0, listOf(0.15 to 0.17, 0.7 to 0.72, 0.8 to 1.5, 1.9 to 2.6), 0.001f)
        val start = WordTiming.speechStart(samples, rate)
        assertEquals(0.7, start.toDouble() / rate, 0.011)

        val opening = sound(3.0, listOf(0.0 to 1.2, 1.6 to 2.4), noise = 0.001f)
        assertEquals(0, WordTiming.speechStart(opening, rate))

        val noisy = sound(3.0, listOf(0.8 to 1.5), noise = 0.2f)
        assertEquals(0, WordTiming.speechStart(noisy, rate))
    }

    @Test
    fun farPausesAndNoisySoundLeaveWordsAlone() {
        val samples = sound(3.0, listOf(0.5 to 1.2, 1.6 to 2.4), noise = 0.001f)
        val words = listOf(word("far", 2.9, 2.95))
        assertEquals(words, WordTiming.refine(words, WordTiming.pauses(samples, rate)))

        val noisy = sound(3.0, listOf(0.5 to 1.2, 1.6 to 2.4), noise = 0.2f)
        assertTrue(WordTiming.pauses(noisy, rate).isEmpty())
    }
}

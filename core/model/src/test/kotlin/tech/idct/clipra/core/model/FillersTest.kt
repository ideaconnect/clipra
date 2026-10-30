package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filler words in English and Polish (#76), as Edit by text cuts them (#48). */
class FillersTest {
    private fun words(vararg text: String) = text.mapIndexed { i, word ->
        Word(word, startUs = i * 500_000L, endUs = i * 500_000L + 400_000, confidence = 0.9f)
    }

    @Test
    fun singleFillersInBothLanguages() {
        listOf("um", "Uh,", "erm", "hmm...").forEach { assertTrue(it, Fillers.isFiller(it, "en")) }
        listOf("yyy", "Eee,", "ehm", "yhm").forEach { assertTrue(it, Fillers.isFiller(it, "pl")) }
        assertFalse(Fillers.isFiller("yyy", "en"))
        assertFalse(Fillers.isFiller("Lisbon", "en"))
    }

    @Test
    fun anAsideBecomesOneFiller() {
        val merged = Fillers.withPhrases(words("It", "was,", "you", "know,", "amazing."), "en")
        assertEquals(listOf("It", "was,", "you know,", "amazing."), merged.map { it.text })
        val aside = merged[2]
        assertEquals(WordKind.Filler, aside.kind)
        assertEquals(1_000_000L, aside.startUs)
        assertEquals(1_900_000L, aside.endUs)
    }

    @Test
    fun speechIsNotAFiller() {
        val question = words("Do", "you", "know", "where", "it", "is?")
        assertEquals(question, Fillers.withPhrases(question, "en"))
        // At the very start there is nothing to set it off.
        val start = words("You", "know,", "Lisbon", "is", "warm.")
        assertEquals(start, Fillers.withPhrases(start, "en"))
    }

    @Test
    fun polishAsides() {
        val merged = Fillers.withPhrases(
            words("To", "było,", "no", "wiesz,", "piękne.", "A", "potem,", "jakby,", "wróciliśmy."),
            "pl",
        )
        assertEquals(
            listOf("To", "było,", "no wiesz,", "piękne.", "A", "potem,", "jakby,", "wróciliśmy."),
            merged.map { it.text },
        )
        assertEquals(2, merged.count { it.kind == WordKind.Filler })
        // English phrases mean nothing in Polish.
        val english = words("Było,", "you", "know,", "super.")
        assertEquals(english, Fillers.withPhrases(english, "pl"))
    }
}

package tech.idct.clipra.ml.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Chrf] scores as sacrebleu does, and [Chrf.SAME_TRANSLATION] lets through the drift between
 * arm64 and x86_64 while it catches wrong output (#202). No model needed.
 */
@RunWith(AndroidJUnit4::class)
class ChrfTest {
    @Test
    fun scoresAsSacrebleuDoes() {
        // sacrebleu 2.6.0: CHRF().sentence_score(hypothesis, [reference]).score
        assertEquals(100.0, Chrf.score(LIGHT, LIGHT), 1e-9)
        assertEquals(87.9301, Chrf.score("Jutro jedzimy tramwajem do Alfamy 🚋", TRAM), 1e-4)
        assertEquals(
            23.5843,
            Chrf.score("Tomorrow we're taking the tram up to Alfama 🚋", TRAM),
            1e-4,
        )
        assertEquals(7.3868, Chrf.score(LISBON, LIGHT), 1e-4)
        assertEquals(63.6364, Chrf.score("ab", "abc"), 1e-4)
        assertEquals(0.0, Chrf.score("", LIGHT), 0.0)
        assertEquals(0.0, Chrf.score(" ", ""), 0.0)
    }

    @Test
    fun letsTheDriftBetweenArm64AndX86Through() {
        // Of 32 lines translated with the same model, the ones that came out differently on a
        // Galaxy S9 (arm64) and on the Pixel_3_API_29 emulator (x86_64).
        val drift = listOf(
            "Jutro jedzimy tramwajem do Alfamy 🚋" to TRAM,
            "Nie zapomnij polubić i subskrybować" to "Nie zapomnij polubić i zapisać się",
            "Pogoda była straszna, więc zostaliśmy w środku przez cały dzień" to
                "Pogoda była straszna, więc zostaliśmy w środku cały dzień",
            "Muzeum otwiera się o dziewiątem i zamyka o szóstej" to
                "Muzeum otwiera się o dziewiąte, zamyka o szóstej",
            "Będziemy dziś razem gotować kolację" to "Będziemy razem gotować kolację",
            "Dziękuję bardzo za oglądanie" to "Dziękuję bardzo za obejrzanie",
            "Nasz pociąg został opóźniony o czety minuty" to
                "Nasz pociąg został opóźniony o czety minut",
            "Kupiliśmy świeży chleb i ser na targu" to "Kupiliśmy świeży chleb i ser na rynku",
            "Są przyjaciółmi od czasów szkoły podstawowej" to
                "Przyjaźnią się od czasów szkoły podstawowej",
            "Chciałbym zamówić dwie kawy i ciasto, proszę" to
                "Chciałbym zamówić dwie kawy i ciasto",
            "Do zobaczenia w następnym filmie!" to "Do zobaczenia na następnym filmie!",
            "Jecaliśmy wzdłuż wybrzeża, kiedy zaczęła się burza" to
                "Jecaliśmy wzdłuż wybrzeża, gdy zaczęła się burza",
        )
        for ((arm64, x86) in drift) {
            val score = Chrf.score(arm64, x86)
            assertTrue("$arm64: $score", score >= Chrf.SAME_TRANSLATION)
        }
    }

    @Test
    fun catchesWrongOutput() {
        val wrong = listOf(
            // Untranslated
            "We finally made it to Lisbon" to LISBON,
            "The light here is unreal" to LIGHT,
            "Tomorrow we're taking the tram up to Alfama 🚋" to TRAM,
            // Empty
            "" to LISBON,
            "" to TRAM,
            // Another line's translation; the second pair was the closest of the 32 lines
            LIGHT to TRAM,
            TRAM to LISBON,
            "Jeśli jutro spadnie deszcz, zamiast tego odwiedzimy zamek" to
                "Moja siostra odwiedzi nas w przyszłym tygodniu",
            // Cut short and stuck on one word
            "Jutro jedziemy" to TRAM,
            "Jutro Jutro Jutro Jutro Jutro Jutro" to TRAM,
        )
        for ((output, expected) in wrong) {
            val score = Chrf.score(output, expected)
            assertTrue("\"$output\" for \"$expected\": $score", score < Chrf.SAME_TRANSLATION)
        }
    }

    private companion object {
        const val LISBON = "W końcu docieliśmy do Lizbonie"
        const val LIGHT = "Światło tutaj jest nierealne"
        const val TRAM = "Jutro jedziemy tramwajem do Alfamy 🚋"
    }
}

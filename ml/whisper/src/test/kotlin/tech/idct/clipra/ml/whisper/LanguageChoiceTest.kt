package tech.idct.clipra.ml.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

/** Clips Whisper is unsure of take the language heard clearly in the run (#188). */
class LanguageChoiceTest {
    private fun heard(language: String, probability: Float) =
        Whisper.Detection(language, probability)

    @Test
    fun aGuessTakesTheLanguageHeardClearly() {
        // A Polish talk, and street clips with faint voices Whisper took for Spanish and Russian.
        val picked = LanguageChoice.pick(
            mapOf(
                "talk" to heard("pl", 0.97f),
                "harbour" to heard("es", 0.21f),
                "cafe" to heard("ru", 0.11f),
            ),
        )
        assertEquals(mapOf("talk" to "pl", "harbour" to "pl", "cafe" to "pl"), picked)
    }

    @Test
    fun twoLanguagesSpokenClearlyStayApart() {
        val picked = LanguageChoice.pick(
            mapOf(
                "interview" to heard("en", 0.92f),
                "answer" to heard("pl", 0.88f),
                "more" to heard("pl", 0.81f),
                "street" to heard("de", 0.3f),
            ),
        )
        // The street clip takes Polish, heard most surely over the run.
        assertEquals(
            mapOf("interview" to "en", "answer" to "pl", "more" to "pl", "street" to "pl"),
            picked,
        )
    }

    @Test
    fun withNothingSureTheLikeliestLanguageGoesForAll() {
        val picked = LanguageChoice.pick(
            mapOf("a" to heard("pl", 0.4f), "b" to heard("pl", 0.3f), "c" to heard("es", 0.45f)),
        )
        assertEquals(mapOf("a" to "pl", "b" to "pl", "c" to "pl"), picked)
        val none = emptyMap<String, Whisper.Detection>()
        assertEquals(emptyMap<String, String>(), LanguageChoice.pick(none))
    }
}
